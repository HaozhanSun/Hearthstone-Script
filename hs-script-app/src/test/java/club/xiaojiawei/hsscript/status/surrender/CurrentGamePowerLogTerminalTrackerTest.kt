package club.xiaojiawei.hsscript.status.surrender

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class CurrentGamePowerLogTerminalTrackerTest {
    @AfterEach
    fun cleanup() = MandatoryRankSurrenderGuard.resetForTest()

    @Test
    fun `terminal proof is owned by the latest live game and correct players`() {
        val tracker = CurrentGamePowerLogTerminalTracker()
        tracker.observeLine("CREATE_GAME gameId=match-1")
        val oldIdentity = requireNotNull(tracker.currentGameIdentity("player-self#1"))
        MandatoryRankSurrenderGuard.begin(oldIdentity)
        tracker.observeLine("TAG_CHANGE Entity=player-self#1 tag=PLAYSTATE value=CONCEDED")
        tracker.observeLine("TAG_CHANGE Entity=player-opponent#2 tag=PLAYSTATE value=WON")
        tracker.observeLine("TAG_CHANGE Entity=GameEntity tag=STEP value=FINAL_GAMEOVER")
        tracker.observeLine("TAG_CHANGE Entity=GameEntity tag=STATE value=COMPLETE")

        val proof = tracker.currentGameSurrenderEvidence("player-self#1", "player-opponent#2")
        assertNotNull(proof)
        assertTrue(MandatorySurrenderTerminalEvidence.authorizes(oldIdentity, proof))
        assertFalse(
            MandatorySurrenderTerminalEvidence.authorizes(
                oldIdentity,
                tracker.currentGameSurrenderEvidence("player-opponent#2", "player-self#1"),
            ),
            "swapping the players must not turn our concession into a win proof",
        )
        assertNotNull(MandatoryRankSurrenderGuard.authorizeTerminalCleanup(proof))

        tracker.observeLine("CREATE_GAME gameId=match-2")
        val newProof = tracker.currentGameSurrenderEvidence("player-self#1", "player-opponent#2")
        assertFalse(MandatorySurrenderTerminalEvidence.authorizes(oldIdentity, newProof))
        assertFalse(MandatoryRankSurrenderGuard.authorizeTerminalCleanup(newProof) != null)
    }

    @Test
    fun `replayed log tail cannot authorize current-game cleanup`() {
        val tracker = CurrentGamePowerLogTerminalTracker()
        tracker.observeLine("CREATE_GAME gameId=historical", liveAttachedSession = false)
        tracker.observeLine("TAG_CHANGE Entity=player-self#1 tag=PLAYSTATE value=CONCEDED", liveAttachedSession = false)
        tracker.observeLine("TAG_CHANGE Entity=player-opponent#2 tag=PLAYSTATE value=WON", liveAttachedSession = false)
        tracker.observeLine("TAG_CHANGE Entity=GameEntity tag=STEP value=FINAL_GAMEOVER", liveAttachedSession = false)
        tracker.observeLine("TAG_CHANGE Entity=GameEntity tag=STATE value=COMPLETE", liveAttachedSession = false)
        assertFalse(tracker.hasCompleteTerminalEvidence())
        assertFalse(MandatorySurrenderTerminalEvidence.authorizes("1:player-self#1", null))
    }
}
