package club.xiaojiawei.hsscript.strategy.phase

import club.xiaojiawei.hsscript.bean.log.Block
import club.xiaojiawei.hsscript.bean.log.ExtraEntity
import club.xiaojiawei.hsscript.bean.log.TagChangeEntity
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.E2ETrace
import club.xiaojiawei.hsscript.status.ScreenWatchdogKind
import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
import club.xiaojiawei.hsscript.strategy.AbstractPhaseStrategy
import club.xiaojiawei.hsscript.utils.GameUtil.addGameEndTask
import club.xiaojiawei.hsscript.utils.GameResultScreenshot
import club.xiaojiawei.hsscript.utils.GameResultOutcomeDetector
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscriptbase.util.RandomUtil
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 游戏结束阶段
 * @author 肖嘉威
 * @date 2022/11/27 13:44
 */
object GameOverPhaseStrategy : AbstractPhaseStrategy() {

    private val duplicateCallbacks = AtomicInteger(0)
    @Volatile
    private var lastDuplicateWarningAt = 0L

    private fun recordDuplicateCallback() {
        val count = duplicateCallbacks.incrementAndGet()
        val now = System.currentTimeMillis()
        if (count == 1 || now - lastDuplicateWarningAt >= 5_000L) {
            lastDuplicateWarningAt = now
            // Power.log repeats the terminal block.  Once the first result
            // handler owns cleanup, later callbacks are deliberately ignored.
            club.xiaojiawei.hsscriptbase.config.log.debug {
                "GAME_OVER_DUPLICATE_CALLBACK_IGNORED count=$count phase=${war.currentPhase} " +
                    "step=${war.currentTurnStep} inWar=${WarEx.inWar}"
            }
        }
    }

    private val resultScreenshotCaptured = AtomicBoolean(false)
    /**
     * GAME_OVER is entered for every subsequent line in the result section of
     * Power.log.  Keep the cleanup idempotent so those lines cannot end the
     * same in-memory war repeatedly and inflate warCount.
     */
    private val resultHandlingStarted = AtomicBoolean(false)

    private val replayCleanupHandled = AtomicBoolean(false)

    /**
     * PLAYSTATE is normally available by the time GAME_OVER is dispatched,
     * but a stale/replayed Power.log can leave it unavailable forever.  The
     * old code returned on every callback in that case and wedged the state
     * machine in GAME_OVER.  Give the authoritative read a short grace period
     * and then perform one safe cleanup so a fresh game can start.
     */
    @Volatile
    private var e2eResultWaitStartedAt: Long = 0L

    private const val E2E_RESULT_WAIT_TIMEOUT_MS = 15_000L

    /**
     * Hearthstone writes PLAYSTATE before the result animation is painted.
     * A one-second capture can therefore save the last attack frame instead
     * of the actual "click to continue" result screen. Keep the screenshot
     * inside the result handler, but let the client finish that transition.
     */
    private const val RESULT_SCREENSHOT_DELAY_BASE_MS = 4_500L

    fun resetForNewGame() {
        resultScreenshotCaptured.set(false)
        resultHandlingStarted.set(false)
        replayCleanupHandled.set(false)
        e2eResultWaitStartedAt = 0L
    }

