package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.GameStartupModeEnum
import club.xiaojiawei.hsscript.starter.GameStartupModeSequencePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActionDispatchGateTest {

    @Test
    fun `startup handoff dispatch is blocked by manual or automatic pause`() {
        assertFalse(ActionDispatchGate.allowForState("startup.handoff", paused = true, working = true))
        assertFalse(ActionDispatchGate.allowForState("startup.handoff", paused = true, working = false))
    }

    @Test
    fun `startup handoff dispatch is blocked when work is not active`() {
        assertFalse(ActionDispatchGate.allowForState("startup.handoff", paused = false, working = false))
    }

    @Test
    fun `explicit pause gates every configured platform startup mode`() {
        GameStartupModeEnum.values().forEach { mode ->
            val selected = GameStartupModeSequencePolicy.select(
                configuredModes = listOf(mode),
                attemptIndex = 0,
                launcherWindowAvailable = true,
            )
            assertEquals(mode, selected.mode)
            assertFalse(
                ActionDispatchGate.allowForState("startup.handoff.${selected.mode.name}", paused = true, working = true),
            )
        }
    }

    @Test
    fun `startup handoff dispatch is allowed only while unpaused and working`() {
        assertTrue(ActionDispatchGate.allowForState("startup.handoff", paused = false, working = true))
    }
}
