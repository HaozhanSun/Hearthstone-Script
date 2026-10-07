package club.xiaojiawei.hsscript.strategy.mode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreMatchRankGateTest {
    @Test
    fun `queue dispatch is independent of rank and deferred to active match`() {
        // The old pre-match detector rejected rank 4 (and UNKNOWN) before the
        // game could start. Rank policy now runs behind the live Mulligan gate.
        var dispatches = 0
        for (observedRank in listOf(4, 5, 7, 10, 21, null)) {
            val result = PreMatchRankGate.evaluate(
                working = true,
                paused = false,
                mandatoryRankSurrenderPending = false,
            )
            assertTrue(result.queueAuthorization.allowed, "rank=$observedRank")
            assertTrue(MatchmakingGuardPolicy.dispatchIfAuthorized(result.queueAuthorization) { dispatches++ })
        }
        assertEquals(6, dispatches, "each authorized queue request reaches the actual dispatch callback")
    }

    @Test
    fun `runtime pause and pending mandatory surrender still block queue input`() {
        var dispatches = 0
        val denied = listOf(
            PreMatchRankGate.evaluate(false, false, false),
            PreMatchRankGate.evaluate(true, true, false),
            PreMatchRankGate.evaluate(true, false, true),
        )
        denied.forEach {
            assertFalse(it.queueAuthorization.allowed)
            assertFalse(MatchmakingGuardPolicy.dispatchIfAuthorized(it.queueAuthorization) { dispatches++ })
        }
        assertEquals(0, dispatches)
    }
}
