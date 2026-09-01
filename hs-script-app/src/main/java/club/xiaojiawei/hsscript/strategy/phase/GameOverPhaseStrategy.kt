package club.xiaojiawei.hsscript.strategy.phase

import club.xiaojiawei.hsscript.bean.log.Block
import club.xiaojiawei.hsscript.bean.log.ExtraEntity
import club.xiaojiawei.hsscript.bean.log.TagChangeEntity
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.E2ETrace
import club.xiaojiawei.hsscript.strategy.AbstractPhaseStrategy
import club.xiaojiawei.hsscript.utils.GameUtil.addGameEndTask
import club.xiaojiawei.hsscript.utils.GameResultScreenshot
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscriptbase.util.RandomUtil
import java.io.IOException
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
            recordDuplicateCallback()
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
        val authoritativeOutcome = readAuthoritativeOutcome()
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

        WarEx.endWar(authoritativeOutcome)

        val resultOutcome = if (resultScreenshotCaptured.compareAndSet(false, true)) {
            // In E2E mode a terminal PLAYSTATE is not enough to call the
            // result a successful bot game. If the script milestones were
            // missing, keep the evidence explicitly non-winning even when
            // stale WarEx state still says win after a fast disconnect.
            if (e2eEnabled && !scriptControlledGame) {
                "draw-or-unknown"
            } else {
                when {
                    WarEx.isWin -> "win"
                    war.won.isNotBlank() -> "opponent-win"
                    war.lost == war.me.gameId -> "loss"
                    war.conceded == war.me.gameId -> "conceded"
                    else -> "draw-or-unknown"
                }
            }
        } else null

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
                        "missing script milestones: ${E2ETrace.milestoneSummary()}"
                }
            }
        }
        try {
            val screenshotDelayMs = RandomUtil.getActionInterval(RESULT_SCREENSHOT_DELAY_BASE_MS.toInt())
            club.xiaojiawei.hsscriptbase.config.log.info {
                "GAME_RESULT_SCREENSHOT_WAIT delayMs=$screenshotDelayMs outcome=$resultOutcome game=${WarEx.warCount}"
            }
            SystemUtil.delay(screenshotDelayMs)
            // Hearthstone publishes the authoritative PLAYSTATE before the
            // result animation is fully painted. Capture after this short
            // natural transition, but before addGameEndTask's first cleanup
            // click, so the file is an actual result-page snapshot whenever
            // the client exposes one.
            resultOutcome?.let { GameResultScreenshot.capture(it, WarEx.warCount) }
            val accessFile = PowerLogListener.logFile
            accessFile?.seek(accessFile.length())
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
        addGameEndTask()
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

    private fun readAuthoritativeOutcome(): Boolean? {
        val modelOutcome = when {
            war.won.isNotBlank() -> war.won == war.me.gameId
            war.lost.isNotBlank() -> war.lost != war.me.gameId
            war.conceded.isNotBlank() ->
                if (war.me.gameId.isBlank()) false else war.conceded != war.me.gameId
            else -> null
        }
        return modelOutcome ?: E2ETrace.readPowerLogResult(
            PowerLogListener.logFile?.path(),
            war.me.gameId,
        )
    }
}