    fun forceTerminalFromScreenWatchdog(kind: ScreenWatchdogKind, evidence: String): Boolean {
        val powerLogTerminal = if (kind == ScreenWatchdogKind.RESULT) {
            E2ETrace.readPowerLogTerminal(PowerLogListener.logFile?.path(), war.me.gameId)
        } else null
        val resolution = resolveScreenWatchdogTerminal(kind, powerLogTerminal)
        if (!resolution.accepted) {
            club.xiaojiawei.hsscriptbase.config.log.warn {
                "SCREEN_STATE_CORRECTION_BLOCKED reason=result-without-authoritative-power-log-terminal evidence=$evidence"
            }
            return false
        }
        val resultOverride = resolution.winOverride
        if (!resultHandlingStarted.compareAndSet(false, true)) {
            club.xiaojiawei.hsscriptbase.config.log.warn {
                "SCREEN_STATE_CORRECTED_SKIPPED reason=result-handler-already-started kind=$kind evidence=$evidence"
            }
            return false
        }
        val completedGameNumber = WarEx.reserveCompletedGameNumber()
        resultOverride?.let { WarEx.endWar(it) }
        val outcome = resolution.outcome
        club.xiaojiawei.hsscriptbase.config.log.warn {
            "SCREEN_STATE_CORRECTED source=screen-watchdog terminal=$kind outcome=$outcome " +
                "action=RECORD_RESULT_AND_CLEAR_PAGE evidence=$evidence"
        }
        resultScreenshotCaptured.compareAndSet(false, true)
        GameResultScreenshot.capture(outcome, completedGameNumber)
        // Generic RESULT OCR can miss the localized WIN/LOST banner. When
        // Power.log has already proven the terminal state, reset the stale
        // in-war model here and let the caller use its bounded, postchecked
        // page dismissal path instead of the continuous generic click task.
        if (kind != ScreenWatchdogKind.RESULT) {
            addGameEndTask(
                MandatoryRankSurrenderGuard.existingTerminalCleanupCapability(),
            )
        }
        WarEx.reset()
        return true
    }

    override fun dealTagChangeThenIsOver(line: String, tagChangeEntity: TagChangeEntity): Boolean {
        over()
        return true
    }

    override fun dealShowEntityThenIsOver(line: String, extraEntity: ExtraEntity): Boolean {
        over()
        return true
    }

    override fun dealFullEntityThenIsOver(line: String, extraEntity: ExtraEntity): Boolean {
        over()
        return true
    }

    override fun dealChangeEntityThenIsOver(line: String, extraEntity: ExtraEntity): Boolean {
        over()
        return true
    }

    override fun dealBlockIsOver(line: String, block: Block): Boolean {
        over()
        return true
    }

    override fun dealBlockEndIsOver(line: String, block: Block?): Boolean {
        over()
        return true
    }

    override fun dealOtherThenIsOver(line: String): Boolean {
        over()
        return true
    }

