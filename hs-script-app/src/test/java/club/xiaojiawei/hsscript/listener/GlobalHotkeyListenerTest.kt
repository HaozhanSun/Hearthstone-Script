package club.xiaojiawei.hsscript.listener

import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobalHotkeyListenerTest {

    private val originalPaused = PauseStatus.isPause
    private val originalWorking = WorkTimeListener.working

    @BeforeTest
    fun resetFixedHotkeyState() {
        GlobalHotkeyListener.resetFixedHotkeyStateForTest()
    }

    @AfterTest
    fun restorePauseState() {
        GlobalHotkeyListener.resetFixedHotkeyStateForTest()
        WorkTimeListener.working = originalWorking
        PauseStatus.setManualPause(originalPaused)
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
    fun `Windows-unflagged F2 pauses but injected F2 leaves matchmaking dispatch active`() {
        WorkTimeListener.working = true
        PauseStatus.setManualPause(false)

        assertEquals(FixedHotkeyInputPolicy.Decision.IGNORE_INJECTED, GlobalHotkeyListener.pressInjectedF2ForTest())
        assertEquals(FixedHotkeyInputPolicy.Decision.IGNORE_INJECTED, GlobalHotkeyListener.releaseInjectedF2ForTest())
        assertEquals(
            FixedHotkeyInputPolicy.Decision.IGNORE_INJECTED,
            FixedHotkeyInputPolicy.decideLowLevel(FixedHotkeyInputPolicy.LLKHF_LOWER_IL_INJECTED),
            "lower-integrity injected input is also never a pause authority",
        )
        assertFalse(PauseStatus.isPause, "an injected F2 cannot pause the script")
        assertTrue(WorkTimeListener.working, "an injected F2 cannot stop the active runtime")
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(working = true, paused = false))

        val windowsUnflagged = GlobalHotkeyListener.pressF2ForTest()
        assertEquals(FixedHotkeyInputPolicy.Decision.ACCEPT_WINDOWS_UNFLAGGED, windowsUnflagged)
        assertTrue(PauseStatus.isPause, "Windows-unflagged F2 remains the manual pause authority")
        assertFalse(WorkTimeListener.working, "Windows-unflagged F2 stops the active runtime")
        assertFalse(MatchmakingGuardPolicy.runtimeAllowsInput(working = false, paused = true))
    }

    @Test
    fun `repeated F2 is edge-deduplicated and unverifiable callbacks do not pause`() {
        WorkTimeListener.working = true
        PauseStatus.setManualPause(false)

        assertEquals(FixedHotkeyInputPolicy.Decision.ACCEPT_WINDOWS_UNFLAGGED, GlobalHotkeyListener.pressF2ForTest())
        assertTrue(PauseStatus.isPause)
        assertEquals(
            FixedHotkeyInputPolicy.Decision.ACCEPT_WINDOWS_UNFLAGGED,
            GlobalHotkeyListener.pressF2ForTest(),
            "a held/repeated key is acknowledged but does not issue a second pause transition",
        )
        GlobalHotkeyListener.releaseF2ForTest()

        val detector = GlobalHotkeyListener.FixedHotkeyEdgeDetector()
        assertEquals(445, detector.onKeyDown(0x71))
        detector.onKeyUp(0x71)
        assertEquals(445, detector.onKeyDown(0x71), "a key-up resets the next press edge")

        PauseStatus.setManualPause(false)
        WorkTimeListener.working = true
        GlobalHotkeyListener.onHotKey(445)
        assertFalse(PauseStatus.isPause, "the unverifiable JIntellitype callback cannot pause")
        assertTrue(WorkTimeListener.working)
        assertEquals(FixedHotkeyInputPolicy.Decision.IGNORE_HOOK_UNAVAILABLE, FixedHotkeyInputPolicy.hookUnavailable())
    }

    @Test
    fun `Windows-unflagged F1 remains the matching resume control`() {
        WorkTimeListener.working = false
        PauseStatus.setManualPause(true)

        assertEquals(FixedHotkeyInputPolicy.Decision.ACCEPT_WINDOWS_UNFLAGGED, GlobalHotkeyListener.pressF1ForTest())
        assertFalse(PauseStatus.isPause)
        GlobalHotkeyListener.releaseF1ForTest()
    }

    @Test
    fun `fixed hotkey wiring has no provenance-free pause fallback`() {
        val relative = Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "listener", "GlobalHotkeyListener.kt",
        )
        val source = listOf(Path.of("."), Path.of("hs-script-app"))
            .map { it.resolve(relative) }
            .first(Files::isRegularFile)
            .let(Files::readString)

        assertTrue(source.contains("keyboardData.flags"))
        assertTrue(source.contains("LLKHF_INJECTED"))
        assertFalse(source.contains("INSTANCE.GetAsyncKeyState"), "key-state polling cannot prove input provenance")
        assertFalse(source.contains("registerFixedControls"), "JIntellitype fixed controls cannot prove input provenance")
        assertTrue(source.contains("source=JIntellitype"))
        assertTrue(source.contains("action=none"))
        assertTrue(source.contains("FIXED_HOTKEY_UNAVAILABLE"))
        assertTrue(source.contains("manualF1F2=unavailable"))
    }
}
