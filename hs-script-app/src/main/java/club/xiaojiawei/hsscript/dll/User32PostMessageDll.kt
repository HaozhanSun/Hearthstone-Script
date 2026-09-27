package club.xiaojiawei.hsscript.dll

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM

/** BOOL-preserving declaration for PostMessage, whose JNA platform wrapper returns Unit. */
internal interface User32PostMessageDll : Library {
    fun PostMessage(hwnd: HWND, message: Int, wParam: WPARAM, lParam: LPARAM): Boolean

    companion object {
        val INSTANCE: User32PostMessageDll by lazy {
            Native.load("user32", User32PostMessageDll::class.java)
        }
    }
}
