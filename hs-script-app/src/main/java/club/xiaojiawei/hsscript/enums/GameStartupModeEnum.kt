package club.xiaojiawei.hsscript.enums

import club.xiaojiawei.hsscript.consts.GAME_CN_NAME
import club.xiaojiawei.hsscript.consts.GAME_PROGRAM_NAME
import club.xiaojiawei.hsscript.consts.PLATFORM_CN_NAME
import club.xiaojiawei.hsscript.utils.CMDUtil
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.MouseUtil
import club.xiaojiawei.hsscript.utils.PlatformMessageStartupDispatch
import club.xiaojiawei.hsscript.utils.getString
import club.xiaojiawei.hsscriptbase.config.log
import com.sun.jna.platform.win32.WinDef
import org.jetbrains.kotlin.utils.addToStdlib.ifTrue
import kotlin.io.path.Path
import kotlin.io.path.exists

/**
 * @author 肖嘉威
 * @date 2025/10/7 15:48
 */
enum class GameStartupModeEnum(val comment: String, val introduction: String, val exec: () -> Unit) {

//    GAME_ARG(
//        GAME_CN_NAME,
//        "通过向${GAME_CN_NAME}传递参数的方式启动",
//        {
//            Path(ConfigEnum.GAME_PATH.getString(), GAME_PROGRAM_NAME).let {
//                it.exists().ifTrue {
//                    CMDUtil.directExec(it.toString(), "-launch", "-uid", "hs_beta")
//                }
//            }
//        }),

    PLATFORM_ARG(
        "${PLATFORM_CN_NAME}参数",
        "通过向${PLATFORM_CN_NAME}传递参数的方式启动",
        {
            GameUtil.launchPlatformAndGame()
        }),

    PLATFORM_MESSAGE(
        "${PLATFORM_CN_NAME}消息",
        "通过模拟鼠标消息点击${PLATFORM_CN_NAME}窗口里的进入游戏按钮的方式启动",
        startup@{
            val platformHWND = GameUtil.findPlatformHWND()
            val clientRect = WinDef.RECT()
            if (platformHWND == null ||
                !com.sun.jna.platform.win32.User32.INSTANCE.GetClientRect(platformHWND, clientRect)
            ) {
                log.warn { "PLATFORM_MESSAGE_STARTUP_BLOCKED reason=launcher-client-rect-unavailable hwnd=$platformHWND" }
                return@startup
            }
            val width = clientRect.right - clientRect.left
            val height = clientRect.bottom - clientRect.top
            val point = PlatformMessageStartupDispatch.startButtonPoint(width, height)
            if (point == null) {
                log.warn { "PLATFORM_MESSAGE_STARTUP_BLOCKED reason=launcher-client-size-invalid client=${width}x$height" }
                return@startup
            }
            val queued = PlatformMessageStartupDispatch.dispatch(
                point = point,
                hwnd = platformHWND,
                click = MouseUtil::postStartupWindowMessageClick,
            )
            log.info {
                "PLATFORM_MESSAGE_STARTUP_DISPATCH transport=client-window-message hwnd=$platformHWND " +
                    "client=${width}x$height point=(${point.x},${point.y}) messagesQueued=$queued " +
                    "acceptance=awaiting-game-process-window"
            }
        }),

    ;


}
