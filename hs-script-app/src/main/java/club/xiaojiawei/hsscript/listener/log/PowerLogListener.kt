package club.xiaojiawei.hsscript.listener.log

import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.consts.GAME_WAR_LOG_NAME
import club.xiaojiawei.hsscript.core.Core
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.RuntimeFaultBackoff
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.status.surrender.CurrentGamePowerLogTerminalTracker
import club.xiaojiawei.hsscript.strategy.AbstractPhaseStrategy
import club.xiaojiawei.hsscript.strategy.DeckStrategyActuator
import club.xiaojiawei.hsscript.strategy.phase.ReplaceCardPhaseStrategy
import club.xiaojiawei.hsscript.utils.PowerLogUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.enums.GameLogModeEnum
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.StepEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 对局日志监听器
 * @author 肖嘉威
 * @date 2023/7/5 20:40
 */
object PowerLogListener :
    AbstractLogListener(GAME_WAR_LOG_NAME, 0, 50L, TimeUnit.MILLISECONDS) {

    private val war = WAR

    /** Bounds transient parser faults so one malformed Power.log line cannot kill the listener. */
    private val resolveFaultBackoff = RuntimeFaultBackoff()

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
    private val currentGamePowerLogTerminalTracker = CurrentGamePowerLogTerminalTracker()

    /** True only when this CREATE_GAME has terminal PLAYSTATE and complete-game markers. */
    fun hasCurrentGameCompleteTerminalPowerLogEvidence(): Boolean =
        currentGamePowerLogTerminalTracker.hasCompleteTerminalEvidence()

    private const val RESERVE_SIZE_B = 4 * 1024 * 1024
    private const val ACTIVE_GAME_SCAN_CHUNK_B = 4 * 1024 * 1024
    private const val ACTIVE_GAME_SCAN_MAX_B = 128 * 1024 * 1024

    /**
     * Retry a late disk-log attach when startup crossed the listener's initial
     * wait window. The caller must first verify that this is the current game
     * session's readable, non-empty Power.log; replay remains state-only.
     */
    @Synchronized
    fun bindCurrentSessionIfAvailable(): Boolean {
        if (ScriptStatus.gameLogMode != GameLogModeEnum.DISK) return false
        val latestPowerLog = GameUtil.getLatestLogDir()
            ?.resolve(GAME_WAR_LOG_NAME)
            ?.takeIf { it.isFile && it.canRead() && it.length() > 0L }
            ?: return false
        val currentPath = logFile?.path()?.let { File(it).absoluteFile.normalize().path }
        val latestPath = latestPowerLog.absoluteFile.normalize().path
        if (currentPath == latestPath) return true
        if (currentPath != null) {
            // The already-running listener rotates itself on its next poll;
            // do not restart/replay it concurrently from the watchdog thread.
            return false
        }

        log.warn {
            "POWER_LOG_LATE_BIND_REQUEST current=${currentPath ?: "none"} " +
                "latest=$latestPath reason=current-session-file-verified"
        }
        // listen() applies the same unfinished-game replay semantics as a
        // normal startup attach and rotates an already-running listener safely.
        listen()
        return logFile?.path()?.let { File(it).absoluteFile.normalize().path } == latestPath
    }

    override fun dealOldLog() {
        WarEx.reset()
        PowerLogUtil.resetPendingTagChanges()
        terminalTailFence = false
        currentGamePowerLogTerminalTracker.reset()

        logFile?.let {
            val unfinishedGameStart = unfinishedGameStartOffset(it.path())
            val replayExistingGame = unfinishedGameStart != null
            log.info {
                "POWER_LOG_ATTACH_PROBE path=${it.path()} " +
                    "replayExistingGame=$replayExistingGame " +
                    "unfinishedGameStart=${unfinishedGameStart ?: "none"}"
            }
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
                        "Power.log恢复：从未结束对局起点回放 " +
                            "offset=${unfinishedGameStart ?: 0} reason=active-game-detected"
                    }
                    // Power.log is append-only across multiple games. Replaying
                    // from byte zero makes startup latency proportional to the
                    // entire historical log and suppresses live clicks for the
                    // whole replay. Only the newest unfinished CREATE_GAME block
                    // is needed to rebuild the current WAR model.
                    it.seek(unfinishedGameStart ?: 0L)
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
                log.info {
                    "POWER_LOG_REPLAY_SKIPPED path=${it.path()} replayExistingGame=false " +
                        "reason=terminal-or-no-create e2e=${System.getProperty("hs.script.e2e") == "true"}"
                }
                it.seek(it.length())
            }
        }
    }

    /**
     * Return the byte offset of the newest unfinished game, or null when the
     * log ends after a completed game. The offset lets the live listener skip
     * all completed historical matches while retaining the existing state
     * reconstruction behavior for a game that is already on screen.
     */
    internal fun unfinishedGameStartOffset(path: String): Long? = runCatching {
        RandomAccessFile(File(path), "r").use { file ->
            val fileLength = file.length()
            var scanEnd = fileLength
            var scannedBytes = 0L
            var newestCreateGame: Long? = null
            var newestTerminalPlayState: Long? = null

            // Power.log is append-only and the newest game is always near EOF.
            // Scanning the whole historical file here can hold the listener for
            // tens of seconds, which is long enough to miss the mulligan window.
            // Walk backwards in bounded chunks and stop as soon as the newest
            // CREATE_GAME marker is found.
            while (scanEnd > 0L && scannedBytes < ACTIVE_GAME_SCAN_MAX_B) {
                val chunkStart = (scanEnd - ACTIVE_GAME_SCAN_CHUNK_B).coerceAtLeast(0L)
                file.seek(chunkStart)
                if (chunkStart > 0L) file.readLine() // discard a partial line
                while (file.filePointer < scanEnd) {
                    val lineStart = file.filePointer
                    val line = file.readLine() ?: break
                    if (line.contains("CREATE_GAME")) newestCreateGame = lineStart
                    if (line.contains("tag=PLAYSTATE value=WON") ||
                        line.contains("tag=PLAYSTATE value=LOST")
                    ) {
                        newestTerminalPlayState = lineStart
                    }
                }
                if (newestCreateGame != null && newestTerminalPlayState != null) break
                scannedBytes += scanEnd - chunkStart
                scanEnd = chunkStart
            }

            val gameStart = newestCreateGame ?: return@runCatching null
            // Because both markers were found while walking backwards, each
            // is the newest occurrence in the file. A terminal marker after
            // the newest CREATE_GAME means the latest game is complete; a
            // marker before it belongs to the previous game and the latest
            // game is still active.
            gameStart.takeUnless { start ->
                newestTerminalPlayState?.let { terminal -> terminal > start } == true
            }
        }
    }.getOrElse { error ->
        log.warn(error) { "POWER_LOG_ACTIVE_GAME_OFFSET_FAILED path=$path" }
        null
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
        // During an E2E watchdog restart this method is also used to rebuild
        // the in-memory model from the beginning of Power.log.  The normal
        // listener intentionally yields while a phase handler is working, but
        // applying that gate during replay stops at the first historical turn
        // and later replays old GAME_OVER/matchmaking lines as if they were
        // live input.  That creates duplicate queues, false results, and can
        // make the app appear to close or restart by itself.  Replay must read
        // through EOF; the live scheduled listener keeps the normal gate.
        while (!PauseStatus.isPause && WorkTimeListener.working &&
            (replayingExistingLog || !AbstractPhaseStrategy.dealing)
        ) {
            logFile?.let {
                val line = it.readLine()
                if (line == null) {
                    return@dealNewLog
                }
                // Terminal markers are safety evidence, not only model input.
                // Some current-client GameState.DebugPrintPower lines (notably
                // STATE=COMPLETE) are intentionally excluded by isRelevance;
                // observe every raw line before that parser filter so a
                // completed current match can release result cleanup safely.
                currentGamePowerLogTerminalTracker.observeLine(
                    line,
                    liveAttachedSession = !replayingExistingLog,
                )
                if (PowerLogUtil.isRelevance(line)) {
                    try {
                        resolveLog(line)
                        resolveFaultBackoff.onSuccess()
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        log.info { "POWER_LOG_RESOLVE_INTERRUPTED" }
                        return@dealNewLog
                    } catch (fault: Throwable) {
                        val decision = resolveFaultBackoff.onFailure()
                        log.error(fault) {
                            "POWER_LOG_RESOLVE_FAILED " +
                                "failureCount=${decision.failureCount} " +
                                "retryAfterMs=${decision.delayMs} " +
                                "circuitOpened=${decision.circuitOpened} " +
                                "phase=${war.currentPhase.name} " +
                                "step=${war.currentTurnStep?.name ?: "NONE"} " +
                                "line=${line.take(240)}"
                        }
                        if (decision.circuitOpened) {
                            log.warn {
                                "POWER_LOG_RESOLVE_CIRCUIT_OPEN " +
                                    "retryAfterMs=${decision.delayMs} " +
                                    "failureCount=${decision.failureCount} " +
                                    "action=skip-faulty-line-and-yield"
                            }
                        }
                        try {
                            SystemUtil.delay(decision.delayMs.toInt())
                        } catch (backoffInterrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            log.info { "POWER_LOG_RESOLVE_BACKOFF_INTERRUPTED" }
                            return@dealNewLog
                        }
                    }
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
        val logFile = logFile
        logFile ?: return false

        if (ScriptStatus.maxLogSizeB > 0 && logFile.length() + RESERVE_SIZE_B >= ScriptStatus.maxLogSizeB) {
            log.info { "${GAME_WAR_LOG_NAME}即将达到" + (ScriptStatus.maxLogSizeKB) + "KB，准备重启游戏" }
            Core.restart()
            return false
        }
        return true
    }

}
