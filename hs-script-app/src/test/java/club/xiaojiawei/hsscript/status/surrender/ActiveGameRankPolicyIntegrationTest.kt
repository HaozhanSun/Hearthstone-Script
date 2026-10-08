package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.status.ActionDispatchGate
import club.xiaojiawei.hsscript.status.ScreenWatchdogKind
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
import club.xiaojiawei.hsscript.strategy.mode.PreMatchRankGate
import java.awt.Rectangle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Consumer-level contract for queue orchestration plus the current game's
 * Mulligan barrier. Queue eligibility is intentionally rank-neutral; only a
 * current-game read controls ordinary gameplay or mandatory surrender.
 */
class ActiveGameRankPolicyIntegrationTest {
    private val now = 80_000L

    @AfterEach
    fun resetState() {
        MulliganRankDispatchBarrier.resetForTest()
        MandatoryRankSurrenderGuard.resetForTest()
    }

    @Test
    fun `rank three dispatches queue then arms mandatory surrender and affirmative confirmation`() {
        assertEquals(1, dispatchQueue(), "rank must not be read before queue dispatch")

        val decision = activeRankDecision(3)
        assertFalse(decision.eligible)
        assertEquals("rank-not-5-or-10", decision.reason)

        val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
        assertFalse(MulliganRankDispatchBarrier.authorizeEligibleRank(ticket, 3))
        val surrenderCapability = requireNotNull(MulliganRankDispatchBarrier.requireSurrender(ticket))
        val recoveryCapability = MandatoryRankSurrenderGuard.begin("game-rank-3:self")

        assertFalse(
            ActionDispatchGate.allowForState(
                action = "strategy.turn-end",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
                rankBarrierState = MulliganRankDispatchBarrier.currentState(),
            ),
            "rank three must not release ordinary gameplay",
        )
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "surrender.request",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
                recoveryCapabilityValid = MandatoryRankSurrenderGuard.isRecoveryCapabilityValid(recoveryCapability),
                rankBarrierState = MulliganRankDispatchBarrier.currentState(),
                rankSurrenderRequestCapabilityValid = MulliganRankDispatchBarrier.isSurrenderCapabilityValid(surrenderCapability),
            ),
            "the current game's single-use mandatory surrender is the only allowed dispatch",
        )
        val dialog = MandatoryRankSurrenderRecoveryPolicy.decide(ScreenWatchdogKind.SURRENDER_CONFIRMATION)
        assertEquals(MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_CONFIRMATION, dialog.action)
        assertEquals(MandatoryRankSurrenderRecoveryPolicy.ConfirmationTarget.ACCEPT_NOW, dialog.confirmationTarget)
    }

    @Test
    fun `rank five and ten queue then release ordinary active-game actions`() {
        for (rank in listOf(5, 10)) {
            assertEquals(1, dispatchQueue(), "rank=$rank must not change queue dispatch")
            assertTrue(activeRankDecision(rank).eligible, "rank=$rank must authorize active gameplay")

            val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
            assertTrue(MulliganRankDispatchBarrier.authorizeEligibleRank(ticket, rank))
            assertTrue(
                ActionDispatchGate.allowForState(
                    action = "mulligan.confirm",
                    paused = false,
                    working = true,
                    rankBarrierState = MulliganRankDispatchBarrier.currentState(),
                ),
                "rank=$rank should release ordinary Mulligan input",
            )
            MulliganRankDispatchBarrier.resetForTest()
        }
    }

    @Test
    fun `unknown PaddleX failure and cancellation queue but keep active game blocked`() {
        for (failure in listOf("unknown", "PaddleX failure", "PaddleX cancellation")) {
            assertEquals(1, dispatchQueue(), "$failure must not block queue")
            val decision = RankEligibilityPolicy.evaluate(
                detection = null,
                expectedMode = "GAMEPLAY",
                actualMode = "GAMEPLAY",
                expectedInWar = true,
                inWar = true,
                nowMs = now,
            )
            assertFalse(decision.eligible, "$failure must not authorize active gameplay")
            assertEquals("rank-evidence-missing", decision.reason)

            MulliganRankDispatchBarrier.beginCurrentGame()
            assertFalse(
                ActionDispatchGate.allowForState(
                    action = "strategy.play-card",
                    paused = false,
                    working = true,
                    rankBarrierState = MulliganRankDispatchBarrier.currentState(),
                ),
                "$failure must retain the active-game no-ordinary-input barrier",
            )
            MulliganRankDispatchBarrier.resetForTest()
        }
    }

    private fun dispatchQueue(): Int {
        val queue = PreMatchRankGate.evaluate(
            working = true,
            paused = false,
            mandatoryRankSurrenderPending = false,
        )
        var dispatches = 0
        assertTrue(queue.queueAuthorization.allowed)
        assertTrue(MatchmakingGuardPolicy.dispatchIfAuthorized(queue.queueAuthorization) { dispatches++ })
        return dispatches
    }

    private fun activeRankDecision(rank: Int) = RankEligibilityPolicy.evaluate(
        detection = CurrentRankDetector.Detection(
            rank = rank,
            tier = CurrentRankDetector.RankTier.GOLD,
            ocrText = rank.toString(),
            confidence = 0.99,
            captureBounds = Rectangle(0, 0, 105, 108),
            provider = "PADDLEX",
            capturedAtMs = now,
            agreementCount = 1,
        ),
        expectedMode = "GAMEPLAY",
        actualMode = "GAMEPLAY",
        expectedInWar = true,
        inWar = true,
        nowMs = now,
    )
}
