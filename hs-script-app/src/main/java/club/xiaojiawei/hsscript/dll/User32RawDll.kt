package club.xiaojiawei.hsscript.dll

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Raw pointer mapping for z-order APIs.
 *
 * HWND_TOPMOST and HWND_NOTOPMOST are special HWND sentinel values, not real
 * window handles. Mapping this one call with Pointer avoids a JNA structure
 * conversion changing those sentinel values before user32 receives them.
 */
@Suppress("ktlint:standard:function-naming")
interface User32RawDll : Library {
    fun SetWindowPos(
        hwnd: Pointer?,
        hWndInsertAfter: Pointer?,
        x: Int,
        y: Int,
        cx: Int,
        cy: Int,
        uFlags: Int,
    ): Boolean

    companion object {
        val INSTANCE: User32RawDll by lazy {
            Native.load("user32", User32RawDll::class.java)
        }
    }
}
