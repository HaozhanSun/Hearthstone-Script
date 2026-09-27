package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.enums.MouseControlModeEnum
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.Point

/**
 * Keep the normal Battle.net MESSAGE-mode click sequence shared by regular
 * and safe-native runs. Queuing a raw WM_* message only proves enqueue, not
 * that Battle.net accepted the launch-button action.
 */
internal object PlatformMessageStartupDispatch {
    fun dispatch(
        upperClick: Point,
        lowerClick: Point,
        hwnd: HWND?,
        click: (Point, HWND?, Int) -> Unit,
        delay: () -> Unit,
    ) {
        click(upperClick, hwnd, MouseControlModeEnum.MESSAGE.code)
        delay()
        click(lowerClick, hwnd, MouseControlModeEnum.MESSAGE.code)
    }
}
