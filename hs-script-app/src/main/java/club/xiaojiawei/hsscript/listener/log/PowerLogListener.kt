package club.xiaojiawei.hsscript.listener.log

import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.consts.GAME_WAR_LOG_NAME
import club.xiaojiawei.hsscript.core.Core
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.status.E2EReadinessGate
import club.xiaojiawei.hsscript.status.E2ETrace
import club.xiaojiawei.hsscript.strategy.AbstractPhaseStrategy
import club.xiaojiawei.hsscript.strategy.DeckStrategyActuator
import club.xiaojiawei.hsscript.strategy.phase.ReplaceCardPhaseStrategy
import club.xiaojiawei.hsscript.utils.PowerLogUtil
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.StepEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 对局日志监听器
 * @author 肖嘉威
 * @date 2023/7/5 20:40
 */
object PowerLogListener :
    AbstractLogListener(GAME_WAR_LOG_NAME, 0, 50L, TimeUnit.MILLISECONDS) {

    private val war = WAR

    /** True while an E2E watchdog restart is rebuilding the live game model. */
    @Volatile
    var replayingExistingLog: Boolean = false

    /**
     * A Power.log result block contains a tail of repeated state/event lines
     * after FINAL_GAMEOVER.  WarEx.reset() intentionally returns the model to
     * FILL_DECK, so without a fence those stale lines are interpreted as the
     * beginning of a new game and can repeatedly announce phase transitions.
     * Only a real CREATE_GAME line is allowed to open the next game.
     */
    @Volatile
    private var terminalTailFence = false

    private val e2eReadinessGate = E2EReadinessGate()

    @Volatile
    private var lastReadinessLogAt = 0L

    private const val RESERVE_SIZE_B = 4 * 1024 * 1024

    override fun dealOldLog() {
        WarEx.reset()
        PowerLogUtil.resetPendingTagChanges()
        terminalTailFence = false

        logFile?.let {
            if (System.getProperty("hs.script.e2e") == "true") {
                val baseline = it.length()
                e2eReadinessGate.begin(
                    runId = System.getProperty("hs.script.e2e.run-id", "unknown"),
                    processId = ProcessHandle.current().pid(),
                    logPath = it.path(),
                    baselineOffset = baseline,
                    nowMs = System.currentTimeMillis(),
                )
                it.seek(baseline)
                log.warn {
                    "E2E_READINESS_SESSION_STARTED runId=${System.getProperty("hs.script.e2e.run-id", "unknown")} " +
                        "pid=${ProcessHandle.current().pid()} path=${it.path()} baselineOffset=$baseline " +
                        "existingLog=${baseline > 0} old-tail-replay=false"
                }
                return
            }
            val replayExistingGame = hasUnfinishedGame(it.path())
            if (replayExistingGame) {
                // A restart or late attach must reconstruct an already active
                // game before consuming new lines; otherwise the phase machine
                // starts at FILL_DECK with no player mapping and the bot can
                // observe the board without reaching the live turn handler.
                // Replay guards make this state-only and prevent historical UI
                // clicks.
                replayingExistingLog = true
                try {
                    log.info {
                        "Power.log恢复：从开头回放当前未结束对局 " +
                            "reason=${if (replayExistingGame) "active-game-detected" else "e2e-watchdog"}"
                    }
                    it.seek(0)
                    dealNewLog()
                } finally {
                    replayingExistingLog = false
                    if (war.currentPhase == WarPhaseEnum.REPLACE_CARD) {
                        ReplaceCardPhaseStrategy.resumeAfterExistingLogReplay()
                    } else {
                        ReplaceCardPhaseStrategy.discardAfterExistingLogReplay()
                    }
                    DeckStrategyActuator.resumeAfterExistingLogReplay()
                    log.info {
                        "E2E恢复完成：当前阶段=${war.currentPhase.comment}，当前步骤=${war.currentTurnStep}"
                    }
                }
            } else {
                it.seek(it.length())
            }
        }
    }

    /**
     * Detect whether the newest CREATE_GAME block is still live.  Starting a
     * listener after Hearthstone has already entered a match otherwise seeks
     * directly to EOF and loses the in-memory card model.  A completed match
     * is fenced by its authoritative WON/LOST PLAYSTATE, so result/home/deck
     * screens do not trigger an unsafe historical replay.
     */
    private fun hasUnfinishedGame(path: String): Boolean = runCatching {
        val active = File(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
            hasUnfinishedGame(lines)
        }
        log.info {
            "POWER_LOG_ATTACH_PROBE path=$path replayExistingGame=$active"
        }
        active
    }.getOrElse { error ->
        log.warn(error) { "POWER_LOG_ATTACH_PROBE_FAILED path=$path" }
        false
    }

    internal fun hasUnfinishedGame(lines: Sequence<String>): Boolean {
        var sawCreateGame = false
        var terminal = false
        lines.forEach { line ->
            when {
                line.contains("CREATE_GAME") -> {
                    sawCreateGame = true
                    terminal = false
                }
                sawCreateGame && (
                    line.contains("tag=PLAYSTATE value=WON") ||
                        line.contains("tag=PLAYSTATE value=LOST")
                    ) -> terminal = true
            }
        }
        return sawCreateGame && !terminal
    }

    /**
     * Power.log has one shared cursor and WAR is one shared model. During
     * replay/restart a scheduled callback can overlap with the replay pass;
     * serialize the whole cursor/phase pass so a reset cannot be overwritten
     * by a stale result callback.
     */
    @Synchronized
    override fun dealNewLog() {
        // The normal listener intentionally yields while a phase handler is
        // working. During a deliberate non-E2E replay, the replay flag allows
        // the cursor to read through EOF; the live scheduled listener keeps
        // the normal gate. E2E runs never enter this replay path: they seek
        // to the session baseline in dealOldLog and wait for a fresh
        // CREATE_GAME transition instead.
        while (!PauseStatus.isPause && WorkTimeListener.working &&
            (replayingExistingLog || !AbstractPhaseStrategy.dealing)
        ) {
            logFile?.let {
                val line = it.readLine()
                if (line == null) {
                    return@dealNewLog
                } else if (System.getProperty("hs.script.e2e") == "true") {
                    val ready = e2eReadinessGate.observeLine(
                        runId = System.getProperty("hs.script.e2e.run-id", "unknown"),
                        processId = ProcessHandle.current().pid(),
                        absolutePosition = it.getPosition(),
                        line = line,
                    )
                    if (ready) {
                        log.info {
                            "E2E_READINESS_READY runId=${System.getProperty("hs.script.e2e.run-id", "unknown")} " +
                                "pid=${ProcessHandle.current().pid()} source=${it.path()} transition=CREATE_GAME"
                        }
                    }
                    if (PowerLogUtil.isRelevance(line)) {
                        resolveLog(line)
                    }
                } else if (PowerLogUtil.isRelevance(line)) {
                    resolveLog(line)
                }
            } ?: return
        }
    }

    @Synchronized
    private fun resolveLog(line: String) {
        val startsNewGame = line.contains("CREATE_GAME")
        if (terminalTailFence && !startsNewGame) {
            if (log.isDebugEnabled()) {
                log.debug { "忽略结算尾部事件，等待CREATE_GAME: $line" }
            }
            return
        }
        if (startsNewGame) {
            if (System.getProperty("hs.script.e2e") == "true") {
                // CREATE_GAME is the authoritative per-game boundary. A fast
                // pre-mulligan surrender may never emit TURN=1, so resetting
                // only from FillDeckPhaseStrategy would leave prior-game
                // milestones attached to the new game.
                E2ETrace.beginNewGame("CREATE_GAME")
            }
            if (terminalTailFence) {
                log.info { "检测到新的CREATE_GAME，打开下一局日志输入" }
            }
            // A new CREATE_GAME can arrive while the previous result screen
            // is still the active phase in the parser.  In that case the
            // subsequent BEGIN_MULLIGAN/TURN lines would otherwise continue
            // through GameOverPhaseStrategy and the UI can visibly enter a
            // new game while the script remains in FILL_DECK/inWar=false.
            // Reset the in-memory model at the boundary before dispatching
            // the first line of the next game.  This is intentionally not a
            // statistics reset: WarEx.reset() preserves the completed-game
            // counters while clearing only the current game model.
            if (war.currentPhase == WarPhaseEnum.GAME_OVER) {
                log.info {
                    "CREATE_GAME边界重置：上一局结算已结束，开始接管下一局"
                }
                WarEx.reset(print = false)
                ReplaceCardPhaseStrategy.resetForNewGame()
            }
            terminalTailFence = false
        }

        val phaseBefore = war.currentPhase
        val stepBefore = war.currentTurnStep
        when (war.currentPhase) {
            WarPhaseEnum.FILL_DECK -> {
                WarPhaseEnum.FILL_DECK.phaseStrategy?.deal(line)
            }

            WarPhaseEnum.GAME_OVER -> {
                WarPhaseEnum.GAME_OVER.phaseStrategy?.deal(line)
            }

            else -> war.currentPhase.phaseStrategy?.deal(line)
        }
        // A GAME_OVER handler may have reset the war while consuming this
        // line. Never re-apply the stale FINAL_GAMEOVER value after reset.
        if (war.currentTurnStep == StepEnum.FINAL_GAMEOVER &&
            war.currentPhase != WarPhaseEnum.FILL_DECK
        ) {
            war.currentPhase = WarPhaseEnum.GAME_OVER
        }
        if (phaseBefore == WarPhaseEnum.GAME_OVER &&
            stepBefore == StepEnum.FINAL_GAMEOVER &&
            war.currentPhase == WarPhaseEnum.GAME_OVER
        ) {
            // The result block contains repeated callbacks after the
            // idempotent GAME_OVER handler has already completed.  This is
            // expected tail traffic, not a phase transition failure.
            log.debug {
                "GAME_OVER_TAIL_CALLBACK_IGNORED " +
                    "phase=GAME_OVER " +
                    "step=${war.currentTurnStep} inWar=${WarEx.inWar}"
            }
        }

        // Set the fence only after the GAME_OVER strategy has consumed the
        // terminal block and reset the in-memory war.  FINAL_GAMEOVER is
        // announced before the following Power.log lines that contain the
        // authoritative PLAYSTATE=LOST/WON.  Fencing as soon as that step is
        // observed drops those lines, leaving the app stuck on the result
        // screen with no result handler or continue click.
        if (phaseBefore == WarPhaseEnum.GAME_OVER &&
            war.currentPhase != WarPhaseEnum.GAME_OVER
        ) {
            terminalTailFence = true
            log.info { "GAME_OVER_TAIL_FENCE enabled; awaiting next CREATE_GAME" }
        }
    }

    fun checkPowerLogSize(): Boolean {
        if (!allowE2EDispatch("power-log-size-check")) return false
        val logFile = logFile
        logFile ?: return false

        if (ScriptStatus.maxLogSizeB > 0 && logFile.length() + RESERVE_SIZE_B >= ScriptStatus.maxLogSizeB) {
            log.info { "${GAME_WAR_LOG_NAME}即将达到" + (ScriptStatus.maxLogSizeKB) + "KB，准备重启游戏" }
            Core.restart()
            return false
        }
        return true
    }

    /**
     * These are the bounded pre-game inputs needed to cause the first
     * CREATE_GAME transition.  Requiring CREATE_GAME before these actions
     * would deadlock a fresh E2E run on the hub forever.  Once the bounded
     * readiness wait expires, even these inputs are fail-closed.
     */
    private val preGameDispatchContexts = setOf(
        "hub-after-enter",
        "hub-popup-dismiss",
        "tournament-entry",
        "power-log-size-check",
        "start-matching",
    )

    internal fun isPreGameDispatchContext(context: String): Boolean =
        context in preGameDispatchContexts

    /**
     * E2E-only hard gate for normal in-game dispatch.  Normal user runs
     * retain the upstream behavior; supervised E2E runs must prove a fresh
     * CREATE_GAME transition before gameplay actions are allowed.  The
     * small pre-game allowlist above is limited to the waiting state so it
     * can start that authoritative transition without weakening the
     * post-timeout fail-closed behavior.
     */
    fun allowE2EDispatch(context: String): Boolean {
        if (System.getProperty("hs.script.e2e") != "true") return true

        val now = System.currentTimeMillis()
        val decision = e2eReadinessGate.evaluate(
            runId = System.getProperty("hs.script.e2e.run-id", "unknown"),
            processId = ProcessHandle.current().pid(),
            nowMs = now,
        )
        if (decision.state == E2EReadinessGate.State.READY) return true

        if (decision.state == E2EReadinessGate.State.WAITING_FOR_CREATE_GAME &&
            isPreGameDispatchContext(context)
        ) {
            return true
        }

        if (decision.state == E2EReadinessGate.State.BLOCKED) {
            if (!PauseStatus.isPause) {
                log.error {
                    "E2E_READINESS_FAIL_CLOSED context=$context reason=${decision.reason} " +
                        "waitedMs=${decision.waitedMs} pause=true"
                }
                PauseStatus.isPause = true
            }
        } else if (now - lastReadinessLogAt >= 10_000L) {
            lastReadinessLogAt = now
            log.warn {
                "E2E_READINESS_BLOCKED context=$context reason=${decision.reason} " +
                    "waitedMs=${decision.waitedMs} action=none"
            }
        }
        return false
    }

    /** Called by the lifecycle monitor so the bounded wait cannot run forever. */
    fun enforceE2EReadiness() {
        allowE2EDispatch("lifecycle-readiness")
    }

}
