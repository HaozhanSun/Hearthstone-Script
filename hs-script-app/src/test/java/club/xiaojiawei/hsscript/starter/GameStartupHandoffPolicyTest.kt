package club.xiaojiawei.hsscript.starter

import club.xiaojiawei.hsscriptbase.enums.ModeEnum
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
    fun `verified Home mode confirms startup while the current session log is still empty`() {
        assertTrue(GameStartupHandoffPolicy.startupHandshakeConfirmed(false, ModeEnum.HUB))
        assertFalse(GameStartupHandoffPolicy.startupHandshakeConfirmed(false, ModeEnum.STARTUP))
        assertFalse(GameStartupHandoffPolicy.startupHandshakeConfirmed(false, ModeEnum.LOGIN))
        assertFalse(GameStartupHandoffPolicy.startupHandshakeConfirmed(false, null))
        assertTrue(GameStartupHandoffPolicy.startupHandshakeConfirmed(true, null))
    }

    @Test
    fun `empty log and unconfirmed login pause at timeout while verified Home completes startup`() {
        // A zero-byte session Power.log is not itself a menu/handshake proof.
        assertFalse(GameStartupHandoffPolicy.startupHandshakeConfirmed(false, ModeEnum.LOGIN))
        assertEquals(
            GameStartupHandoffPolicy.HandshakeTimeoutDecision.AUTOMATIC_PAUSE,
            GameStartupHandoffPolicy.onHandshakeTimeout(
                GameStartupHandoffPolicy.startupHandshakeConfirmed(false, ModeEnum.LOGIN),
            ),
        )
        assertEquals(
            GameStartupHandoffPolicy.HandshakeTimeoutDecision.NO_PAUSE_NEEDED,
            GameStartupHandoffPolicy.onHandshakeTimeout(
                GameStartupHandoffPolicy.startupHandshakeConfirmed(false, ModeEnum.HUB),
            ),
        )
        assertEquals(
            GameStartupHandoffPolicy.HandshakeTimeoutDecision.AUTOMATIC_PAUSE,
            GameStartupHandoffPolicy.onHandshakeTimeout(
                GameStartupHandoffPolicy.startupHandshakeConfirmed(false, null),
            ),
        )
    }

    @Test
    fun `handshake timeout grants only a bounded grace to an in-flight visual probe`() {
        assertEquals(
            GameStartupHandoffPolicy.HandshakeTimeoutDecision.WAIT_FOR_SCREEN_PROBE,
            GameStartupHandoffPolicy.onHandshakeTimeout(
                startupConfirmed = false,
                screenProbeInProgress = true,
                probeGraceElapsedMs = GameStartupHandoffPolicy.SCREEN_PROBE_COMPLETION_GRACE_MS - 1,
            ),
        )
        assertEquals(
            GameStartupHandoffPolicy.HandshakeTimeoutDecision.NO_PAUSE_NEEDED,
            GameStartupHandoffPolicy.onHandshakeTimeout(
                startupConfirmed = true,
                screenProbeInProgress = true,
                probeGraceElapsedMs = 0,
            ),
        )
        assertEquals(
            GameStartupHandoffPolicy.HandshakeTimeoutDecision.AUTOMATIC_PAUSE,
            GameStartupHandoffPolicy.onHandshakeTimeout(
                startupConfirmed = false,
                screenProbeInProgress = true,
                probeGraceElapsedMs = GameStartupHandoffPolicy.SCREEN_PROBE_COMPLETION_GRACE_MS,
            ),
        )
    }

    @Test
    fun `power log retry begins at thirty seconds`() {
        assertFalse(GameStartupHandoffPolicy.powerLogStallRetryDue(10_000L, 39_999L))
        assertTrue(GameStartupHandoffPolicy.powerLogStallRetryDue(10_000L, 40_000L))
    }
}
