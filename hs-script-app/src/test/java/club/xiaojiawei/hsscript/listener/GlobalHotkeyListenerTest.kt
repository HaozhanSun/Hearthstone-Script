package club.xiaojiawei.hsscript.listener

import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.PauseStatus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobalHotkeyListenerTest {

    private val originalPaused = PauseStatus.isPause
    private val originalWorking = WorkTimeListener.working

    @AfterTest
    fun restorePauseState() {
        WorkTimeListener.working = originalWorking
        PauseStatus.setManualPauseForTest(originalPaused)
    }

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
    fun `only F2 can pause an active run`() {
        WorkTimeListener.working = true
        PauseStatus.setManualPauseForTest(false)

        assertFalse(PauseStatus.setAutomaticPause(true))
        assertFalse(PauseStatus.isPause, "an uncertain recovery must not pause a user-started run")

        PauseStatus.isPause = true
        assertFalse(PauseStatus.isPause, "legacy direct pause assignments must also be suppressed during a user-started run")

        assertFalse(PauseStatus.suppressNonF2Pause("main-ui/test"))
        assertFalse(PauseStatus.isPause, "the main UI pause control must not pause a user-started run")

        assertFalse(PauseStatus.suppressNonF2Pause("tray/test"))
        assertFalse(PauseStatus.isPause, "the tray pause control must not pause a user-started run")

        GlobalHotkeyListener.pressNonF2PauseForTest()
        assertFalse(PauseStatus.isPause, "a configurable non-F2 hotkey must not pause a user-started run")

        GlobalHotkeyListener.pressF2ForTest()
        assertTrue(PauseStatus.isPause, "the explicit F2 path remains able to pause")
        assertEquals(PauseStatus.Origin.MANUAL, PauseStatus.pauseOrigin)
    }
}
