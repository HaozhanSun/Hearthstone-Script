package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.bean.DiskLogFile
import club.xiaojiawei.hsscript.status.ActionDispatchGate
import club.xiaojiawei.hsscript.strategy.phase.ReplaceCardPhaseStrategy
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
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
    fun `verified mulligan identity releases terminal rank barrier and stops recovery retry`() {
        val lines = readV563RankSurrenderTerminalExcerpt()
        val tracker = CurrentGamePowerLogTerminalTracker()
        val localInput = lines.first { it.contains("Entity=laz#12793 tag=MULLIGAN_STATE value=INPUT") }
        lines.takeWhile { it != localInput }.forEach { tracker.observeLine(it) }

        // This is the same trust boundary as ReplaceCardPhaseStrategy: only
        // the event already classified as our own INPUT may bind the account.
        val identity = requireNotNull(tracker.bindVerifiedLocalEntity("laz#12793"))
        MandatoryRankSurrenderGuard.begin(identity)
        val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
        val surrenderCapability = requireNotNull(MulliganRankDispatchBarrier.requireSurrender(ticket))
        assertTrue(MulliganRankDispatchBarrier.isSurrenderCapabilityValid(surrenderCapability))

        val retryScheduler = Executors.newSingleThreadScheduledExecutor()
        val retryFired = AtomicBoolean(false)
        val queuedRetry = retryScheduler.schedule({ retryFired.set(true) }, 5, TimeUnit.SECONDS)
        val retryFutureField = ReplaceCardPhaseStrategy::class.java
            .getDeclaredField("rankSurrenderRetryFuture")
            .apply { isAccessible = true }
        retryFutureField.set(ReplaceCardPhaseStrategy, queuedRetry)

        try {
            lines.dropWhile { it != localInput }.forEach { tracker.observeLine(it) }
            val terminal = tracker.currentGameSurrenderEvidence("", "law#31891")
            assertNotNull(terminal)
            assertEquals(identity, terminal?.gameIdentity)
            assertEquals("LOST", terminal?.ownPlayState)
            assertEquals("WON", terminal?.opponentPlayState)
            assertTrue(terminal?.ownConceded == true)
            assertTrue(terminal?.finalGameOver == true)
            assertTrue(terminal?.complete == true)

            val cleanup = MandatoryRankSurrenderGuard.authorizeTerminalCleanup(terminal)
            assertNotNull(cleanup, "the current CREATE_GAME terminal result is locally attributable")
            assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", cleanup))
            ReplaceCardPhaseStrategy.onAuthoritativeTerminalProofAccepted()
            assertTrue(queuedRetry.isCancelled, "accepted terminal proof must cancel the delayed surrender retry")
            assertEquals(null, retryFutureField.get(ReplaceCardPhaseStrategy), "no retry remains queued")
            assertFalse(retryFired.get(), "the canceled callback must not run later")
            assertFalse(MandatoryRankSurrenderGuard.isPending())
            assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
            assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
            assertFalse(
                ActionDispatchGate.allowForState(
                    action = "recovery.left",
                    paused = false,
                    working = true,
                    terminalCleanupPending = true,
                ),
                "terminal proof must stop repeated stale Mulligan recovery clicks",
            )
            assertTrue(
                ActionDispatchGate.allowForState(
                    action = "terminal-result.dismiss",
                    paused = false,
                    working = true,
                    terminalCleanupPending = true,
                    terminalCleanupCapabilityValid = true,
                ),
                "only the capability-authorized result dismissal may proceed",
            )

            // Preserve the exact result frame captured for this failed live run;
            // its tooltip text is not authoritative and cannot override Power.log.
            val screenshot = requireNotNull(javaClass.getResource("game-0001-loss-20261004-202935-244.png"))
            val screenshotFile = File(screenshot.toURI())
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(screenshotFile.readBytes()).joinToString("") { "%02X".format(it) }
            assertEquals("322F882AB3F346FE0D392502650E12AC28BFD7204CBE09A369157A1507A14C6F", digest)
            val image = ImageIO.read(screenshotFile)
            assertEquals(1920, image.width)
            assertEquals(1080, image.height)
        } finally {
            queuedRetry.cancel(true)
            retryScheduler.shutdownNow()
        }
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

    @Test
    fun `two sequential games resolve local identity by previously confirmed GameAccountId`() {
        val tracker = CurrentGamePowerLogTerminalTracker()
        val fixture = File(
            requireNotNull(javaClass.getResource("rank4-v549-two-game-account-binding-excerpt.log")).toURI(),
        )
        var firstIdentity: String? = null
        var secondIdentity: String? = null
        var secondTailResult: PowerLogTerminalTailReader.Result? = null

        DiskLogFile(fixture.absolutePath).use { file ->
            var gameNumber = 0
            while (true) {
                val line = file.readLine() ?: break
                tracker.observeLine(line)
                if (line.contains("GameState.DebugPrintPower() - CREATE_GAME")) gameNumber++

                if (gameNumber == 1 && line.contains("tag=MULLIGAN_STATE value=INPUT")) {
                    // WAR provides an explicit local identity in game one.
                    // Bind that name to GameAccountId, not to Mulligan INPUT.
                    firstIdentity = requireNotNull(tracker.currentGameIdentity("laz#12793"))
                    MandatoryRankSurrenderGuard.begin(firstIdentity)
                }

                if (gameNumber == 1 && line.contains("tag=STATE value=COMPLETE")) {
                    val evidence = tracker.currentGameSurrenderEvidence("", "")
                    assertTrue(MandatorySurrenderTerminalEvidence.authorizes(firstIdentity, evidence))
                    val cleanup = requireNotNull(MandatoryRankSurrenderGuard.authorizeTerminalCleanup(evidence))
                    assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", cleanup))
                    assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED", cleanup))
                }

                if (gameNumber == 2 && line.contains("tag=MULLIGAN_STATE value=INPUT")) {
                    secondIdentity = requireNotNull(tracker.currentGameIdentity(""))
                    assertFalse(firstIdentity == secondIdentity, "the second CREATE_GAME owns a new generation")
                    assertEquals(
                        null,
                        tracker.currentGameIdentity("Izzy#31408"),
                        "a WAR identity contradicting the previously account-bound local player is ambiguous",
                    )
                    MandatoryRankSurrenderGuard.begin(secondIdentity)
                }

                if (gameNumber == 2 && line.contains("tag=PLAYSTATE value=CONCEDED")) {
                    val partial = tracker.currentGameSurrenderEvidence("", null)
                    assertEquals("laz#12793", partial?.ownEntityId)
                    assertTrue(partial?.ownConceded == true)
                    assertFalse(tracker.hasCompleteTerminalEvidence())

                    // Match the live race: GameOver starts on CONCEDED and
                    // drains the unread Power.log tail before it seeks EOF.
                    secondTailResult = PowerLogTerminalTailReader.observeUntilComplete(
                        file = file,
                        maxLines = 16,
                        deadlineNanos = System.nanoTime() + 100_000_000L,
                        observeLine = { tracker.observeLine(it) },
                    )
                    break
                }
            }
        }

        assertNotNull(firstIdentity)
        assertNotNull(secondIdentity)
        assertEquals(PowerLogTerminalTailReader.StopReason.COMPLETE, secondTailResult?.stopReason)
        assertTrue(tracker.hasCompleteTerminalEvidence())
        val secondEvidence = tracker.currentGameSurrenderEvidence("", null)
        assertEquals("LOST", secondEvidence?.ownPlayState)
        assertTrue(secondEvidence?.ownConceded == true)
        assertTrue(secondEvidence?.finalGameOver == true)
        assertTrue(secondEvidence?.complete == true)
        assertTrue(MandatorySurrenderTerminalEvidence.authorizes(secondIdentity, secondEvidence))
        val secondCleanup = requireNotNull(MandatoryRankSurrenderGuard.authorizeTerminalCleanup(secondEvidence))
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", secondCleanup))
        assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED", secondCleanup))
        assertFalse(MandatoryRankSurrenderGuard.isPending())
    }

    @Test
    fun `mulligan input cannot establish local ownership without account binding`() {
        val tracker = CurrentGamePowerLogTerminalTracker()
        tracker.observeLine("D 20:57:44.8466179 GameState.DebugPrintPower() - CREATE_GAME")
        tracker.observeLine("D 20:57:44.8466179 GameState.DebugPrintPower() - TAG_CHANGE Entity=Izzy#31408 tag=MULLIGAN_STATE value=INPUT")

        assertEquals(null, tracker.currentGameIdentity(""))
        assertEquals(null, tracker.currentGameSurrenderEvidence("", null))
    }

    @Test
    fun `ambiguous or changed GameAccountId mapping fails closed`() {
        val ambiguous = CurrentGamePowerLogTerminalTracker()
        seedFirstGameAccount(ambiguous)
        ambiguous.observeLine("D 20:57:44.8466179 GameState.DebugPrintPower() - CREATE_GAME")
        ambiguous.observeLine("D 20:57:44.8466179 GameState.DebugPrintPower() - Player EntityID=2 PlayerID=1 GameAccountId=[hi=144115193835963207 lo=36936081]")
        ambiguous.observeLine("D 20:57:44.8466179 GameState.DebugPrintPower() - Player EntityID=3 PlayerID=2 GameAccountId=[hi=144115193835963207 lo=36936081]")
        ambiguous.observeLine("D 20:57:44.8466179 GameState.DebugPrintGame() - PlayerID=1, PlayerName=laz#12793")
        ambiguous.observeLine("D 20:57:44.8466179 GameState.DebugPrintGame() - PlayerID=2, PlayerName=Opponent#2")
        assertEquals(null, ambiguous.currentGameIdentity(""), "duplicate account ownership must not be guessed")

        val changed = CurrentGamePowerLogTerminalTracker()
        seedFirstGameAccount(changed)
        changed.observeLine("D 20:57:44.8466179 GameState.DebugPrintPower() - CREATE_GAME")
        changed.observeLine("D 20:57:44.8466179 GameState.DebugPrintPower() - Player EntityID=2 PlayerID=1 GameAccountId=[hi=144115193835963207 lo=99999999]")
        changed.observeLine("D 20:57:44.8466179 GameState.DebugPrintGame() - PlayerID=1, PlayerName=Other#2")
        changed.observeLine("D 20:57:44.8466179 GameState.DebugPrintPower() - TAG_CHANGE Entity=Other#2 tag=MULLIGAN_STATE value=INPUT")
        assertEquals(null, changed.currentGameIdentity(""), "a changed account without explicit WAR identity is not local proof")
    }

    private fun seedFirstGameAccount(tracker: CurrentGamePowerLogTerminalTracker) {
        tracker.observeLine("D 20:54:30.4604634 GameState.DebugPrintPower() - CREATE_GAME")
        tracker.observeLine("D 20:54:30.4604634 GameState.DebugPrintPower() - Player EntityID=2 PlayerID=1 GameAccountId=[hi=144115193835963207 lo=36936081]")
        tracker.observeLine("D 20:54:30.4604634 GameState.DebugPrintPower() - Player EntityID=3 PlayerID=2 GameAccountId=[hi=144115193835963207 lo=694393783]")
        tracker.observeLine("D 20:54:30.4604634 GameState.DebugPrintGame() - PlayerID=1, PlayerName=laz#12793")
        tracker.observeLine("D 20:54:30.4604634 GameState.DebugPrintGame() - PlayerID=2, PlayerName=UNKNOWN HUMAN PLAYER")
        assertEquals("1:laz#12793", tracker.currentGameIdentity("laz#12793"))
    }

    private fun readCapturedRealTerminalExcerpt(): List<String> =
        requireNotNull(javaClass.getResourceAsStream("rank4-v546-current-session-terminal-excerpt.log"))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readLines() }

    private fun readLiveV547Excerpt(): List<String> =
        requireNotNull(javaClass.getResourceAsStream("rank4-v547-live-game2-surrender-excerpt.log"))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readLines() }

    private fun readV563RankSurrenderTerminalExcerpt(): List<String> =
        requireNotNull(javaClass.getResourceAsStream("v4.16.563-rank-surrender-terminal-excerpt.log"))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readLines() }
}