    private fun over() {
        war.isMyTurn = false
        cancelAllTask()

        if (resultHandlingStarted.get()) {
            recordDuplicateCallback()
            return
        }
        if (e2eResultWaitStartedAt != 0L) {
            // GAME_OVER can be delivered more than once while the final
            // PLAYSTATE is still being flushed.  A second callback must not
            // run the cleanup/classification path with a different snapshot;
            // it would race the first callback and can publish UNKNOWN even
            // when the first callback later obtains the authoritative result.
            recordDuplicateCallback()
            return
        }

        // A watchdog restart replays the whole Power.log so the in-memory
        // model can be rebuilt.  That file can contain several completed
        // games before the currently active one.  Treating each historical
        // GAME_OVER as a live result would click the result screen, reset the
        // trace, and queue another game before the replay reaches the tail.
        // Reset only the model here; live result handling below must remain
        // untouched.
        if (PowerLogListener.replayingExistingLog) {
            if (!replayCleanupHandled.compareAndSet(false, true)) {
                recordDuplicateCallback()
                return
            }
            club.xiaojiawei.hsscriptbase.config.log.info {
                "E2E恢复回放：忽略历史结算事件，仅重置内存对局模型"
            }
            WarEx.reset(print = false)
            return
        }

        val e2eEnabled = System.getProperty("hs.script.e2e") == "true"
        // A surrender strategy intentionally ends the game before the normal
        // mulligan/turn/out-card milestones.  The current player's
        // CONCEDED marker is nevertheless an authoritative completed result
        // for the crash-stability gate, so accept that path as controlled too.
        // A fast-concede game can reach GAME_OVER before the player identity
        // has been copied into war.me.  The CONCEDED tag is still an
        // authoritative terminal marker for the current game in this process;
        // treating a non-empty marker as controlled keeps the alternating
        // concede round observable instead of misclassifying it as stale.
        val currentPlayerConceded = war.conceded.isNotBlank() || E2ETrace.surrenderRequested
        val scriptControlledGame = e2eEnabled &&
            (E2ETrace.isValidScriptControlledGame() || currentPlayerConceded)
        // GAME_OVER can be emitted a few seconds before the final PLAYSTATE
        // line reaches the parser.  This is not E2E-only: the normal app used
        // to capture the last attack frame as draw-or-unknown and let the
        // MCTS worker submit one stale action during that same race.
        val authoritativeTerminal = readAuthoritativeTerminal()
        val authoritativeOutcome = terminalToWinOverride(authoritativeTerminal)
        if (war.me.gameId.isNotBlank() && authoritativeOutcome == null) {
            val now = System.currentTimeMillis()
            val waitStartedAt = e2eResultWaitStartedAt
            if (waitStartedAt == 0L) {
                e2eResultWaitStartedAt = now
                club.xiaojiawei.hsscriptbase.config.log.info {
                    "E2E结算等待Power.log最终PLAYSTATE，最多等待${E2E_RESULT_WAIT_TIMEOUT_MS / 1000}秒"
                }
                return
            }
            if (now - waitStartedAt < E2E_RESULT_WAIT_TIMEOUT_MS) {
                return
            }
            club.xiaojiawei.hsscriptbase.config.log.warn {
                "E2E结算等待Power.log超时，继续清理结算状态，避免卡死在游戏结束阶段"
            }
        }

        e2eResultWaitStartedAt = 0L
        if (!resultHandlingStarted.compareAndSet(false, true)) {
            return
        }

        if (!resultScreenshotCaptured.compareAndSet(false, true)) {
            recordDuplicateCallback()
            return
        }

        // Reserve one all-matches ordinal before endWar() increments
        // warCount. The same ID must appear in the pre-finalization wait log,
        // the saved result screenshot, and the completion log.
        val completedGameNumber = WarEx.reserveCompletedGameNumber()

        val modelResultOutcome = if (e2eEnabled && !scriptControlledGame && authoritativeTerminal == null) {
            "draw-or-unknown"
        } else {
            classifyResultOutcome(
                isWin = WarEx.isWin,
                wonId = war.won,
                lostId = war.lost,
                concededId = war.conceded,
                ourId = war.me.gameId,
                localSurrenderRequested = currentPlayerConceded ||
                    authoritativeTerminal == E2ETrace.PowerLogTerminal.CONCEDED,
                authoritativeTerminal = authoritativeTerminal,
            )
        }

        var capturedResultImage: java.awt.image.BufferedImage? = null
        var screenshotOutcome = modelResultOutcome
        val screenshotDelayMs = RandomUtil.getActionInterval(RESULT_SCREENSHOT_DELAY_BASE_MS.toInt())
        club.xiaojiawei.hsscriptbase.config.log.info {
            "GAME_RESULT_SCREENSHOT_WAIT delayMs=$screenshotDelayMs outcome=$modelResultOutcome " +
                "game=$completedGameNumber scope=all-matches"
        }
        SystemUtil.delay(screenshotDelayMs)
        capturedResultImage = GameResultScreenshot.captureImage()

        // A fast disconnect/surrender can leave the current player's ID
        // unresolved even though the result banner is unambiguous. Use the
        // central result-banner ROI as a bounded visual fallback; authoritative
        // Power.log and a local surrender always take precedence.
        val visualOutcome = if (authoritativeOutcome == null &&
            !currentPlayerConceded &&
            capturedResultImage != null
        ) {
            GameResultOutcomeDetector.classifyImage(capturedResultImage!!)
        } else null
        val visualResultOverride = when (visualOutcome) {
            "win" -> true
            "loss" -> false
            else -> null
        }
        val finalResultOverride = authoritativeOutcome ?:
            if (currentPlayerConceded) false else visualResultOverride
        WarEx.endWar(finalResultOverride)
        screenshotOutcome = when {
            currentPlayerConceded -> "conceded"
            finalResultOverride == true -> "win"
            finalResultOverride == false -> "loss"
            else -> modelResultOutcome
        }
        club.xiaojiawei.hsscriptbase.config.log.info {
            "TERMINAL_RESULT_EVIDENCE model=$modelResultOutcome visual=${visualOutcome ?: "UNKNOWN"} " +
                "final=${screenshotOutcome ?: "none"} override=${finalResultOverride ?: "UNKNOWN"}"
        }

        val resultOutcome = if (screenshotOutcome.isNotBlank()) {
            // In E2E mode a terminal PLAYSTATE is not enough to call the
            // result a successful bot game. If the script milestones were
            // missing, keep the evidence explicitly non-winning even when
            // stale WarEx state still says win after a fast disconnect.
            screenshotOutcome
        } else null

        club.xiaojiawei.hsscriptbase.config.log.info {
            "TERMINAL_RESULT_CLASSIFIED outcome=${resultOutcome ?: "none"} " +
                "authoritative=${authoritativeOutcome ?: "UNKNOWN"} modelWin=${WarEx.isWin} " +
                "won=${war.won.ifBlank { "<blank>" }} " +
                "lost=${war.lost.ifBlank { "<blank>" }} " +
                "conceded=${war.conceded.ifBlank { "<blank>" }} " +
                "ourId=${war.me.gameId.ifBlank { "<blank>" }} " +
                "localSurrenderRequested=$currentPlayerConceded"
        }

        if (e2eEnabled) {
            if (scriptControlledGame) {
                val runId = System.getProperty("hs.script.e2e.run-id", "unknown")
                if (WarEx.isWin) {
                    club.xiaojiawei.hsscriptbase.config.log.info {
                        "E2E_WIN_RESULT $runId, game result recorded by script and player won"
                    }
                } else if (currentPlayerConceded) {
                    club.xiaojiawei.hsscriptbase.config.log.info {
                        "E2E_GAME_RESULT_CONCEDED $runId, authoritative current-player CONCEDED result"
                    }
                } else {
                    club.xiaojiawei.hsscriptbase.config.log.warn {
                        "E2E_GAME_RESULT_LOSS $runId, result was recorded but the player did not win"
                    }
                }
                E2ETrace.recordResult(WarEx.isWin)
            } else {
                club.xiaojiawei.hsscriptbase.config.log.warn {
                    "E2E_GAME_RESULT_REJECTED ${System.getProperty("hs.script.e2e.run-id", "unknown")}, " +
                        "missing script milestones: mulligan=${E2ETrace.mulliganCompleted}, " +
                        "ourTurn=${E2ETrace.ourTurnSeen}, outCard=${E2ETrace.outCardStarted}"
                }
            }
        }
        // Hearthstone publishes PLAYSTATE before the result animation is
        // fully painted. The image above was captured after that transition;
        // save it under the evidence-backed outcome before cleanup clicks.
        capturedResultImage?.let { image ->
            resultOutcome?.let { GameResultScreenshot.save(image, it, completedGameNumber) }
        }
        val accessFile = PowerLogListener.logFile
        accessFile?.seek(accessFile.length())
        val completeCurrentGamePowerLogTerminal =
            PowerLogListener.hasCurrentGameCompleteTerminalPowerLogEvidence()
        val ownEntityId = war.me.gameId
        val opponentEntityId = war.rival.gameId
        val surrenderTerminalEvidence = if (completeCurrentGamePowerLogTerminal) {
            PowerLogListener.currentGameSurrenderTerminalEvidence(ownEntityId, opponentEntityId)
        } else null
        val terminalCleanupCapability = MandatoryRankSurrenderGuard.authorizeTerminalCleanup(
            surrenderTerminalEvidence,
        )
        if (MandatoryRankSurrenderGuard.isPending()) {
            club.xiaojiawei.hsscriptbase.config.log.info {
                "RANK_SURRENDER_TERMINAL_PROOF result=${if (terminalCleanupCapability != null) "ACCEPTED" else "REJECTED"} " +
                    "source=CREATE_GAME_SCOPED_POWERLOG " +
                    "sameGame=${surrenderTerminalEvidence?.gameIdentity == "${PowerLogListener.currentGameSurrenderIdentity(ownEntityId)}"} " +
                    "ownPlayState=${surrenderTerminalEvidence?.ownPlayState ?: "UNKNOWN"} " +
                    "opponentPlayState=${surrenderTerminalEvidence?.opponentPlayState ?: "UNKNOWN"} " +
                    "finalGameOver=${surrenderTerminalEvidence?.finalGameOver == true} " +
                    "complete=${surrenderTerminalEvidence?.complete == true} " +
                    "playerTerminal=${authoritativeTerminal ?: "UNKNOWN"}"
            }
        }
        if (terminalCleanupCapability != null &&
            MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", terminalCleanupCapability)
        ) {
            club.xiaojiawei.hsscriptbase.config.log.info {
                "RANK_SURRENDER_TERMINAL_RECONCILED barrier=cleared " +
                    "terminalUiCleanup=pending ordinaryDispatch=false"
            }
        }
        addGameEndTask(terminalCleanupCapability)
        WarEx.reset()
        if (System.getProperty("hs.script.e2e") == "true" &&
            E2ETrace.resultRecorded &&
            System.getProperty("hs.script.e2e.pause-after-result") == "true"
        ) {
            // Pausing after a result is opt-in for a deliberately single-game
            // test. Normal E2E and normal user sessions remain continuous.
            club.xiaojiawei.hsscriptbase.config.log.info {
                "E2E单局开关已启用：结果截图已保存，暂停在结算后的状态"
            }
            PauseStatus.asyncSetPause(true)
        }
    }

