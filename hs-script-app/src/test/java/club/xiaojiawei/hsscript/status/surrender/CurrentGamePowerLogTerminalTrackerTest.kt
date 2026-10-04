package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.bean.DiskLogFile
import club.xiaojiawei.hsscript.status.ActionDispatchGate
import java.io.File
import java.nio.file.Files
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

    @Test
    fun `phase-tail lines complete the same game proof with duplicate create and missing opponent id`() {
        val lines = readLiveV547Excerpt()
        val tracker = CurrentGamePowerLogTerminalTracker()
        val fixture = File(requireNotNull(javaClass.getResource("rank4-v547-live-game2-surrender-excerpt.log")).toURI())

        // The outer listener observes GameState CREATE_GAME. The phase handler
        // consumes the nested PowerTaskList CREATE_GAME through FINAL_GAMEOVER.
        val requestedIdentity = DiskLogFile(fixture.absolutePath).use { file ->
            val firstLine = requireNotNull(file.readLine())
            tracker.observeLine(firstLine)
            val identity = requireNotNull(tracker.currentGameIdentity("player-self#1"))
            MandatoryRankSurrenderGuard.begin(identity)
            var line: String?
            do {
                line = file.readLine()
                if (line != null) tracker.observeLine(line)
            } while (line != null && !line.contains("tag=STEP value=FINAL_GAMEOVER"))

            assertFalse(tracker.hasCompleteTerminalEvidence())
            assertTrue(tracker.currentGameSurrenderEvidence("player-self#1", "")?.finalGameOver == true)
            assertEquals(identity, tracker.currentGameIdentity("player-self#1"))
            val tailResult = PowerLogTerminalTailReader.observeUntilComplete(
                file = file,
                maxLines = 256,
                deadlineNanos = System.nanoTime() + 100_000_000L,
                observeLine = { tracker.observeLine(it) },
            )
            assertEquals(PowerLogTerminalTailReader.StopReason.COMPLETE, tailResult.stopReason)
            assertEquals(1, tailResult.linesRead, "only the raw STATE=COMPLETE tail should remain")
            identity
        }

        assertTrue(tracker.hasCompleteTerminalEvidence())
        assertEquals(requestedIdentity, tracker.currentGameIdentity("player-self#1"),
            "same-timestamp PowerTaskList CREATE_GAME is a duplicate, not a new match")

        val evidence = tracker.currentGameSurrenderEvidence("player-self#1", "")
        assertNotNull(evidence)
        assertEquals("LOST", evidence?.ownPlayState, "Power.log overwrites CONCEDED with LOST later")
        assertTrue(evidence?.ownConceded == true, "preserve the earlier local CONCEDED event")
        assertEquals(null, evidence?.opponentEntityId, "the live model did not resolve the opponent id")
        assertTrue(MandatorySurrenderTerminalEvidence.authorizes(requestedIdentity, evidence))

        val cleanup = MandatoryRankSurrenderGuard.authorizeTerminalCleanup(evidence)
        assertNotNull(cleanup)
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", cleanup))
        assertFalse(MandatoryRankSurrenderGuard.isPending(), "accepted same-game proof clears the rank barrier")
        assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "recovery.left",
                paused = false,
                working = true,
                terminalCleanupPending = true,
            ),
            "ordinary recovery remains fenced while terminal UI cleanup is pending",
        )
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED", cleanup))
        assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
    }

    @Test
    fun `bounded phase tail without complete terminal proof keeps rank barrier pending`() {
        val lines = readLiveV547Excerpt()
        val tracker = CurrentGamePowerLogTerminalTracker()
        val tempLog = Files.createTempFile("rank4-v547-incomplete-terminal-", ".log")
        try {
            val incompleteLines = lines.takeWhile { !it.contains("tag=STATE value=COMPLETE") } +
                "D 19:31:15.7610021 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=STATE value=IN_PROGRESS"
            Files.writeString(tempLog, incompleteLines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
            DiskLogFile(tempLog.toString()).use { file ->
                val firstLine = requireNotNull(file.readLine())
                tracker.observeLine(firstLine)
                val requestedIdentity = requireNotNull(tracker.currentGameIdentity("player-self#1"))
                MandatoryRankSurrenderGuard.begin(requestedIdentity)
                val result = PowerLogTerminalTailReader.observeUntilComplete(
                    file = file,
                    maxLines = 2,
                    deadlineNanos = System.nanoTime() + 100_000_000L,
                    observeLine = { tracker.observeLine(it) },
                )
                assertEquals(PowerLogTerminalTailReader.StopReason.LINE_LIMIT, result.stopReason)
                assertEquals(2, result.linesRead)
            }
            assertFalse(tracker.hasCompleteTerminalEvidence())
            val partial = tracker.currentGameSurrenderEvidence("player-self#1", "")
            assertEquals(null, MandatoryRankSurrenderGuard.authorizeTerminalCleanup(partial))
            assertTrue(MandatoryRankSurrenderGuard.isPending(), "incomplete tail must fail closed")
        } finally {
            Files.deleteIfExists(tempLog)
        }
    }

    private fun readCapturedRealTerminalExcerpt(): List<String> =
        requireNotNull(javaClass.getResourceAsStream("rank4-v546-current-session-terminal-excerpt.log"))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readLines() }

    private fun readLiveV547Excerpt(): List<String> =
        requireNotNull(javaClass.getResourceAsStream("rank4-v547-live-game2-surrender-excerpt.log"))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readLines() }
}
