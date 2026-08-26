package club.xiaojiawei.hsscript.listener.log

import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.consts.GAME_WAR_LOG_NAME
import club.xiaojiawei.hsscript.core.Core
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.strategy.AbstractPhaseStrategy
import club.xiaojiawei.hsscript.strategy.DeckStrategyActuator
import club.xiaojiawei.hsscript.strategy.phase.ReplaceCardPhaseStrategy
import club.xiaojiawei.hsscript.utils.PowerLogUtil
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.StepEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscriptcardsdk.status.WAR
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

    private const val RESERVE_SIZE_B = 4 * 1024 * 1024

    override fun dealOldLog() {
        WarEx.reset()
        PowerLogUtil.resetPendingTagChanges()
        terminalTailFence = false

        logFile?.let {
            if (System.getProperty("hs.script.e2e") == "true") {
                // A watchdog restart must reconstruct the current game before
                // consuming new lines; otherwise the phase machine starts at
                // DRAWN_INIT_CARD with no player mapping and never reaches
                // the live turn handler. The actuator guards below make this
                // replay state-only and prevent duplicate UI clicks.
                replayingExistingLog = true
                try {
                    log.info { "E2E恢复：从Power.log开头回放当前对局状态" }
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
            log.warn {
                "PHASE_ANOMALY game-over callback left phase=GAME_OVER " +
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
