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
            ),
        )
    }

    @Test
    fun `repeated timed out handoffs remain retryable with a capped delay instead of pausing`() {
        assertEquals(
            GameStartupRecoveryPolicy.Decision.RETRY_GAME_HANDOFF,
            GameStartupRecoveryPolicy.decide(
                gameAlive = false,
                startupConfirmed = false,
                now = 20_000L,
                lastLaunchAt = 1_000L,
            ),
        )
        assertEquals(6_000L, GameStartupRecoveryPolicy.retryDelayMs(20_000L, 1_000L, consecutiveFailures = 3))
        assertEquals(
            GameStartupRecoveryPolicy.RETRY_BACKOFF_MAX_MS,
            GameStartupRecoveryPolicy.retryDelayMs(20_000L, 1_000L, consecutiveFailures = 20),
        )
        assertEquals(
            4_000L,
            GameStartupRecoveryPolicy.retryDelayMs(5_000L, 1_000L, consecutiveFailures = 1),
        )
    }

    @Test
    fun `retry reattaches to a late live game and never launches over it`() {
        assertEquals(
            GameStartupRecoveryPolicy.RetryAction.REATTACH_GAME_STARTER,
            GameStartupRecoveryPolicy.retryAction(startupConfirmed = false, gameAlive = true),
        )
        assertEquals(
            GameStartupRecoveryPolicy.RetryAction.STARTER_CHAIN,
            GameStartupRecoveryPolicy.retryAction(startupConfirmed = false, gameAlive = false),
        )
        assertEquals(
            GameStartupRecoveryPolicy.RetryAction.NONE,
            GameStartupRecoveryPolicy.retryAction(startupConfirmed = true, gameAlive = false),
        )
    }

    @Test
    fun `launch dispatch stays unconfirmed through retries until a stable game window appears`() {
        var handoff = GameStartupHandoffPolicy.State()

        // A process-launch request and even a live Battle.net launcher are not
        // proof Hearthstone accepted the request.
        repeat(3) { attempt ->
            val now = 20_000L + attempt * 30_000L
            val observation = GameStartupHandoffPolicy.observe(
                handoff,
                processAlive = false,
                windowFound = false,
                nowMs = now,
            )
            handoff = observation.state
            assertEquals(GameStartupHandoffPolicy.Decision.WAIT, observation.decision)
            assertEquals(
                GameStartupRecoveryPolicy.Decision.RETRY_GAME_HANDOFF,
                GameStartupRecoveryPolicy.decide(
                    gameAlive = false,
                    startupConfirmed = false,
                    now = now,
                    lastLaunchAt = 1_000L,
                ),
            )
            assertEquals(
                GameStartupRecoveryPolicy.RETRY_BACKOFF_MAX_MS,
                GameStartupRecoveryPolicy.retryDelayMs(now, 1_000L, consecutiveFailures = 20),
            )
        }

        assertEquals(
            GameStartupRecoveryPolicy.RetryAction.REATTACH_GAME_STARTER,
            GameStartupRecoveryPolicy.retryAction(startupConfirmed = false, gameAlive = true),
        )
        val firstVisible = GameStartupHandoffPolicy.observe(
            handoff,
            processAlive = true,
            windowFound = true,
            nowMs = 110_000L,
        )
        assertEquals(GameStartupHandoffPolicy.Decision.WAIT, firstVisible.decision)
        val stableVisible = GameStartupHandoffPolicy.observe(
            firstVisible.state,
            processAlive = true,
            windowFound = true,
            nowMs = 110_100L,
        )
        assertEquals(GameStartupHandoffPolicy.Decision.HANDOFF, stableVisible.decision)
    }
}
