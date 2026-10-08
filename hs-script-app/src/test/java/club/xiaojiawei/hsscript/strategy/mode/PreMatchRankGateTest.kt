package club.xiaojiawei.hsscript.strategy.mode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreMatchRankGateTest {
    @Test
    fun `rank is deliberately neutral at matchmaking and dispatches once`() {
        var dispatches = 0
        for (observedRank in listOf(4, 5, 6, 9, 10, 11, 21, null)) {
            val result = PreMatchRankGate.evaluate(
                working = true,
                paused = false,
                mandatoryRankSurrenderPending = false,
            )
            assertTrue(result.queueAuthorization.allowed, "rank=$observedRank must not block queue")
            assertTrue(MatchmakingGuardPolicy.dispatchIfAuthorized(result.queueAuthorization) { dispatches++ })
        }
        assertEquals(8, dispatches, "each queue request reaches the actual dispatch callback")
    }

    @Test
    fun `only runtime pause and pending mandatory surrender block queue input`() {
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
