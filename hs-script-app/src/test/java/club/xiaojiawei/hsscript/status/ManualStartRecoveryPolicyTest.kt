package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals

class ManualStartRecoveryPolicyTest {
    @Test
    fun `expired debug run does not block scheduled explicit rearm for missing current session`() {
        assertEquals(
            ManualStartRecoveryPolicy.Decision.REARM_STALE_SESSION,
            ManualStartRecoveryPolicy.evaluate(
                scheduleAllowsStart = true,
                working = true,
                currentGameSessionReady = false,
                debugRunExpired = true,
            ),
        )
    }

    @Test
    fun `expired debug run cannot grant work outside schedule and startup window`() {
        assertEquals(
            ManualStartRecoveryPolicy.Decision.BLOCK_OUTSIDE_ALLOWED_WINDOW,
            ManualStartRecoveryPolicy.evaluate(
                scheduleAllowsStart = false,
                working = true,
                currentGameSessionReady = false,
                debugRunExpired = true,
            ),
        )
    }

    @Test
    fun `healthy current session still follows ordinary explicit start`() {
        assertEquals(
            ManualStartRecoveryPolicy.Decision.START,
            ManualStartRecoveryPolicy.evaluate(
                scheduleAllowsStart = true,
                working = true,
                currentGameSessionReady = true,
                debugRunExpired = false,
            ),
        )
    }

    @Test
    fun `explicit Start after expired Test Run accepts a fresh bounded startup window`() {
        assertEquals(
            ManualStartRecoveryPolicy.Decision.START_AFTER_DEBUG_EXPIRY,
            ManualStartRecoveryPolicy.evaluate(
                scheduleAllowsStart = true, // WorkTimeListener opened the configured startup window for this click.
                working = false,
                currentGameSessionReady = false,
                debugRunExpired = true,
            ),
        )
    }

    @Test
    fun `manual Start does not use expired Test Run to bypass a closed schedule and startup window`() {
        assertEquals(
            ManualStartRecoveryPolicy.Decision.BLOCK_OUTSIDE_ALLOWED_WINDOW,
            ManualStartRecoveryPolicy.evaluate(
                scheduleAllowsStart = false,
                working = false,
                currentGameSessionReady = false,
                debugRunExpired = true,
            ),
        )
    }

    @Test
    fun `manual Start with active game or matchmaking is classified as resume only`() {
        assertEquals(
            ManualStartRecoveryPolicy.Decision.RESUME_EXISTING_SESSION,
            ManualStartRecoveryPolicy.evaluate(
                scheduleAllowsStart = true,
                working = false,
                currentGameSessionReady = true,
                debugRunExpired = true,
                activeGameOrMatchmaking = true,
            ),
        )
    }
}
