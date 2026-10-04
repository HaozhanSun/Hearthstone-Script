package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.status.ActionDispatchGate
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
        tracker.observeLine("CREATE_GAME gameId=historical", currentSessionEvidence = false)
        tracker.observeLine("TAG_CHANGE Entity=player-self#1 tag=PLAYSTATE value=CONCEDED", currentSessionEvidence = false)
        tracker.observeLine("TAG_CHANGE Entity=player-opponent#2 tag=PLAYSTATE value=WON", currentSessionEvidence = false)
        tracker.observeLine("TAG_CHANGE Entity=GameEntity tag=STEP value=FINAL_GAMEOVER", currentSessionEvidence = false)
        tracker.observeLine("TAG_CHANGE Entity=GameEntity tag=STATE value=COMPLETE", currentSessionEvidence = false)
        assertFalse(tracker.hasCompleteTerminalEvidence())
        assertFalse(MandatorySurrenderTerminalEvidence.authorizes("1:player-self#1", null))
    }

    @Test
    fun `verified current-session active-game replay seeds later live surrender terminal proof`() {
        val lines = readCapturedRealTerminalExcerpt()
        val terminalStart = lines.indexOfFirst { it.contains("PLAYSTATE value=CONCEDED") }
        assertTrue(terminalStart > 0, "fixture must contain active-game prefix followed by the surrender result")

        val tracker = CurrentGamePowerLogTerminalTracker()
        lines.take(terminalStart).forEach { tracker.observeLine(it, currentSessionEvidence = true) }
        val requestedIdentity = requireNotNull(tracker.currentGameIdentity("laz#12793"))
        MandatoryRankSurrenderGuard.begin(requestedIdentity)
        assertFalse(tracker.hasCompleteTerminalEvidence(), "the replayed prefix is an unfinished game")

        // These exact captured lines arrived live after the script's surrender click.
        lines.drop(terminalStart).forEach { tracker.observeLine(it, currentSessionEvidence = true) }
        val evidence = tracker.currentGameSurrenderEvidence("laz#12793", "Klfe#1109")
        assertNotNull(evidence)
        assertEquals("LOST", evidence?.ownPlayState)
        assertEquals("WON", evidence?.opponentPlayState)
        assertTrue(MandatorySurrenderTerminalEvidence.authorizes(requestedIdentity, evidence))
        val cleanup = MandatoryRankSurrenderGuard.authorizeTerminalCleanup(evidence)
        assertNotNull(cleanup, "same current CREATE_GAME plus local terminal proof authorizes cleanup")
        assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(cleanup))
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", cleanup))
        assertFalse(MandatoryRankSurrenderGuard.isPending(), "authoritative same-game proof clears the rank barrier")
        assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "matchmaking.start",
                paused = false,
                working = true,
                terminalCleanupPending = true,
            ),
            "terminal UI cleanup keeps every ordinary action fenced",
        )
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "terminal-result.dismiss",
                paused = false,
                working = true,
                terminalCleanupPending = true,
                terminalCleanupCapabilityValid = true,
            ),
        )
        assertTrue(
            MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED", cleanup),
            "observed result dismissal releases the terminal-only cleanup lock",
        )
        assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
    }

    @Test
    fun `terminal lines from another CREATE_GAME cannot release the requested surrender`() {
        val lines = readCapturedRealTerminalExcerpt()
        val terminalStart = lines.indexOfFirst { it.contains("PLAYSTATE value=CONCEDED") }
        val tracker = CurrentGamePowerLogTerminalTracker()
        lines.take(terminalStart).forEach { tracker.observeLine(it, currentSessionEvidence = true) }
        val requestedIdentity = requireNotNull(tracker.currentGameIdentity("laz#12793"))
        MandatoryRankSurrenderGuard.begin(requestedIdentity)

        tracker.observeLine("D 18:40:00.0000000 GameState.DebugPrintPower() - CREATE_GAME")
        lines.drop(terminalStart).forEach { tracker.observeLine(it, currentSessionEvidence = true) }

        val laterGameEvidence = tracker.currentGameSurrenderEvidence("laz#12793", "Klfe#1109")
        assertNotNull(laterGameEvidence)
        assertFalse(MandatorySurrenderTerminalEvidence.authorizes(requestedIdentity, laterGameEvidence))
        assertEquals(null, MandatoryRankSurrenderGuard.authorizeTerminalCleanup(laterGameEvidence))
        assertTrue(MandatoryRankSurrenderGuard.isPending(), "different-game evidence must preserve the safety barrier")
    }

    private fun readCapturedRealTerminalExcerpt(): List<String> =
        requireNotNull(javaClass.getResourceAsStream("rank4-v546-current-session-terminal-excerpt.log"))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readLines() }
}
