package club.xiaojiawei.hsscript.utils

import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.Point

/**
 * Keep the normal Battle.net MESSAGE-mode click sequence shared by regular
 * and safe-native runs. Queuing a raw WM_* message only proves enqueue, not
 * that Battle.net accepted the launch-button action.
 */
internal object PlatformMessageStartupDispatch {
    /** Battle.net Home CTA point, expressed in the selected client window's coordinates. */
    fun startButtonPoint(clientWidth: Int, clientHeight: Int): Point? {
        if (clientWidth < PlatformWindowDiscoveryPolicy.MIN_CLIENT_WIDTH ||
            clientHeight < PlatformWindowDiscoveryPolicy.MIN_CLIENT_HEIGHT
        ) return null
        return Point((clientWidth * 0.11).toInt(), (clientHeight * 0.892).toInt())
    }

    /** Returns whether targeted messages were queued, not whether Battle.net accepted the action. */
    fun dispatch(
        point: Point,
        hwnd: HWND?,
        click: (Point, HWND?) -> Boolean,
    ): Boolean = click(point, hwnd)
}
