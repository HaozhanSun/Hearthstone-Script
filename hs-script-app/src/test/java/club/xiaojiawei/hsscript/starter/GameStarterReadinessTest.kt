package club.xiaojiawei.hsscript.starter

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameStarterReadinessTest {
    @Test
    fun `platform is not closed while the game is dead`() {
        assertFalse(PlatformCloseReadiness.shouldClose(true, false, true, 60_000L, 20_000L))
        assertFalse(PlatformCloseReadiness.shouldClose(false, true, true, 60_000L, 20_000L))
    }

    @Test
    fun `platform closes only after a visible window is stable`() {
        assertFalse(PlatformCloseReadiness.shouldClose(true, true, true, 19_999L, 20_000L))
        assertTrue(PlatformCloseReadiness.shouldClose(true, true, true, 20_000L, 20_000L))
    }

    @Test
    fun `platform stays alive until the startup handshake is confirmed`() {
        assertFalse(PlatformCloseReadiness.shouldClose(true, true, false, 60_000L, 20_000L))
    }
}
