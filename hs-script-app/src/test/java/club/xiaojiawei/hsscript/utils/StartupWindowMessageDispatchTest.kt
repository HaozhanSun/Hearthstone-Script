package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.dll.User32PostMessageDll
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class StartupWindowMessageDispatchTest {
    private val hwnd = HWND(Pointer.createConstant(0x12345L))
    private val point = LPARAM(0x00100020L)

    @Test
    fun `native binding resolves exact PostMessageW export and reports invalid target`() {
        assumeTrue(System.getProperty("os.name").contains("Windows", ignoreCase = true))

        // WM_NULL is harmless even if the deliberately invalid handle were ever reused.
        assertFalse(
            User32PostMessageDll.INSTANCE.PostMessageW(
                HWND(Pointer.createConstant(0x7fffdeaddeadL)),
                0x0000,
                WPARAM(0L),
                LPARAM(0L),
            ),
        )
    }

    @Test
    fun `native binding accepts harmless WM_NULL on desktop window`() {
        assumeTrue(System.getProperty("os.name").contains("Windows", ignoreCase = true))
        val desktop = User32.INSTANCE.GetDesktopWindow()

        assertTrue(
            User32PostMessageDll.INSTANCE.PostMessageW(
                desktop,
                0x0000,
                WPARAM(0L),
                LPARAM(0L),
            ),
        )
    }

    @Test
    fun `dispatch posts move down then up and returns target acceptance`() {
        val observed = mutableListOf<Triple<Int, Long, Long>>()
        val result = StartupWindowMessageDispatch.click(hwnd, point) { target, message, wParam, lParam ->
            assertEquals(hwnd, target)
            observed += Triple(message, wParam.toLong(), lParam.toLong())
            true
        }

        assertEquals(
            listOf(
                Triple(0x0200, 0L, point.toLong()),
                Triple(0x0201, 1L, point.toLong()),
                Triple(0x0202, 0L, point.toLong()),
            ),
            observed,
        )
        assertTrue(result.allMessagesPosted)
    }

    @Test
    fun `native refusal remains a failed dispatch while release is still attempted`() {
        val messages = mutableListOf<Int>()
        val result = StartupWindowMessageDispatch.click(hwnd, point) { _, message, _, _ ->
            messages += message
            message != 0x0201
        }

        assertEquals(listOf(0x0200, 0x0201, 0x0202), messages)
        assertFalse(result.allMessagesPosted)
        assertTrue(result.mouseMovePosted)
        assertFalse(result.mouseDownPosted)
        assertTrue(result.mouseUpPosted)
    }

    @Test
    fun `missing native entry point fails closed and does not claim dispatch`() {
        var calls = 0
        val result = StartupWindowMessageDispatch.click(hwnd, point) { _, _, _, _ ->
            calls++
            throw UnsatisfiedLinkError("missing test export")
        }

        assertEquals(1, calls)
        assertTrue(result.nativeBindingFailure)
        assertFalse(result.allMessagesPosted)
    }
}
