package club.xiaojiawei.hsscript.utils

import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM

internal fun interface StartupWindowMessagePoster {
    fun post(hwnd: HWND, message: Int, wParam: WPARAM, lParam: LPARAM): Boolean
}

internal data class StartupWindowMessageDispatchResult(
    val mouseMovePosted: Boolean,
    val mouseDownPosted: Boolean,
    val mouseUpPosted: Boolean,
    val nativeBindingFailure: Boolean = false,
) {
    val allMessagesPosted: Boolean
        get() = mouseMovePosted && mouseDownPosted && mouseUpPosted && !nativeBindingFailure
}
internal object StartupWindowMessageDispatch {
    private const val WM_MOUSEMOVE = 0x0200
    private const val WM_LBUTTONDOWN = 0x0201
    private const val WM_LBUTTONUP = 0x0202

    fun click(hwnd: HWND, lParam: LPARAM, poster: StartupWindowMessagePoster): StartupWindowMessageDispatchResult {
        val move = postOrFailClosed(poster, hwnd, WM_MOUSEMOVE, WPARAM(0L), lParam)
        if (move.nativeBindingFailure) return move
        val down = postOrFailClosed(poster, hwnd, WM_LBUTTONDOWN, WPARAM(1L), lParam)
        if (down.nativeBindingFailure) return move.combine(down)
        val up = postOrFailClosed(poster, hwnd, WM_LBUTTONUP, WPARAM(0L), lParam)
        return move.combine(down).combine(up)
    }

    private fun postOrFailClosed(
        poster: StartupWindowMessagePoster,
        hwnd: HWND,
        message: Int,
        wParam: WPARAM,
        lParam: LPARAM,
    ): StartupWindowMessageDispatchResult = try {
        val accepted = poster.post(hwnd, message, wParam, lParam)
        when (message) {
            WM_MOUSEMOVE -> StartupWindowMessageDispatchResult(accepted, false, false)
            WM_LBUTTONDOWN -> StartupWindowMessageDispatchResult(false, accepted, false)
            else -> StartupWindowMessageDispatchResult(false, false, accepted)
        }
    } catch (_: UnsatisfiedLinkError) {
        StartupWindowMessageDispatchResult(false, false, false, nativeBindingFailure = true)
    }

    private fun StartupWindowMessageDispatchResult.combine(
        other: StartupWindowMessageDispatchResult,
    ) = StartupWindowMessageDispatchResult(
        mouseMovePosted = mouseMovePosted || other.mouseMovePosted,
        mouseDownPosted = mouseDownPosted || other.mouseDownPosted,
        mouseUpPosted = mouseUpPosted || other.mouseUpPosted,
        nativeBindingFailure = nativeBindingFailure || other.nativeBindingFailure,
    )
}
