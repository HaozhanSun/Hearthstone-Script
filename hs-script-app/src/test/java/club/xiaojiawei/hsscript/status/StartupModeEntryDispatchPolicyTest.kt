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
            dispatch = actions::add,
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
            dispatch = actions::add,
        ))
        assertFalse(StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
            chooserProofFresh = true,
            paused = true,
            dispatch = actions::add,
        ))
        assertTrue(actions.isEmpty())
    }
}
