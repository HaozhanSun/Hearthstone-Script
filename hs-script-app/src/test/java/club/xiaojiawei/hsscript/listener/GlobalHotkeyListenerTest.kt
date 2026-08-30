package club.xiaojiawei.hsscript.listener

import club.xiaojiawei.hsscript.status.PauseStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `F2 activates pause and F1 explicitly resumes`() {
        try {
            PauseStatus.isPause = false
            GlobalHotkeyListener.onHotKey(445)
            assertTrue(PauseStatus.isPause)
            GlobalHotkeyListener.onHotKey(444)
            assertFalse(PauseStatus.isPause)
        } finally {
            PauseStatus.isPause = true
        }
    }
}
