package club.xiaojiawei.hsscript.listener

import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalHotkeyListenerTest {

    @Test
    fun `fixed hotkeys emit only on a key down edge`() {
        val detector = GlobalHotkeyListener.FixedHotkeyEdgeDetector()

        assertEquals(444, detector.onKeyDown(0x70))
        assertEquals(null, detector.onKeyDown(0x70))
        assertEquals(445, detector.onKeyDown(0x71))
        assertEquals(null, detector.onKeyDown(0x71))
        detector.onKeyUp(0x70)
        detector.onKeyUp(0x71)
        assertEquals(444, detector.onKeyDown(0x70))
        assertEquals(445, detector.onKeyDown(0x71))
    }
}