    private fun readE2eOutcome(): Boolean? {
        val currentPlayerConceded = war.conceded.isNotBlank() || E2ETrace.surrenderRequested
        if (!E2ETrace.isValidScriptControlledGame() && !currentPlayerConceded) return null
        val modelOutcome = when {
            war.won.isNotBlank() -> war.won == war.me.gameId
            war.lost.isNotBlank() -> war.lost != war.me.gameId
            war.conceded.isNotBlank() ->
                if (war.me.gameId.isBlank()) false else war.conceded != war.me.gameId
            else -> null
        }
        return modelOutcome ?: if (E2ETrace.surrenderRequested) {
            false
        } else E2ETrace.readPowerLogResult(
            PowerLogListener.logFile?.path(),
            war.me.gameId,
        )
    }

    private fun readAuthoritativeTerminal(): E2ETrace.PowerLogTerminal? {
        val modelTerminal = when {
            war.won.isNotBlank() -> E2ETrace.PowerLogTerminal.WON
            war.conceded.isNotBlank() -> E2ETrace.PowerLogTerminal.CONCEDED
            war.lost.isNotBlank() -> E2ETrace.PowerLogTerminal.LOST
            else -> null
        }
        val powerLogTerminal = E2ETrace.readPowerLogTerminal(
            PowerLogListener.logFile?.path(),
            war.me.gameId,
        )
        if (powerLogTerminal != null) return powerLogTerminal

        // During the terminal callback the model can already contain the
        // opponent's LOST/CONCEDED id while the final Power.log marker is
        // still being flushed.  This can happen even after war.me has been
        // resolved.  Treating that model value as authoritative mislabels a
        // real current-player WON result as a loss/draw, so every non-local
        // terminal result must wait for the typed Power.log marker.
        if (modelTerminal != null && !WarEx.surrenderRequested &&
            !E2ETrace.surrenderRequested
        ) {
            club.xiaojiawei.hsscriptbase.config.log.info {
                "TERMINAL_RESULT_WAIT reason=power-log-terminal-unavailable modelTerminal=$modelTerminal " +
                    "ourId=${war.me.gameId.ifBlank { "<blank>" }} " +
                    "powerLogOutcome=UNKNOWN"
            }
            return null
        }

        return modelTerminal ?: if (WarEx.surrenderRequested || E2ETrace.surrenderRequested) {
            // Fast surrender can reach GAME_OVER before the player ID is
            // copied into war.me. Do not let unresolved IDs become UNKNOWN or
            // leak the previous game's result; the local surrender request is
            // a conservative loss fallback, and the structured line makes the
            // missing authoritative-ID correlation explicit.
            club.xiaojiawei.hsscriptbase.config.log.warn {
                "TERMINAL_RESULT_FALLBACK outcome=LOST source=local-surrender-request " +
                    "reason=${WarEx.surrenderReason ?: "player-id-unresolved"} " +
                    "powerLogOutcome=UNKNOWN " +
                    "ourId=${war.me.gameId.ifBlank { "<blank>" }}"
            }
            E2ETrace.PowerLogTerminal.CONCEDED
        } else null
    }
}

