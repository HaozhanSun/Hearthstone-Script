package club.xiaojiawei.hsscript.strategy.mode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreMatchRankGateTest {
    @Test
    fun `only a fresh exact rank five or ten dispatches queue input`() {
        var dispatches = 0
        for (observedRank in listOf(4, 5, 6, 9, 10, 11, 21, null)) {
            val result = PreMatchRankGate.evaluate(
                working = true,
                paused = false,
                mandatoryRankSurrenderPending = false,
                tournamentMode = true,
                inWar = false,
                observedRank = observedRank,
                freshRankObservation = true,
                ocrFailure = false,
            )
            val allowed = observedRank == 5 || observedRank == 10
            assertEquals(allowed, result.queueAuthorization.allowed, "rank=$observedRank")
            assertEquals(allowed, MatchmakingGuardPolicy.dispatchIfAuthorized(result.queueAuthorization) { dispatches++ })
        }
        assertEquals(2, dispatches, "only rank 5 and rank 10 reach the actual dispatch callback")
    }

    @Test
    fun `unknown OCR stale mode mismatch active game and runtime guards block queue input`() {
        var dispatches = 0
        val denied = listOf(
            PreMatchRankGate.evaluate(false, false, false, true, false, 5, true, false),
            PreMatchRankGate.evaluate(true, true, false, true, false, 5, true, false),
            PreMatchRankGate.evaluate(true, false, true, true, false, 5, true, false),
            PreMatchRankGate.evaluate(true, false, false, true, false, null, true, false),
            PreMatchRankGate.evaluate(true, false, false, true, false, 5, false, false),
            PreMatchRankGate.evaluate(true, false, false, true, false, null, false, true),
            PreMatchRankGate.evaluate(true, false, false, false, false, 5, true, false),
            PreMatchRankGate.evaluate(true, false, false, true, true, 5, true, false),
        )
        denied.forEach {
            assertFalse(it.queueAuthorization.allowed)
            assertFalse(MatchmakingGuardPolicy.dispatchIfAuthorized(it.queueAuthorization) { dispatches++ })
        }
        assertEquals(0, dispatches)
    }
}
