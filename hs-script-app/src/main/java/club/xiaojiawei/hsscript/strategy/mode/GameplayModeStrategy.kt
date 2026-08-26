package club.xiaojiawei.hsscript.strategy.mode

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.status.Mode
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.Mode.prevMode
import club.xiaojiawei.hsscript.strategy.AbstractModeStrategy
import club.xiaojiawei.hsscript.utils.GameUtil

/**
 * 游戏界面
 *
 * @author 肖嘉威
 * @date 2022/11/25 12:43
 */
object GameplayModeStrategy : AbstractModeStrategy<Any?>() {

    override fun wantEnter() {
    }

    override fun afterEnter(t: Any?) {
        if (prevMode == ModeEnum.LOGIN || prevMode == null) {
            if (!WarEx.inWar) {
                // A stale LoadingScreen scene must never be allowed to drive
                // game clicks.  The only safe action when the parser says
                // GAMEPLAY but has no active War is to stop and wait for a
                // fresh scene/log transition.
                log.error {
                    "GAMEPLAY状态无活动对局，拒绝盲目点击并暂停 " +
                        "prevMode=$prevMode currMode=${Mode.currMode} " +
                        "phase=${WarEx.war.currentPhase.name}"
                }
                PauseStatus.isPause = true
                Mode.reset()
                return
            }
            if (System.getProperty("hs.script.e2e") == "true") {
                // During an E2E watchdog restart the UI can reach GAMEPLAY
                // before Power.log has rebuilt the current War state. Do not
                // mistake that short initialization window for a broken game.
                log.info { "E2E接管：等待Power.log完成对局初始化，不提前投降" }
                if (!club.xiaojiawei.hsscript.bean.single.WarEx.inWar) {
                    GameUtil.dismissStaleGameEndScreen()
                }
            } else {
                log.info { "当前对局不完整，准备投降" }
                GameUtil.surrender()
            }
        }
    }

}