internal fun classifyResultOutcome(
    isWin: Boolean,
    wonId: String,
    lostId: String,
    concededId: String,
    ourId: String,
    localSurrenderRequested: Boolean,
    authoritativeTerminal: E2ETrace.PowerLogTerminal? = null,
): String = when {
    authoritativeTerminal == E2ETrace.PowerLogTerminal.CONCEDED -> "conceded"
    authoritativeTerminal == E2ETrace.PowerLogTerminal.LOST -> "loss"
    authoritativeTerminal == E2ETrace.PowerLogTerminal.WON -> "win"
    isWin -> "win"
    wonId.isNotBlank() -> "opponent-win"
    lostId.isNotBlank() && lostId == ourId -> "loss"
    concededId.isNotBlank() && concededId == ourId -> "conceded"
    localSurrenderRequested -> "conceded"
    else -> "draw-or-unknown"
}

/** Maps the current player's terminal PLAYSTATE to the boolean expected by WarEx.endWar. */
internal fun terminalToWinOverride(
    terminal: E2ETrace.PowerLogTerminal?,
): Boolean? = when (terminal) {
    E2ETrace.PowerLogTerminal.WON -> true
    E2ETrace.PowerLogTerminal.LOST,
    E2ETrace.PowerLogTerminal.CONCEDED,
    -> false
    null -> null
}

