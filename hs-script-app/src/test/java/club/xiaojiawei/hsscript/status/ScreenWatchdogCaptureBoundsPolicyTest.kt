package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Rectangle

class ScreenWatchdogCaptureBoundsPolicyTest {
    private val desktop = Rectangle(0, 0, 1920, 1080)

    @Test
    fun `upstream route retains desktop fallback rather than adopting hwnd fallback`() {
        val staleOrUnverifiedWindow = Rectangle(2100, 0, 900, 700)

        assertEquals(
            desktop,
            ScreenWatchdogCaptureBoundsPolicy.select(
                cachedGameBounds = null,
                currentWindowBounds = staleOrUnverifiedWindow,
                desktopBounds = desktop,
                betaExtensionsEnabled = false,
            ),
        )
    }

    @Test
    fun `beta hwnd fallback is clipped and stale offscreen hwnd is rejected`() {
        assertNull(
            ScreenWatchdogCaptureBoundsPolicy.select(
                cachedGameBounds = null,
                currentWindowBounds = Rectangle(2100, 0, 900, 700),
                desktopBounds = desktop,
                betaExtensionsEnabled = true,
            ),
        )
        assertEquals(
            Rectangle(1200, 0, 720, 700),
            ScreenWatchdogCaptureBoundsPolicy.select(
                cachedGameBounds = null,
                currentWindowBounds = Rectangle(1200, 0, 900, 700),
                desktopBounds = desktop,
                betaExtensionsEnabled = true,
            ),
        )
    }
}
