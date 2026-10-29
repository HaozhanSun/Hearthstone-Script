package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupModeEntryDispatchPolicyTest {
    @Test
    fun `scheduled chooser callback dispatches only the named Traditional Battle action`() {
        val actions = mutableListOf<String>()

        val dispatched = StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
            chooserProofFresh = true,
            paused = false,
            processId = 33512,
            hwnd = "game-hwnd",
            dispatch = { action -> actions.add(action); true },
        )

        assertTrue(dispatched)
        assertEquals(listOf(StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION), actions)
    }

    @Test
    fun `scheduled chooser callback does not dispatch without fresh proof or while paused`() {
        val actions = mutableListOf<String>()

        assertFalse(StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
            chooserProofFresh = false,
            paused = false,
            processId = 33512,
            hwnd = "game-hwnd",
            dispatch = { action -> actions.add(action); true },
        ))
        assertFalse(StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
            chooserProofFresh = true,
            paused = true,
            processId = 33512,
            hwnd = "game-hwnd",
            dispatch = { action -> actions.add(action); true },
        ))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `mode click owns foreground transaction and concurrent recovery capture is deferred`() {
        val actions = mutableListOf<String>()
        var captureRan = false
        val dispatched = StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
            chooserProofFresh = true,
            paused = false,
            processId = 33512,
            hwnd = "game-hwnd",
        ) { action ->
            actions += action
            assertTrue(StartupModeEntryDispatchPolicy.isModeEntryInFlight(33512, "game-hwnd"))
            assertEquals(null, StartupModeEntryDispatchPolicy.withRecoveryCapture(33512, "game-hwnd") {
                captureRan = true
            }, "an idle probe must not steal focus while the authorized selector click is in flight")
            true
        }

        assertTrue(dispatched)
        assertEquals(listOf(StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION), actions)
        assertFalse(captureRan)
        assertFalse(StartupModeEntryDispatchPolicy.isModeEntryInFlight(33512, "game-hwnd"))
        assertEquals("captured", StartupModeEntryDispatchPolicy.withRecoveryCapture(33512, "game-hwnd") {
            "captured"
        })
    }

    @Test
    fun `rejected selector input is not reported as dispatched`() {
        val dispatched = StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
            chooserProofFresh = true,
            paused = false,
            processId = 33512,
            hwnd = "game-hwnd",
            dispatch = { false },
        )
        assertFalse(dispatched)
    }
}
