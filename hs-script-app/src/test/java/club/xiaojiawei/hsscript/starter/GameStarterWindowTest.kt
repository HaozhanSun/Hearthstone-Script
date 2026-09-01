package club.xiaojiawei.hsscript.starter

import club.xiaojiawei.hsscript.utils.GameUtil
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameStarterWindowTest {

    @Test
    fun `screen-only fallback is rejected until a real window is discovered`() {
        val screenOnlyFallback = HWND(Pointer.createConstant(1))
        val realWindow = HWND(Pointer.createConstant(0x37f07c0))

        assertNull(GameUtil.resolveRealGameWindow(screenOnlyFallback))
        assertEquals(realWindow, GameUtil.resolveRealGameWindow(realWindow))
    }

    @Test
    fun `null discovery remains a wait state`() {
        assertNull(GameUtil.resolveRealGameWindow(null))
    }
}
