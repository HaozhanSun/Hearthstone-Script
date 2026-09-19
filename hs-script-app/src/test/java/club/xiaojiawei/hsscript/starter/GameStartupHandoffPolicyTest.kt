package club.xiaojiawei.hsscript.starter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameStartupHandoffPolicyTest {
    @Test
    fun `requires two consecutive live window observations`() {
        val first = GameStartupHandoffPolicy.observe(GameStartupHandoffPolicy.State(), true, true, 1_000L)
        assertEquals(GameStartupHandoffPolicy.Decision.WAIT, first.decision)
        val second = GameStartupHandoffPolicy.observe(first.state, true, true, 1_100L)
        assertEquals(GameStartupHandoffPolicy.Decision.HANDOFF, second.decision)
    }

    @Test
    fun `short process loss waits and requires fresh observations`() {
        val observed = GameStartupHandoffPolicy.observe(GameStartupHandoffPolicy.State(), true, true, 1_000L)
        val lost = GameStartupHandoffPolicy.observe(observed.state, false, false, 2_000L)
        assertEquals(GameStartupHandoffPolicy.Decision.WAIT, lost.decision)
        assertEquals(0, lost.state.stableObservations)
        val fresh = GameStartupHandoffPolicy.observe(lost.state, true, true, 2_100L)
        assertEquals(GameStartupHandoffPolicy.Decision.WAIT, fresh.decision)
    }

    @Test
    fun `startup screen probe waits for readable Power log content`() {
        assertFalse(GameStartupHandoffPolicy.startupScreenProbeReady(false, 0L))
        assertFalse(GameStartupHandoffPolicy.startupScreenProbeReady(true, 0L))
        assertFalse(GameStartupHandoffPolicy.startupScreenProbeReady(true, -1L))
        assertTrue(GameStartupHandoffPolicy.startupScreenProbeReady(true, 1L))
    }

    @Test
    fun `startup screen probe remains blocked while Power log is empty`() {
        assertTrue(
            GameStartupHandoffPolicy.shouldDeferStartupScreenProbe(
                powerLogAttached = true,
                powerLogLength = 0L,
                decision = StartupScreenRecoveryPolicy.Decision.WAIT,
            ),
        )
        assertTrue(
            GameStartupHandoffPolicy.shouldDeferStartupScreenProbe(
                powerLogAttached = true,
                powerLogLength = 0L,
                decision = StartupScreenRecoveryPolicy.Decision.PROBE,
            ),
        )
        assertFalse(
            GameStartupHandoffPolicy.shouldDeferStartupScreenProbe(
                powerLogAttached = true,
                powerLogLength = 1L,
                decision = StartupScreenRecoveryPolicy.Decision.PROBE,
            ),
        )
    }

    @Test
    fun `power log retry begins at thirty seconds`() {
        assertFalse(GameStartupHandoffPolicy.powerLogStallRetryDue(10_000L, 39_999L))
        assertTrue(GameStartupHandoffPolicy.powerLogStallRetryDue(10_000L, 40_000L))
    }
}
