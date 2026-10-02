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
    fun `a startup input dispatch without a game process and window is not an accepted handoff`() {
        // Posting an input / logging a launch attempt is not target acceptance.
        val afterDispatch = GameStartupHandoffPolicy.observe(
            GameStartupHandoffPolicy.State(),
            processAlive = false,
            windowFound = false,
            nowMs = 1_000L,
        )
        assertEquals(GameStartupHandoffPolicy.Decision.WAIT, afterDispatch.decision)

        val firstLiveObservation = GameStartupHandoffPolicy.observe(
            afterDispatch.state,
            processAlive = true,
            windowFound = true,
            nowMs = 2_000L,
        )
        assertEquals(GameStartupHandoffPolicy.Decision.WAIT, firstLiveObservation.decision)
        val confirmed = GameStartupHandoffPolicy.observe(
            firstLiveObservation.state,
            processAlive = true,
            windowFound = true,
            nowMs = 2_100L,
        )
        assertEquals(GameStartupHandoffPolicy.Decision.HANDOFF, confirmed.decision)
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
    fun `handshake timeout keeps monitoring without pausing for either known or unknown startup`() {
        assertEquals(
            GameStartupHandoffPolicy.HandshakeTimeoutDecision.CONTINUE_MONITORING,
            GameStartupHandoffPolicy.onHandshakeTimeout(
                GameStartupHandoffPolicy.startupHandshakeConfirmed(false, ModeEnum.HUB),
            ),
        )
        assertEquals(
            GameStartupHandoffPolicy.HandshakeTimeoutDecision.CONTINUE_MONITORING,
            GameStartupHandoffPolicy.onHandshakeTimeout(
                GameStartupHandoffPolicy.startupHandshakeConfirmed(false, null),
            ),
        )
        assertEquals(60_000L, GameStartupHandoffPolicy.handshakeTimeoutRecheckDelayMs())
    }

    @Test
    fun `power log retry begins at thirty seconds`() {
        assertFalse(GameStartupHandoffPolicy.powerLogStallRetryDue(10_000L, 39_999L))
        assertTrue(GameStartupHandoffPolicy.powerLogStallRetryDue(10_000L, 40_000L))
    }
}
