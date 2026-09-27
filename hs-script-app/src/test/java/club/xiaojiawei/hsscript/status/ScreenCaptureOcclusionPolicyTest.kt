package club.xiaojiawei.hsscript.status

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenCaptureOcclusionPolicyTest {
    private val target = 0x100L
    private val capture = Rectangle(100, 100, 800, 600)

    @Test
    fun `visible opaque overlay across Hearthstone capture is rejected regardless of process`() {
        val result = ScreenCaptureOcclusionPolicy.evaluate(
            target,
            capture,
            listOf(ScreenCaptureOcclusionPolicy.Layer(0x200L, true, Rectangle(0, 0, 1920, 1080))),
            enumerationComplete = true,
        )
        assertFalse(result.accepted)
        assertEquals("visible-window-intersects-capture", result.reason)
        assertEquals(0x200L, result.occludingHandle)
    }

    @Test
    fun `unrelated visible window outside capture is allowed without application whitelist`() {
        val result = ScreenCaptureOcclusionPolicy.evaluate(
            target,
            capture,
            listOf(ScreenCaptureOcclusionPolicy.Layer(0x200L, true, Rectangle(1000, 100, 400, 600))),
            enumerationComplete = true,
        )
        assertTrue(result.accepted)
    }

    @Test
    fun `unknown visible bounds and incomplete z-order enumeration fail closed`() {
        val unknown = ScreenCaptureOcclusionPolicy.evaluate(
            target,
            capture,
            listOf(ScreenCaptureOcclusionPolicy.Layer(0x200L, true, null)),
            enumerationComplete = true,
        )
        assertFalse(unknown.accepted)
        assertEquals("visible-window-bounds-unknown", unknown.reason)

        val incomplete = ScreenCaptureOcclusionPolicy.evaluate(target, capture, emptyList(), enumerationComplete = false)
        assertFalse(incomplete.accepted)
        assertEquals("z-order-enumeration-incomplete", incomplete.reason)
    }

    @Test
    fun `invisible layer does not contaminate capture pixels`() {
        assertTrue(
            ScreenCaptureOcclusionPolicy.evaluate(
                target,
                capture,
                listOf(ScreenCaptureOcclusionPolicy.Layer(0x200L, false, null)),
                enumerationComplete = true,
            ).accepted,
        )
    }
}
