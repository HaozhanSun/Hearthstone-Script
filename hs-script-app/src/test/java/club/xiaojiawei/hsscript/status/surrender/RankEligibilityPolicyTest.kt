package club.xiaojiawei.hsscript.status.surrender

import java.awt.Rectangle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RankEligibilityPolicyTest {
    private val now = 50_000L

    @Test
    fun `exact five and ten authorize despite secondary tier labels`() {
        for (rank in listOf(5, 10)) {
            for (tier in listOf(
                CurrentRankDetector.RankTier.SILVER,
                CurrentRankDetector.RankTier.GOLD,
                CurrentRankDetector.RankTier.PLATINUM,
                CurrentRankDetector.RankTier.UNKNOWN,
                // Regression fixture: the pre-match crop's red backdrop can
                // fool the visual-only Legendary signature despite OCR=10.
                CurrentRankDetector.RankTier.LEGEND,
            )) {
                val decision = evaluate(detection(rank = rank, tier = tier))
                assertTrue(decision.eligible, "rank=$rank tier=$tier reason=${decision.reason}")
            }
        }
    }

    @Test
    fun `ranks one through four six through nine and eleven through twenty are denied`() {
        for (rank in (1..4) + (6..9) + (11..20)) {
            val decision = evaluate(detection(rank = rank))
            assertFalse(decision.eligible, "rank=$rank must fail closed")
        }
    }

    @Test
    fun `numeric rating above twenty requires Legend tier confirmation`() {
        assertTrue(evaluate(detection(rank = 21, tier = CurrentRankDetector.RankTier.LEGEND)).eligible)
        assertTrue(evaluate(detection(rank = 233, tier = CurrentRankDetector.RankTier.LEGEND)).eligible)
        assertFalse(evaluate(detection(rank = 21, tier = CurrentRankDetector.RankTier.UNKNOWN)).eligible)
        assertFalse(evaluate(detection(rank = 21, tier = CurrentRankDetector.RankTier.GOLD)).eligible)
        assertFalse(evaluate(detection(rank = null, tier = CurrentRankDetector.RankTier.LEGEND)).eligible)
    }

    @Test
    fun `unresolved low confidence and visual-only ten hint cannot authorize`() {
        assertFalse(evaluate(null).eligible)
        assertFalse(evaluate(detection(rank = null)).eligible)
        assertTrue(evaluate(detection(rank = 5, confidence = 0.90)).eligible)
        assertFalse(evaluate(detection(rank = 5, confidence = 0.89)).eligible)
        assertFalse(evaluate(detection(rank = 5, confidence = null)).eligible)
        assertEquals(
            "rank-number-not-read-by-ocr",
            evaluate(detection(rank = 10, agreementCount = 0)).reason,
        )
    }

    @Test
    fun `legacy requires repeated identical numeric evidence`() {
        assertTrue(evaluate(detection(rank = 10, provider = "LEGACY", confidence = null, agreementCount = 2)).eligible)
        assertFalse(evaluate(detection(rank = 10, provider = "LEGACY", confidence = null, agreementCount = 1)).eligible)
    }

    @Test
    fun `translated PaddleX failures and cancellation are denied as missing evidence`() {
        // Detector/provider exceptions are normalized to absent evidence unless
        // cancellation propagates; neither path can reuse a prior rank value.
        assertEquals("rank-evidence-missing", evaluate(null).reason)
        assertEquals("rank-unresolved", evaluate(detection(rank = null)).reason)
        assertFalse(evaluate(detection(rank = 5, provider = "UNKNOWN")).eligible)
    }

    @Test
    fun `missing badge stale cached capture and future timestamp fail closed`() {
        assertEquals(
            "rank-capture-invalid",
            evaluate(detection(rank = 5, bounds = Rectangle())).reason,
        )
        assertEquals("rank-evidence-stale", evaluate(detection(rank = 5, capturedAtMs = 1L)).reason)
        assertEquals("rank-evidence-stale", evaluate(detection(rank = 5, capturedAtMs = now + 1)).reason)
    }

    @Test
    fun `mode or war-state mismatch cannot reuse valid rank evidence`() {
        assertEquals("mode-mismatch", evaluate(detection(rank = 5), actualMode = "HUB").reason)
        assertEquals("war-state-mismatch", evaluate(detection(rank = 5), inWar = true).reason)
    }

    @Test
    fun `surrender streak protection cannot grant rank eligibility`() {
        val surrenderStreak = SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 7, consecutiveWins = 0),
        )!!
        assertTrue(surrenderStreak.blocksAutomaticSurrender)
        for (rank in listOf(4, 7, 11, 233)) {
            val decision = evaluate(detection(rank = rank))
            assertFalse(decision.eligible, "streak guard must not allow rank=$rank")
            assertFalse(RankEligibilityPolicy.shouldDispatchMatchmaking(decision, working = true, paused = false))
        }

        val winStreak = SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 0, consecutiveWins = 5),
        )!!
        assertTrue(winStreak.shouldSurrender)
        assertFalse(evaluate(detection(rank = 7)).eligible)
        val rankTen = evaluate(detection(rank = 10))
        assertTrue(rankTen.eligible)
        assertTrue(RankEligibilityPolicy.shouldDispatchMatchmaking(rankTen, working = true, paused = false))
        assertFalse(RankEligibilityPolicy.shouldDispatchMatchmaking(rankTen, working = true, paused = true))
    }

    private fun evaluate(
        detection: CurrentRankDetector.Detection?,
        actualMode: String = "TOURNAMENT",
        inWar: Boolean = false,
    ) = RankEligibilityPolicy.evaluate(
        detection = detection,
        expectedMode = "TOURNAMENT",
        actualMode = actualMode,
        expectedInWar = false,
        inWar = inWar,
        nowMs = now,
    )

    private fun detection(
        rank: Int?,
        tier: CurrentRankDetector.RankTier = CurrentRankDetector.RankTier.SILVER,
        provider: String = "PADDLEX",
        confidence: Double? = 0.99,
        agreementCount: Int = 1,
        capturedAtMs: Long = now,
        bounds: Rectangle = Rectangle(10, 20, 80, 90),
    ) = CurrentRankDetector.Detection(
        rank = rank,
        tier = tier,
        ocrText = rank?.toString().orEmpty(),
        confidence = confidence,
        captureBounds = bounds,
        provider = provider,
        capturedAtMs = capturedAtMs,
        agreementCount = agreementCount,
    )
}
