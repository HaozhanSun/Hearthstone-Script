package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

class ExactCaptureOcrReuseTest {
    @Test
    fun `reuse requires exact pixels and same process and window`() {
        val cache = ExactCaptureOcrReuse()
        cache.remember(101, "hwnd-A", "frame-1", "DECK_SELECTION")

        assertEquals("DECK_SELECTION", cache.lookup(101, "hwnd-A", "frame-1"))
        assertNull(cache.lookup(101, "hwnd-A", "frame-2"), "changed pixels require fresh OCR")
        assertNull(cache.lookup(102, "hwnd-A", "frame-1"), "a new process requires fresh OCR")
        assertNull(cache.lookup(101, "hwnd-B", "frame-1"), "a new window requires fresh OCR")
        assertNull(cache.lookup(101, "hwnd-A", "frame-1"), "mismatch evicts the previous entry")
    }

    @Test
    fun `fingerprint covers every pixel and image dimensions`() {
        val first = BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB)
        val same = BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB)
        val changed = BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB).apply { setRGB(7, 3, 0x123456) }
        val resized = BufferedImage(4, 8, BufferedImage.TYPE_INT_RGB)

        assertEquals(ExactCaptureOcrReuse.fingerprint(first), ExactCaptureOcrReuse.fingerprint(same))
        assertNotEquals(ExactCaptureOcrReuse.fingerprint(first), ExactCaptureOcrReuse.fingerprint(changed))
        assertNotEquals(ExactCaptureOcrReuse.fingerprint(first), ExactCaptureOcrReuse.fingerprint(resized))
    }

    @Test
    fun `missing process or window identity cannot be cached`() {
        val cache = ExactCaptureOcrReuse()
        cache.remember(0, "hwnd-A", "frame", "text")
        assertNull(cache.lookup(0, "hwnd-A", "frame"))
        cache.remember(101, "", "frame", "text")
        assertNull(cache.lookup(101, "", "frame"))
    }

    @Test
    fun `empty or failed OCR result is never reused`() {
        val cache = ExactCaptureOcrReuse()
        cache.remember(101, "hwnd-A", "frame", "")
        assertNull(cache.lookup(101, "hwnd-A", "frame"), "empty OCR must retry on the next identical fresh frame")
    }

    @Test
    fun `exact frame reuse is enabled only for Beta`() {
        assertEquals(true, ExactCaptureOcrReuse.enabledForChannel("beta"))
        assertEquals(false, ExactCaptureOcrReuse.enabledForChannel("stable"))
        assertEquals(false, ExactCaptureOcrReuse.enabledForChannel("release-candidate"))
        assertEquals(false, ExactCaptureOcrReuse.enabledForChannel("unknown"))
    }
}
