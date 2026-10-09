package club.xiaojiawei.hsscript.strategy.mode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PreMatchRankGateTest {
    private val now = 1_000_000L

    @Test
    fun `only exact fresh rank five or ten can issue a pre-match permit`() {
        for (rank in listOf(4, 6, 9, 11, 21, null)) {
            val gate = PreMatchRankGate.evaluate(evidence(rank = rank), now)
            assertFalse(gate.queueAuthorization.allowed, "rank=$rank")
            assertEquals(0, dispatches(gate, now), "rank=$rank must never send input")
        }
        for (rank in listOf(5, 10)) {
            val gate = PreMatchRankGate.evaluate(evidence(rank = rank), now)
            assertTrue(gate.queueAuthorization.allowed, "rank=$rank")
            assertEquals(1, dispatches(gate, now), "rank=$rank sends exactly one first queue input")
        }
    }

    @Test
    fun `unknown low confidence failed cancelled stale and phase or mode mismatch fail closed`() {
        val cases = listOf(
            evidence(rank = null),
            evidence(rank = 5, confidence = 0.94),
            evidence(rank = 5, outcome = PreMatchRankGate.OcrOutcome.FAILURE),
            evidence(rank = 5, outcome = PreMatchRankGate.OcrOutcome.CANCELLED),
            evidence(rank = 5, capturedAtMs = now - PreMatchRankGate.MAX_OBSERVATION_AGE_MS - 1),
            evidence(rank = 5, phase = "REPLACE_CARD"),
            evidence(rank = 5, tournamentMode = false),
            evidence(rank = 5, inWar = true),
            evidence(rank = 5, working = false),
            evidence(rank = 5, paused = true),
            evidence(rank = 5, mandatoryRankSurrenderPending = true),
        )
        cases.forEach { input ->
            val gate = PreMatchRankGate.evaluate(input, now)
            assertFalse(gate.queueAuthorization.allowed, "input=$input")
            assertEquals(0, dispatches(gate, now), "input=$input")
        }
    }

    @Test
    fun `permit is atomic single use and rechecks freshness and runtime at dispatch`() {
        val gate = PreMatchRankGate.evaluate(evidence(rank = 5), now)
        val permit = assertNotNull(gate.permit)
        var dispatches = 0
        assertTrue(permit.dispatchIfCurrent(runtime(), now) { dispatches++ })
        assertFalse(permit.dispatchIfCurrent(runtime(), now) { dispatches++ })
        assertEquals(1, dispatches)

        val stalePermit = assertNotNull(PreMatchRankGate.evaluate(evidence(rank = 10), now).permit)
        assertFalse(stalePermit.dispatchIfCurrent(runtime(), now + PreMatchRankGate.MAX_OBSERVATION_AGE_MS + 1) { dispatches++ })
        val pausedPermit = assertNotNull(PreMatchRankGate.evaluate(evidence(rank = 10), now).permit)
        assertFalse(pausedPermit.dispatchIfCurrent(runtime(paused = true), now) { dispatches++ })
        assertEquals(1, dispatches)
    }

    private fun dispatches(gate: PreMatchRankGate.Result, at: Long): Int {
        var dispatches = 0
        gate.permit?.dispatchIfCurrent(runtime(), at) { dispatches++ }
        return dispatches
    }

    private fun evidence(
        rank: Int?,
        confidence: Double? = 0.99,
        outcome: PreMatchRankGate.OcrOutcome = if (rank == null) PreMatchRankGate.OcrOutcome.UNKNOWN else PreMatchRankGate.OcrOutcome.SUCCESS,
        capturedAtMs: Long = now,
        phase: String = PreMatchRankGate.REQUIRED_PHASE,
        working: Boolean = true,
        paused: Boolean = false,
        mandatoryRankSurrenderPending: Boolean = false,
        tournamentMode: Boolean = true,
        inWar: Boolean = false,
    ) = PreMatchRankGate.Evidence(
        working, paused, mandatoryRankSurrenderPending, tournamentMode, inWar,
        phase, outcome, rank, confidence, capturedAtMs,
    )

    private fun runtime(paused: Boolean = false) = PreMatchRankGate.RuntimeEvidence(
        working = true,
        paused = paused,
        mandatoryRankSurrenderPending = false,
        tournamentMode = true,
        inWar = false,
    )
}
