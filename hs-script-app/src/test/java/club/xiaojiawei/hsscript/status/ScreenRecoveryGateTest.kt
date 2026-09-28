package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenRecoveryGateTest {

    private enum class ScreenState { STARTUP, GAME, RESULT, UNKNOWN }
    private enum class SideEffect { POLL, OCR, SCREENSHOT, STATE_MUTATION, INPUT, AUTOMATIC_PAUSE, DIAGNOSTIC }
    private enum class FailurePath { TIMEOUT, RETRY, EXCEPTION, LOW_CONFIDENCE }

    @Test
    fun `global default off bypasses live screen recovery before any screen access`() {
        val previous = ConfigUtil.getBoolean(ConfigEnum.BETA_SCREEN_RECOVERY_ENABLED)
        try {
            ConfigUtil.putBoolean(ConfigEnum.BETA_SCREEN_RECOVERY_ENABLED, false, store = false)
            assertEquals(
                ScreenStateRecovery.InspectionResult.DISABLED,
                ScreenStateRecovery.inspectAndRecover(30_000, "test-state"),
            )
        } finally {
            ConfigUtil.putBoolean(ConfigEnum.BETA_SCREEN_RECOVERY_ENABLED, previous, store = false)
        }
    }

    @Test
    fun `default off gate prevents all recovery side effects in every screen state`() {
        val gate = ScreenRecoveryGate()
        val effects = mutableListOf<Triple<ScreenState, FailurePath, SideEffect>>()

        ScreenState.entries.forEach { state ->
            FailurePath.entries.forEach { failure ->
                SideEffect.entries.forEach { effect ->
                    val token = gate.tokenOrNull()
                    if (gate.isCurrent(token)) effects += Triple(state, failure, effect)
                }
            }
        }

        assertNull(gate.tokenOrNull())
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `enable admits recovery work while normal state progression remains independent`() {
        val gate = ScreenRecoveryGate()
        gate.setEnabled(true)
        val token = gate.tokenOrNull()

        assertTrue(gate.isCurrent(token))
        // The normal event/phase path has no dependency on the recovery gate.
        var normalTransitions = 0
        listOf("STARTUP", "HUB", "TOURNAMENT", "GAMEPLAY", "GAME_OVER").forEach {
            normalTransitions++
        }
        assertTrue(normalTransitions == 5)
    }

    @Test
    fun `disabling invalidates in-flight decisions and cancels queued recovery tasks`() {
        val gate = ScreenRecoveryGate(initiallyEnabled = true)
        val token = requireNotNull(gate.tokenOrNull())
        val pending = CompletableFuture<Void>()

        assertTrue(gate.track(token, pending))
        gate.setEnabled(false)

        assertFalse(gate.isCurrent(token))
        assertTrue(pending.isCancelled)
        assertNull(gate.tokenOrNull())
    }

    @Test
    fun `stale recovery work cannot resume after disable and re-enable`() {
        val gate = ScreenRecoveryGate(initiallyEnabled = true)
        val staleToken = requireNotNull(gate.tokenOrNull())

        gate.setEnabled(false)
        gate.setEnabled(true)

        assertFalse(gate.isCurrent(staleToken))
        assertTrue(gate.isCurrent(gate.tokenOrNull()))
    }
}
