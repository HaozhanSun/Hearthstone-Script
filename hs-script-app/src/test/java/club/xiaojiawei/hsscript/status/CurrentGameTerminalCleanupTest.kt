package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.status.surrender.CurrentGameResultCleanupEvidence
import club.xiaojiawei.hsscript.status.surrender.CurrentGameSurrenderTerminalEvidence
import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurrentGameTerminalCleanupTest {
    @AfterTest
    fun resetGuards() {
        MandatoryRankSurrenderGuard.resetForTest()
        MulliganRankDispatchBarrier.resetForTest()
    }

    @Test
    fun `complete current-game win releases rank preflight only into terminal cleanup`() {
        val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "recovery.left",
                paused = false,
                working = true,
                rankBarrierState = MulliganRankDispatchBarrier.State.PENDING,
            ),
            "rank barrier blocks ordinary recovery before terminal proof",
        )

        val evidence = terminalEvidence(ownState = "WON", opponentState = "CONCEDED")
        assertTrue(CurrentGameResultCleanupEvidence.authorizes(evidence))
        val cleanup = MandatoryRankSurrenderGuard.authorizeCurrentGameResultCleanup(evidence)
        assertNotNull(cleanup)
        assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
        assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())

        assertFalse(
            ActionDispatchGate.allowForState(
                action = "strategy.card.play",
                paused = false,
                working = true,
                terminalCleanupPending = true,
                rankBarrierState = MulliganRankDispatchBarrier.State.IDLE,
            ),
            "terminal proof does not reopen ordinary gameplay input",
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "recovery.left",
                paused = false,
                working = true,
                terminalCleanupPending = true,
                rankBarrierState = MulliganRankDispatchBarrier.State.IDLE,
            ),
            "generic recovery input cannot use the terminal exception",
        )
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "terminal-result.dismiss",
                paused = false,
                working = true,
                terminalCleanupPending = true,
                terminalCleanupCapabilityValid = true,
                rankBarrierState = MulliganRankDispatchBarrier.State.IDLE,
            ),
            "fresh result-page cleanup remains the only authorized input",
        )
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED", cleanup))
        assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        assertTrue(ticket > 0L)
    }

    @Test
    fun `incomplete or contradictory result proof keeps rank barrier closed`() {
        val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
        val incomplete = terminalEvidence(ownState = "WON", opponentState = "CONCEDED", complete = false)
        assertFalse(CurrentGameResultCleanupEvidence.authorizes(incomplete))
        assertNull(MandatoryRankSurrenderGuard.authorizeCurrentGameResultCleanup(incomplete))
        assertEquals(MulliganRankDispatchBarrier.State.PENDING, MulliganRankDispatchBarrier.currentState())

        val contradictory = terminalEvidence(ownState = "WON", opponentState = "WON")
        assertFalse(CurrentGameResultCleanupEvidence.authorizes(contradictory))
        assertNull(MandatoryRankSurrenderGuard.authorizeCurrentGameResultCleanup(contradictory))
        assertFalse(MulliganRankDispatchBarrier.completeTerminalWithoutSurrender(ticket + 1))
        assertEquals(MulliganRankDispatchBarrier.State.PENDING, MulliganRankDispatchBarrier.currentState())
    }

    @Test
    fun `proven terminal result clears stale mandatory fence for dismissal only`() {
        val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
        val surrenderCapability = MulliganRankDispatchBarrier.requireSurrender(ticket)
        assertNotNull(surrenderCapability)
        // The original surrender executor lost its game identity before the
        // terminal tail arrived. The rank fence must not wedge a proven result.
        MandatoryRankSurrenderGuard.begin("stale-or-mismatched-identity")
        val currentConcede = CurrentGameSurrenderTerminalEvidence(
            gameIdentity = "game-1:self",
            ownEntityId = "self",
            opponentEntityId = null,
            ownPlayState = "CONCEDED",
            opponentPlayState = null,
            ownConceded = true,
            finalGameOver = true,
            complete = true,
        )

        assertFalse(
            ActionDispatchGate.allowForState(
                action = "recovery.left",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
                rankBarrierState = MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED,
            ),
            "mandatory surrender stays in force before terminal proof",
        )
        val cleanup = MandatoryRankSurrenderGuard.authorizeCurrentGameResultCleanup(currentConcede)
        assertNotNull(cleanup)
        assertFalse(MandatoryRankSurrenderGuard.isPending())
        assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "strategy.card.play",
                paused = false,
                working = true,
                terminalCleanupPending = true,
                terminalCleanupCapabilityValid = true,
                rankBarrierState = MulliganRankDispatchBarrier.State.IDLE,
            ),
            "terminal proof grants dismissal, never ordinary gameplay",
        )
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "terminal-result.dismiss",
                paused = false,
                working = true,
                terminalCleanupPending = true,
                terminalCleanupCapabilityValid = true,
                rankBarrierState = MulliganRankDispatchBarrier.State.IDLE,
            ),
        )
    }

    private fun terminalEvidence(
        ownState: String,
        opponentState: String,
        finalGameOver: Boolean = true,
        complete: Boolean = true,
    ) = CurrentGameSurrenderTerminalEvidence(
        gameIdentity = "game-1:self",
        ownEntityId = "self",
        opponentEntityId = "opponent",
        ownPlayState = ownState,
        opponentPlayState = opponentState,
        ownConceded = ownState == "CONCEDED",
        finalGameOver = finalGameOver,
        complete = complete,
    )
}