internal data class ScreenWatchdogTerminalResolution(
    val accepted: Boolean,
    val winOverride: Boolean?,
    val outcome: String,
)

/** Generic RESULT text is not enough to erase an active war; require Power.log terminal evidence. */
internal fun resolveScreenWatchdogTerminal(
    kind: ScreenWatchdogKind,
    terminal: E2ETrace.PowerLogTerminal?,
): ScreenWatchdogTerminalResolution = when (kind) {
    ScreenWatchdogKind.WIN -> ScreenWatchdogTerminalResolution(true, true, "win")
    ScreenWatchdogKind.LOST -> ScreenWatchdogTerminalResolution(true, false, "loss")
    ScreenWatchdogKind.RESULT -> when (terminal) {
        E2ETrace.PowerLogTerminal.WON -> ScreenWatchdogTerminalResolution(true, true, "win")
        E2ETrace.PowerLogTerminal.LOST -> ScreenWatchdogTerminalResolution(true, false, "loss")
        E2ETrace.PowerLogTerminal.CONCEDED -> ScreenWatchdogTerminalResolution(true, false, "conceded")
        null -> ScreenWatchdogTerminalResolution(false, null, "draw-or-unknown")
    }
    else -> ScreenWatchdogTerminalResolution(false, null, "draw-or-unknown")
}
