package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.status.surrender.CurrentRankDetector
import club.xiaojiawei.hsscript.status.surrender.PersistentStreakSnapshot
import club.xiaojiawei.hsscript.status.surrender.SurrenderPolicy
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import java.awt.Rectangle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameUtilSurrenderGuardTest {

    @Test
    fun rejectsUnknownModeWithoutActiveWar() {
        assertFalse(GameUtil.isSurrenderStateConfirmed(null, false))
        assertFalse(GameUtil.isSurrenderStateConfirmed(ModeEnum.HUB, false))
    }

    @Test
    fun allowsGameplayOrActiveWarForPreMulliganSurrender() {
        assertTrue(GameUtil.isSurrenderStateConfirmed(ModeEnum.GAMEPLAY, false))
        assertTrue(GameUtil.isSurrenderStateConfirmed(null, true))
    }

    @Test
    fun verifiedEligibleRankBlocksEveryGenericSurrenderAtTheSharedExecutor() {
        val now = System.currentTimeMillis()
        SurrenderPolicy.resetForNewGame()
        WarEx.surrenderRequested = false
        WarEx.surrenderReason = null
        try {
            val detection = CurrentRankDetector.Detection(
                rank = 10,
                tier = CurrentRankDetector.RankTier.UNKNOWN,
                ocrText = "10",
                confidence = 1.0,
                captureBounds = Rectangle(10, 20, 80, 90),
                provider = "PADDLEX",
                capturedAtMs = now,
                agreementCount = 1,
            )
            val winningStreakDecision = SurrenderPolicy.persistentStreakDecision(
                PersistentStreakSnapshot(consecutiveSurrenders = 0, consecutiveWins = 5),
            )!!
            val winRateDecision = SurrenderPolicy.evaluateWinRate(
                SurrenderPolicy.WinRateSnapshot(games = 20, wins = 12),
            )!!

            assertNull(
                SurrenderPolicy.evaluateMulliganRankEvidence(
                    detection = detection,
                    actualMode = "GAMEPLAY",
                    inWar = true,
                    nowMs = now,
                    persistentStreakDecision = winningStreakDecision,
                    winRateDecisionProvider = { winRateDecision },
                ),
            )
            assertTrue(SurrenderPolicy.currentRankContinueAuthorized())

            val genericRules = listOf(
                "opponent-health-is-40 source=Power.log",
                "rival-hero-is-not-original-class-hero",
                "opponent-played-card-demon-seed",
                "consecutive-wins-over-five",
                "win-rate-at-least-45-percent",
                "deck-strategy-surrender",
                "unspecified",
            )
            for (reason in genericRules) {
                assertEquals("verified-rank-eligibility", SurrenderPolicy.surrenderDispatchBlockReason(false), reason)
                assertFalse(GameUtil.surrender(reason = reason), "must block generic rule: $reason")
                assertFalse(WarEx.surrenderRequested, "blocked request must not mark a surrender: $reason")
            }
        } finally {
            SurrenderPolicy.resetForNewGame()
            WarEx.surrenderRequested = false
            WarEx.surrenderReason = null
        }
    }

    @Test
    fun genericSurrenderWaitsForRankResolutionButMandatoryRankDecisionCanDispatch() {
        SurrenderPolicy.resetForNewGame()
        assertEquals("rank-eligibility-not-resolved", SurrenderPolicy.surrenderDispatchBlockReason(false))

        SurrenderPolicy.forceRankInspectionLatchForTest(completed = true, authorized = false, attempts = 3)
        assertNull(SurrenderPolicy.surrenderDispatchBlockReason(mandatoryRank = true))
        assertNull(SurrenderPolicy.surrenderDispatchBlockReason(mandatoryRank = false))
        SurrenderPolicy.resetForNewGame()
    }
}
