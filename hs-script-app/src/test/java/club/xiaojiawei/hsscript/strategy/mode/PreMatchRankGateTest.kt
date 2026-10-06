package club.xiaojiawei.hsscript.strategy.mode

import club.xiaojiawei.hsscript.ocr.PaddleXOcrCancelledException
import club.xiaojiawei.hsscript.ocr.PaddleXOcrException
import club.xiaojiawei.hsscript.status.surrender.CurrentRankDetector
import java.awt.Rectangle
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreMatchRankGateTest {
    private val now = 100_000L

    @Test
    fun `only fresh exact rank five or ten dispatches queue input`() {
        var dispatches = 0
        val denied = buildList {
            (1..4).forEach { add(detection(it)) }
            (6..9).forEach { add(detection(it)) }
            (11..20).forEach { add(detection(it)) }
            listOf(21, 233, 5220).forEach { add(detection(it)) }
            add(null)
            add(detection(null, agreementCount = 0)) // unresolved/conflicting OCR
            add(detection(5, confidence = 0.89))
            add(detection(5, capturedAtMs = now - 10_001))
            add(detection(5, capturedAtMs = now + 1))
            add(detection(5, bounds = Rectangle()))
            add(detection(5, provider = "UNKNOWN"))
            add(detection(5, provider = "LEGACY", confidence = null, agreementCount = 1))
        }

        denied.forEach { evidence ->
            val result = authorize { evidence }
            assertFalse(result.queueAuthorization.allowed, "evidence=$evidence decision=${result.rankDecision}")
            assertFalse(
                MatchmakingGuardPolicy.dispatchIfAuthorized(result.queueAuthorization) { dispatches++ },
                "denied evidence must not reach queue input: $evidence",
            )
        }
        assertEquals(0, dispatches)

        for (rank in listOf(5, 10)) {
            val result = authorize { detection(rank) }
            assertTrue(result.queueAuthorization.allowed, "rank=$rank reason=${result.queueAuthorization.reason}")
            assertTrue(MatchmakingGuardPolicy.dispatchIfAuthorized(result.queueAuthorization) { dispatches++ })
        }
        assertEquals(2, dispatches, "only exact 5 and 10 may reach the input callback")
    }

    @Test
    fun `PaddleX timeout cancellation and translated errors fail closed before dispatch`() {
        val failures = listOf<Exception>(
            TimeoutException("rank OCR timed out"),
            PaddleXOcrCancelledException("rank request cancelled"),
            PaddleXOcrException("translated OCR sidecar error"),
        )
        var dispatches = 0
        for (failure in failures) {
            val result = authorize { throw failure }
            assertNull(result.detection, failure.javaClass.simpleName)
            assertEquals(failure, result.captureFailure)
            assertFalse(result.rankDecision.eligible, failure.javaClass.simpleName)
            assertFalse(result.queueAuthorization.allowed, failure.javaClass.simpleName)
            assertFalse(
                MatchmakingGuardPolicy.dispatchIfAuthorized(result.queueAuthorization) { dispatches++ },
                failure.javaClass.simpleName,
            )
        }
        assertEquals(0, dispatches)
    }

    @Test
    fun `freshness clock is sampled after the detector returns`() {
        var capturedAt = now
        val result = PreMatchRankGate.evaluate(
            working = true,
            paused = false,
            mandatoryRankSurrenderPending = false,
            expectedMode = "TOURNAMENT",
            actualMode = "TOURNAMENT",
            expectedInWar = false,
            inWar = false,
            nowMs = { capturedAt },
            detectFreshRank = {
                capturedAt += 25
                detection(5, capturedAtMs = capturedAt)
            },
        )
        assertTrue(result.rankDecision.eligible, result.rankDecision.reason)
        assertTrue(result.queueAuthorization.allowed, result.queueAuthorization.reason)
    }

    @Test
    fun `mode mismatch war mismatch and inactive runtime deny without dispatch`() {
        var dispatches = 0
        val validFive = { detection(5) }
        val mismatches = listOf(
            authorize(actualMode = "HUB", detect = validFive),
            authorize(inWar = true, detect = validFive),
        )
        mismatches.forEach { result ->
            assertFalse(result.queueAuthorization.allowed)
            assertFalse(MatchmakingGuardPolicy.dispatchIfAuthorized(result.queueAuthorization) { dispatches++ })
        }

        var detectorCalls = 0
        val stopped = PreMatchRankGate.evaluate(
            working = false,
            paused = false,
            mandatoryRankSurrenderPending = false,
            expectedMode = "TOURNAMENT",
            actualMode = "TOURNAMENT",
            expectedInWar = false,
            inWar = false,
            nowMs = { now },
            detectFreshRank = { detectorCalls++; detection(5) },
        )
        assertEquals(0, detectorCalls, "do not spend OCR or reuse evidence when runtime cannot queue")
        assertFalse(stopped.queueAuthorization.allowed)
        assertFalse(MatchmakingGuardPolicy.dispatchIfAuthorized(stopped.queueAuthorization) { dispatches++ })
        assertEquals(0, dispatches)
    }

    private fun authorize(
        actualMode: String? = "TOURNAMENT",
        inWar: Boolean = false,
        detect: () -> CurrentRankDetector.Detection?,
    ) = PreMatchRankGate.evaluate(
        working = true,
        paused = false,
        mandatoryRankSurrenderPending = false,
        expectedMode = "TOURNAMENT",
        actualMode = actualMode,
        expectedInWar = false,
        inWar = inWar,
        nowMs = { now },
        detectFreshRank = detect,
    )

    private fun detection(
        rank: Int?,
        provider: String = "PADDLEX",
        confidence: Double? = 0.99,
        agreementCount: Int = 1,
        capturedAtMs: Long = now,
        bounds: Rectangle = Rectangle(5, 10, 20, 30),
    ) = CurrentRankDetector.Detection(
        rank = rank,
        tier = CurrentRankDetector.RankTier.UNKNOWN,
        ocrText = rank?.toString().orEmpty(),
        confidence = confidence,
        captureBounds = bounds,
        provider = provider,
        capturedAtMs = capturedAtMs,
        agreementCount = agreementCount,
    )
}
