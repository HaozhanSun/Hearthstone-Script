package club.xiaojiawei.hsscript.status.surrender

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class MandatorySurrenderTerminalEvidenceTest {
    @AfterEach
    fun cleanup() = MandatoryRankSurrenderGuard.resetForTest()

    @Test
    fun `only same game own surrender and opponent win with final complete authorizes`() {
        val valid = evidence()
        assertTrue(MandatorySurrenderTerminalEvidence.authorizes("game-7:self", valid))
        assertFalse(MandatorySurrenderTerminalEvidence.authorizes("older-game:self", valid))
        assertFalse(MandatorySurrenderTerminalEvidence.authorizes(null, valid))
        assertFalse(MandatorySurrenderTerminalEvidence.authorizes("game-7:self", null))
        assertFalse(
            MandatorySurrenderTerminalEvidence.authorizes(
                "game-7:self",
                valid.copy(ownPlayState = "WON"),
            ),
            "our own win must never be reinterpreted as accepted surrender",
        )
        assertFalse(
            MandatorySurrenderTerminalEvidence.authorizes(
                "game-7:self",
                valid.copy(opponentPlayState = "LOST"),
            ),
        )
        assertFalse(MandatorySurrenderTerminalEvidence.authorizes("game-7:self", valid.copy(finalGameOver = false)))
        assertFalse(MandatorySurrenderTerminalEvidence.authorizes("game-7:self", valid.copy(complete = false)))
    }

    @Test
    fun `legacy click and screen labels are not terminal evidence`() {
        val requested = MandatoryRankSurrenderGuard.begin("game-7:self")
        assertTrue(MandatoryRankSurrenderGuard.isRecoveryCapabilityValid(requested))
        assertFalse(MandatorySurrenderTerminalEvidence.authorizes("game-7:self", null))
        assertFalse(MandatoryRankSurrenderGuard.authorizeTerminalCleanup(null) != null)
        assertFalse(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_MAIN_MENU"))
        assertTrue(MandatoryRankSurrenderGuard.isPending())

        val cleanup = MandatoryRankSurrenderGuard.authorizeTerminalCleanup(evidence())
        assertTrue(cleanup != null)
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_MAIN_MENU", cleanup))
        assertFalse(MandatoryRankSurrenderGuard.isPending())
    }

    private fun evidence() = CurrentGameSurrenderTerminalEvidence(
        gameIdentity = "game-7:self",
        ownEntityId = "self",
        opponentEntityId = "opponent",
        ownPlayState = "CONCEDED",
        opponentPlayState = "WON",
        finalGameOver = true,
        complete = true,
    )
}
