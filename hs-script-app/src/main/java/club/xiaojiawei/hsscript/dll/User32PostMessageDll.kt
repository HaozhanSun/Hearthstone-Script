package club.xiaojiawei.hsscript.dll

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.win32.StdCallLibrary

/** BOOL-preserving binding to the explicitly exported Unicode Win32 entry point. */
@Suppress("ktlint:standard:function-naming")
internal interface User32PostMessageDll : StdCallLibrary {
    fun PostMessageW(hwnd: HWND, message: Int, wParam: WPARAM, lParam: LPARAM): Boolean

    companion object {
        val INSTANCE: User32PostMessageDll by lazy {
            Native.load("user32", User32PostMessageDll::class.java)
        }
    }
}
