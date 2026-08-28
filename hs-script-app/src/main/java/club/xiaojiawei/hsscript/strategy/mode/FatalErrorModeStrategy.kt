package club.xiaojiawei.hsscript.strategy.mode

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscript.core.Core
import club.xiaojiawei.hsscript.status.Mode
import club.xiaojiawei.hsscript.status.UnknownStateScreenshot
import club.xiaojiawei.hsscript.strategy.AbstractModeStrategy

/**
 * 致命错误
 * @author 肖嘉威
 * @date 2022/12/10 22:35
 */
object FatalErrorModeStrategy : AbstractModeStrategy<Any?>() {

    override fun wantEnter() {
    }

    override fun afterEnter(t: Any?) {
        // Capture before Core.restart() tears down the client.  Fatal error is
        // a known mode, so it cannot rely on ScreenStateRecovery's unknown
        // screen hook.  The capture is read-only and includes the exact mode
        // and transition context in the log next to the file link.
        val evidence = UnknownStateScreenshot.capture(
            category = UnknownStateScreenshot.CATEGORY_FATAL_ERROR,
            trigger = "fatal-error-mode-entry",
            state = "mode=${Mode.currMode?.name ?: "FATAL_ERROR"}",
            phase = "fatal-error-restart",
            label = "fatal-error-before-restart",
        )
        log.warn {
            "FATAL_ERROR_SCREENSHOT " +
                "path=${evidence?.file?.absolutePath ?: "not-saved"} " +
                "link=${evidence?.link ?: "none"}"
        }
        log.info{"发生致命错误，准备重启游戏"}
        Core.restart()
    }
}
