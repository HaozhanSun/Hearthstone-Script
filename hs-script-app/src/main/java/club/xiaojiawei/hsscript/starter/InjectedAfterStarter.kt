package club.xiaojiawei.hsscript.starter

import club.xiaojiawei.hsscript.dll.CSystemDll
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.enums.MouseControlModeEnum
import club.xiaojiawei.hsscript.utils.ConfigExUtil
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.getBoolean
import club.xiaojiawei.hsscript.utils.getInt
import club.xiaojiawei.hsscript.status.RuntimeSafety
import club.xiaojiawei.hsscriptbase.config.log


/**
 * 启动游戏
 * @author 肖嘉威
 * @date 2023/7/5 14:38
 */
class InjectedAfterStarter : AbstractStarter() {

    override fun execStart() {
        if (RuntimeSafety.safeNative || System.getProperty("hs.script.e2e.skip-inject") == "true") {
            // Do not call the legacy native hook cleanup functions here.  A
            // stale hook belongs to the previous process and those calls can
            // terminate this JVM without a Java exception.  The E2E runner
            // uses java.awt.Robot input and therefore needs no native hook.
            log.info { "安全运行：跳过全局输入/捕获 native hook，保留 Java 输入" }
            startNextStarter()
            return
        }
        if (ConfigEnum.GAME_LOG_LIMIT.getInt() == -1) {
            CSystemDll.INSTANCE.logHook(true)
        }
        if (ConfigExUtil.getMouseControlMode() === MouseControlModeEnum.MESSAGE) {
            CSystemDll.INSTANCE.mouseHook(true)
        }
        if (ConfigEnum.AUTO_REFRESH_GAME_TASK.getBoolean()) {
            CSystemDll.INSTANCE.capture(true)
        }
        if (ConfigEnum.LIMIT_MOUSE_RANGE.getBoolean()) {
            CSystemDll.INSTANCE.limitMouseRange(true)
        }
        val displayMouseTrack = ConfigEnum.DISPLAY_MOUSE_TRACK.getBoolean()
        if (displayMouseTrack || ConfigEnum.DISPLAY_GAME_RECT_POS.getBoolean()) {
            CSystemDll.INSTANCE.presentDraw(true)
            if (displayMouseTrack){
                CSystemDll.INSTANCE.showMouseTrack(true)
            }
        }
//        if (ConfigEnum.GAME_WINDOW_REDUCTION_FACTOR.service?.getStatus(null) == true) {
//            CSystemDll.INSTANCE.resizeGameWindow(true)
//        }
        startNextStarter()
    }

}
