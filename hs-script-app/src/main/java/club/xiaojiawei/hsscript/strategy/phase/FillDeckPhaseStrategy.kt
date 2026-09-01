package club.xiaojiawei.hsscript.strategy.phase

import club.xiaojiawei.hsscriptbase.bean.LThread
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscript.bean.log.TagChangeEntity
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.enums.TagEnum
import club.xiaojiawei.hsscript.enums.WindowEnum
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import club.xiaojiawei.hsscript.status.surrender.SurrenderPolicy
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.strategy.AbstractPhaseStrategy
import club.xiaojiawei.hsscript.strategy.phase.ReplaceCardPhaseStrategy.resetForNewGame
import club.xiaojiawei.hsscript.strategy.DeckStrategyActuator
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.WindowUtil
import club.xiaojiawei.hsscript.utils.runUI

/**
 * 起始填充牌库阶段
 * @author 肖嘉威
 * @date 2022/11/27 13:35
 */
object FillDeckPhaseStrategy : AbstractPhaseStrategy() {

    override fun dealTagChangeThenIsOver(line: String, tagChangeEntity: TagChangeEntity): Boolean {
        if (tagChangeEntity.tag == TagEnum.TURN && tagChangeEntity.value == "1") {
            resetForNewGame()
            SurrenderPolicy.resetForNewGame()
            war.currentPhase = WarPhaseEnum.DRAWN_INIT_CARD
            return true
        }
        return false
    }

    override fun dealOtherThenIsOver(line: String): Boolean {
        if (line.contains("CREATE_GAME")) {
            SurrenderPolicy.resetForNewGame()
            if (ConfigUtil.getBoolean(ConfigEnum.AUTO_OPEN_GAME_ANALYSIS)) {
                runUI {
                    WindowUtil.showStage(WindowEnum.GAME_DATA_ANALYSIS)
                }
            }
            if (PowerLogListener.replayingExistingLog) {
                // A watchdog restart replays every historical CREATE_GAME
                // record.  Do not advance the user's play-one/surrender-one
                // counter while rebuilding old games; otherwise the current
                // live game can accidentally start with the surrender
                // strategy and click the menu on the matchmaking screen.
                club.xiaojiawei.hsscriptbase.config.log.info {
                    "E2E恢复回放：跳过历史对局的策略轮换"
                }
            } else {
                DeckStrategyManager.beginGame()
            }
            GameOverPhaseStrategy.resetForNewGame()
            WarEx.startWar(DeckStrategyManager.currentDeckStrategy?.runModes?.get(0))
            (LThread({
                DeckStrategyActuator.reset()
            }, "Reset Deck Strategy Thread").also { addTask(it) }).start()

        }
        return super.dealOtherThenIsOver(line)
    }

}
