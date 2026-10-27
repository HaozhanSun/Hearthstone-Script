package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.status.surrender.CurrentGameSurrenderTerminalEvidence
import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalCleanupTaskPromotionPolicyTest {
    @Test
    fun `late current proof promotes legacy E2E task while stale proof and non E2E task do not`() {
        MandatoryRankSurrenderGuard.resetForTest()
        try {
            MandatoryRankSurrenderGuard.begin("game-1")
            val evidence = CurrentGameSurrenderTerminalEvidence(
                gameIdentity = "game-1",
                ownEntityId = "self",
                opponentEntityId = "opponent",
                ownPlayState = "CONCEDED",
                opponentPlayState = "WON",
                finalGameOver = true,
                complete = true,
            )
            val terminal = requireNotNull(MandatoryRankSurrenderGuard.authorizeTerminalCleanup(evidence))
            val lateCapability = MandatoryRankSurrenderGuard.existingTerminalCleanupCapability()
            assertEquals(terminal, lateCapability)
            assertEquals(terminal, TerminalCleanupTaskPromotionPolicy.capabilityForTick(true, lateCapability))
            assertEquals(null, TerminalCleanupTaskPromotionPolicy.capabilityForTick(false, terminal))

            MandatoryRankSurrenderGuard.resetForTest()
            assertEquals(null, TerminalCleanupTaskPromotionPolicy.capabilityForTick(true, terminal))
        } finally {
            MandatoryRankSurrenderGuard.resetForTest()
        }
    }
}
