package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.starter.StartupScreenRecoveryPolicy
import club.xiaojiawei.hsscript.starter.GameStartupHandoffPolicy
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconnectFailureRecoveryPolicyTest {
    @Test
    fun `reconnect failure performs bounded restarts then pauses, while repeated probe is throttled`() {
        val policy = ReconnectFailureRecoveryPolicy()
        val firstAt = 10_000L

        assertEquals(
            ReconnectFailureRecoveryPolicy.Evaluation(
                ReconnectFailureRecoveryPolicy.Decision.RESTART_CLIENT,
                attempt = 1,
            ),
            policy.observeFailure(firstAt),
        )
        assertEquals(
            ReconnectFailureRecoveryPolicy.Evaluation(
                ReconnectFailureRecoveryPolicy.Decision.WAIT_FOR_RESTART,
                attempt = 1,
            ),
            policy.observeFailure(firstAt + 1_000L),
        )
        assertEquals(
            ReconnectFailureRecoveryPolicy.Evaluation(
                ReconnectFailureRecoveryPolicy.Decision.RESTART_CLIENT,
                attempt = 2,
            ),
            policy.observeFailure(firstAt + ReconnectFailureRecoveryPolicy.DEFAULT_RETRY_COOLDOWN_MS),
        )
        assertEquals(
            ReconnectFailureRecoveryPolicy.Evaluation(
                ReconnectFailureRecoveryPolicy.Decision.PAUSE_AUTOMATION,
                attempt = 2,
            ),
            policy.observeFailure(firstAt + 2 * ReconnectFailureRecoveryPolicy.DEFAULT_RETRY_COOLDOWN_MS),
        )
    }

    @Test
    fun `restart is followed by a startup visual probe even before fresh Power log has bytes`() {
        val policy = ReconnectFailureRecoveryPolicy()
        assertEquals(
            ReconnectFailureRecoveryPolicy.Decision.RESTART_CLIENT,
            policy.observeFailure(20_000L).decision,
        )

        // Core.restart re-enters GameStarter; its screen-probe policy must be
        // eligible as soon as the new game window handoff is stable, without
        // waiting for the new session's Power.log to become non-empty.
        assertEquals(
            StartupScreenRecoveryPolicy.Decision.PROBE,
            StartupScreenRecoveryPolicy.decide(
                elapsedMs = StartupScreenRecoveryPolicy.INITIAL_PROBE_DELAY_MS,
                noLogProgressMs = 0L,
                normalFlowActive = false,
                initialProbeAttempted = false,
            ),
        )
        assertFalse(GameStartupHandoffPolicy.startupHandshakeConfirmed(inWar = false, mode = ModeEnum.LOGIN))
        assertTrue(GameStartupHandoffPolicy.startupHandshakeConfirmed(inWar = false, mode = ModeEnum.HUB))
        assertEquals(
            GameStartupHandoffPolicy.HandshakeTimeoutDecision.NO_PAUSE_NEEDED,
            GameStartupHandoffPolicy.onHandshakeTimeout(
                startupConfirmed = GameStartupHandoffPolicy.startupHandshakeConfirmed(
                    inWar = false,
                    mode = ModeEnum.HUB,
                ),
            ),
        )
    }

    @Test
    fun `confirmed healthy screen resets restart budget and the attempt window is bounded`() {
        val policy = ReconnectFailureRecoveryPolicy()
        val firstAt = 30_000L
        policy.observeFailure(firstAt)
        policy.onStartupConfirmed()
        assertEquals(
            ReconnectFailureRecoveryPolicy.Decision.RESTART_CLIENT,
            policy.observeFailure(firstAt + 1_000L).decision,
        )
        assertEquals(
            ReconnectFailureRecoveryPolicy.Decision.RESTART_CLIENT,
            policy.observeFailure(firstAt + ReconnectFailureRecoveryPolicy.DEFAULT_ATTEMPT_WINDOW_MS).decision,
        )
    }
}
