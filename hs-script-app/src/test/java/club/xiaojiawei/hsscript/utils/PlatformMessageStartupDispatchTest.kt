package club.xiaojiawei.hsscript.utils

import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlatformMessageStartupDispatchTest {

    @Test
    fun `CTA dispatch is a single client-coordinate message request`() {
        val point = assertNotNull(PlatformMessageStartupDispatch.startButtonPoint(1600, 999))
        assertEquals(Point(176, 891), point)
        var postedPoint: Point? = null

        val queued = PlatformMessageStartupDispatch.dispatch(point, hwnd = null) { actualPoint, _ ->
            postedPoint = actualPoint
            true
        }

        assertTrue(queued)
        assertEquals(point, postedPoint)
    }

    @Test
    fun `invalid client dimensions and failed message dispatch stay blocked`() {
        assertEquals(null, PlatformMessageStartupDispatch.startButtonPoint(160, 28))
        assertFalse(PlatformMessageStartupDispatch.dispatch(Point(176, 891), hwnd = null) { _, _ -> false })
    }
}
