package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
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
    fun `all resolved numeric ranks other than five ten and legendary above twenty are denied`() {
        for (rank in (1..4) + (6..9) + (11..20)) {
            val decision = evaluate(detection(rank = rank))
            assertFalse(decision.eligible, "rank=$rank must fail closed")
        }
    }

    @Test
    fun `queue dispatch is rank neutral while evidence validation remains in match`() {
        var dispatchCount = 0
        val evidence = listOf(null, detection(4), detection(5), detection(21), detection(5220))
        for (rankEvidence in evidence) {
            val queue = MatchmakingGuardPolicy.authorizeQueueInput(true, false, false)
            assertTrue(
                MatchmakingGuardPolicy.dispatchIfAuthorized(queue) { dispatchCount++ },
                "pre-match rank=$rankEvidence must be deferred until in-game evidence",
            )
        }
        assertEquals(evidence.size, dispatchCount)
    }

    @Test
    fun `rank three progression reward never authorizes ordinary play`() {
        val decision = evaluate(detection(rank = 3, tier = CurrentRankDetector.RankTier.GOLD))
        assertFalse(decision.eligible, "post-surrender Gold 3 screen is cleanup evidence, not play authorization")
        assertEquals("rank-not-5-or-10-or-legendary-20-plus", decision.reason)
    }

    @Test
    fun `numeric legendary rating above twenty is allowed independent of tier label`() {
        for (tier in CurrentRankDetector.RankTier.values()) {
            val decision = evaluate(detection(rank = 21, tier = tier))
            assertTrue(decision.eligible, "rank=21 tier=$tier reason=${decision.reason}")
        }
        assertTrue(evaluate(detection(rank = 233, tier = CurrentRankDetector.RankTier.UNKNOWN)).eligible)
        assertTrue(evaluate(detection(rank = 5220, tier = CurrentRankDetector.RankTier.LEGEND)).eligible)
        assertFalse(evaluate(detection(rank = null, tier = CurrentRankDetector.RankTier.LEGEND)).eligible)
    }

    @Test
    fun `unresolved low confidence and visual-only ten hint cannot authorize`() {
        assertFalse(evaluate(null).eligible)
        assertFalse(evaluate(detection(rank = null)).eligible)
        assertTrue(evaluate(detection(rank = 5, confidence = 0.90)).eligible)
        assertFalse(evaluate(detection(rank = 5, confidence = 0.89)).eligible)
        assertFalse(evaluate(detection(rank = 5, confidence = null)).eligible)
        assertFalse(evaluate(detection(rank = 5, confidence = 0.89)).eligible)
        assertFalse(evaluate(detection(rank = 5, provider = "UNKNOWN")).eligible)
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
    fun `translated PaddleX failures and cancellation are unresolved in game and do not authorize play`() {
        // Provider failure, timeout, and cancellation are normalized by the
        // queue boundary to absent evidence; no cached rank may be substituted.
        for (failure in listOf("PaddleXOcrException", "PaddleXTimeout", "PaddleXCancelledException")) {
            val rankDecision = evaluate(null)
            assertFalse(rankDecision.eligible, failure)
            val queue = MatchmakingGuardPolicy.authorizeQueueInput(true, false, false)
            assertTrue(MatchmakingGuardPolicy.dispatchIfAuthorized(queue) {}, "$failure must not gate queue")
        }
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
    fun `streak protection cannot change rank policy and matchmaking uses runtime gate only`() {
        val surrenderStreak = SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 7, consecutiveWins = 0),
        )!!
        assertTrue(surrenderStreak.blocksAutomaticSurrender)
        for (rank in listOf(4, 7, 11, 20)) {
            val decision = evaluate(detection(rank = rank))
            assertFalse(decision.eligible, "streak guard must not allow rank=$rank")
        }

        val winStreak = SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 0, consecutiveWins = 5),
        )!!
        assertTrue(winStreak.shouldSurrender)
        assertFalse(evaluate(detection(rank = 7)).eligible)
        val rankTen = evaluate(detection(rank = 10))
        assertTrue(rankTen.eligible)
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(working = true, paused = false))
        assertFalse(MatchmakingGuardPolicy.runtimeAllowsInput(working = true, paused = true))
        assertFalse(MatchmakingGuardPolicy.runtimeAllowsInput(working = false, paused = false))
        val numericLegend = evaluate(detection(rank = 233, tier = CurrentRankDetector.RankTier.UNKNOWN))
        assertTrue(numericLegend.eligible, "numeric Legendary rating above 20 is allowed")
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
        rankOverride: Int? = rank,
    ) = CurrentRankDetector.Detection(
        rank = rankOverride,
        tier = tier,
        ocrText = rank?.toString().orEmpty(),
        confidence = confidence,
        captureBounds = bounds,
        provider = provider,
        capturedAtMs = capturedAtMs,
        agreementCount = agreementCount,
    )

}
