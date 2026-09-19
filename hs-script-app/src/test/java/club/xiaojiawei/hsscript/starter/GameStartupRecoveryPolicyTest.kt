package club.xiaojiawei.hsscript.starter

import kotlin.test.Test
import kotlin.test.assertEquals

class GameStartupRecoveryPolicyTest {
    @Test
    fun `a short lived game retries through the existing launcher handoff`() {
        assertEquals(
            GameStartupRecoveryPolicy.Decision.RETRY_GAME_HANDOFF,
            GameStartupRecoveryPolicy.decide(
                gameAlive = false,
                startupConfirmed = false,
                now = 20_000L,
                lastLaunchAt = 1_000L,
                consecutiveFailures = 0,
            ),
        )
    }

    @Test
    fun `a live Battle net handoff is throttled and never treated as a reason to kill it`() {
        assertEquals(
            GameStartupRecoveryPolicy.Decision.WAIT_FOR_HANDOFF,
            GameStartupRecoveryPolicy.decide(
                gameAlive = false,
                startupConfirmed = false,
                now = 8_999L,
                lastLaunchAt = 1_000L,
                consecutiveFailures = 1,
            ),
        )
    }

    @Test
    fun `multiple launcher processes still produce one bounded game retry decision`() {
        // The policy deliberately has no platform-process count input: helper
        // process multiplicity must not change the one-game retry semantics.
        assertEquals(
            GameStartupRecoveryPolicy.Decision.RETRY_GAME_HANDOFF,
            GameStartupRecoveryPolicy.decide(
                gameAlive = false,
                startupConfirmed = false,
                now = 20_000L,
                lastLaunchAt = 1_000L,
                consecutiveFailures = 2,
            ),
        )
    }

    @Test
    fun `an existing game process blocks a second game launch`() {
        assertEquals(
            GameStartupRecoveryPolicy.Decision.WAIT_FOR_HANDOFF,
            GameStartupRecoveryPolicy.decide(
                gameAlive = true,
                startupConfirmed = false,
                now = 20_000L,
                lastLaunchAt = 1_000L,
                consecutiveFailures = 0,
            ),
        )
    }

    @Test
    fun `empty Power log does not confirm startup`() {
        assertEquals(
            GameStartupRecoveryPolicy.Decision.RETRY_GAME_HANDOFF,
            GameStartupRecoveryPolicy.decide(
                gameAlive = false,
                startupConfirmed = false,
                now = 20_000L,
                lastLaunchAt = 1_000L,
                consecutiveFailures = 1,
            ),
        )
    }

    @Test
    fun `repeated startup failure becomes a visible automatic pause instead of infinite recovery`() {
        assertEquals(
            GameStartupRecoveryPolicy.Decision.PAUSE_WITH_DIAGNOSTIC,
            GameStartupRecoveryPolicy.decide(
                gameAlive = false,
                startupConfirmed = false,
                now = 20_000L,
                lastLaunchAt = 1_000L,
                consecutiveFailures = GameStartupRecoveryPolicy.MAX_FAILURES,
            ),
        )
    }
}
