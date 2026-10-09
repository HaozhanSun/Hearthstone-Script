package club.xiaojiawei.hsscript.status.surrender

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path

/** Offline integration replay of rank denial -> authoritative surrender -> fresh postgame screen -> next queue. */
class OfflineRankSurrenderMatchmakingE2ETest {
    @Serializable
    private data class Fixture(
        val scenario: String,
        val rank: Rank,
        val deckSelectionRankBadge: Int,
        val priorGameId: String,
        val nextGameId: String,
        val nextGamePowerLog: String,
        val surrenderRequestEvidence: ScriptLogEvidence,
        val powerLog: String,
        val nextGameTerminalPowerLog: String,
        val historicalRank3Evidence: HistoricalRankEvidence,
        val deckSelection: DeckSelection,
    ) {
        @Serializable
        data class ScriptLogEvidence(
            val file: String,
            val sourceFile: String,
            val sourceLineNumbers: List<Int>,
            val captureDate: String,
            val captureTime: String,
            val processId: Int,
            val powerLogSession: String,
            val gameId: String?,
            val sourceLogSha256: String,
            val redactionMetadata: String,
        )

        @Serializable
        data class HistoricalRankEvidence(
            val rank: Int,
            val phase: String,
            val provider: String,
            val confidence: Double,
            val captureTime: String,
            val processId: Int,
            val powerLogSession: String,
            val gameId: String?,
            val sourceFile: String,
            val sourceLog: String,
            val sourceLines: List<Int>,
            val sourceLogSha256: String,
            val sourceScreenshot: String,
            val screenshot: String,
            val decision: String,
            val reason: String,
            val redactionMetadata: String,
        )

        @Serializable
        data class Rank(
            val provider: String,
            val rank: Int,
            val tier: String,
            val confidence: Double,
            val agreementCount: Int,
            val phase: String,
        )

        @Serializable
        data class DeckSelection(
            val ocr: String,
            val screen: String,
            val confidence: Int,
            val evidence: String,
            val screenshot: String,
        )
    }

    private val fixtureDirectory = Path.of("offline-ocr", "rank-surrender")
    private val guard = MandatoryRankSurrenderGuard
    private val barrier = MulliganRankDispatchBarrier

    @AfterEach
    fun clearGlobalPolicyState() {
        guard.resetForTest()
        barrier.resetForTest()
    }

    @Test
    fun `rank four terminal fixture releases queue only after current deck screen is accepted`() {
        val fixture = readFixture()
        val now = System.currentTimeMillis()
        val rankEvidence = RankEvidence(
            rank = fixture.rank.rank,
            confidence = fixture.rank.confidence,
            captureWidth = 105,
            captureHeight = 108,
            provider = fixture.rank.provider,
            capturedAtMs = now,
            agreementCount = fixture.rank.agreementCount,
        )
        val rankDecision = RankEligibilityCorePolicy.evaluate(
            evidence = rankEvidence,
            expectedMode = "GAMEPLAY",
            actualMode = "GAMEPLAY",
            expectedInWar = true,
            inWar = true,
            nowMs = now,
        )
        assertFalse(rankDecision.eligible)
        assertEquals("rank-not-5-or-10", rankDecision.reason)

        val gameTicket = barrier.beginCurrentGame()
        assertNotNull(barrier.requireSurrender(gameTicket), "rank 4 must latch mandatory surrender")
        guard.begin("${fixture.priorGameId}:self")
        assertFalse(postGameRecoveryUnlocked(), "do not evaluate the next pre-match permit while mandatory surrender is unconfirmed")

        val powerLog = resourceText(fixtureDirectory.resolve(fixture.powerLog))
        assertTrue(powerLog.contains("CREATE_GAME"))
        assertTrue(powerLog.contains("MULLIGAN_STATE value=INPUT"))
        assertTrue(powerLog.contains("PLAYSTATE value=CONCEDED"))
        assertTrue(powerLog.contains("PLAYSTATE value=LOST"))
        assertTrue(powerLog.contains("PLAYSTATE value=WON"))
        assertTrue(powerLog.contains("STEP value=FINAL_GAMEOVER"))
        // The Power.log terminal state is the authoritative acceptance proof;
        // a request/click log alone would not mint this cleanup capability.
        assertNotNull(guard.authorizeTerminalCleanup(terminalEvidence(fixture.priorGameId)))

        val deckImagePath = fixtureDirectory.resolve(fixture.deckSelection.screenshot).normalize()
        val deckImage = resourceBytes(deckImagePath)
        val deckDimensions = pngDimensions(deckImage)
        assertTrue(deckDimensions.first > 100 && deckDimensions.second > 100)
        val detectedScreen = fixture.deckSelection.screen
        assertTrue(fixture.deckSelection.ocr.isNotBlank())
        assertEquals("DECK_SELECTION", detectedScreen)

        // A stale or weak image is not enough to clear the lock.
        assertEquals(
            MandatoryRankSurrenderDeckSelectionRecovery.Result.BLOCKED,
            MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
                screenKind = detectedScreen!!,
                confidence = fixture.deckSelection.confidence,
                visualEvidence = fixture.deckSelection.evidence,
                freshObservation = false,
            ),
        )
        assertFalse(postGameRecoveryUnlocked())

        val completion = MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
            screenKind = detectedScreen,
            confidence = fixture.deckSelection.confidence,
            visualEvidence = fixture.deckSelection.evidence,
            freshObservation = true,
        )
        assertEquals(MandatoryRankSurrenderDeckSelectionRecovery.Result.COMPLETED, completion)
        assertFalse(guard.isPending())
        assertEquals(MulliganRankDispatchBarrier.State.IDLE, barrier.currentState())
        assertTrue(postGameRecoveryUnlocked(), "terminal recovery may permit a new fresh deck-selection rank read")
        assertFalse(
            preMatchPermit(fixture.deckSelectionRankBadge, now).allowed,
            "rank four still cannot create the next queue input after recovery",
        )

        assertEquals(
            MandatoryRankSurrenderDeckSelectionRecovery.Result.NOT_REQUIRED,
            MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
                screenKind = detectedScreen,
                confidence = fixture.deckSelection.confidence,
                visualEvidence = fixture.deckSelection.evidence,
                freshObservation = true,
            ),
            "duplicate screen callbacks must not recreate a pending surrender",
        )
    }

    @Test
    fun `rank four deck-selection evidence prevents the next queue input after recovery`() {
        val fixture = readFixture()
        val priorTicket = barrier.beginCurrentGame()
        assertNotNull(barrier.requireSurrender(priorTicket))
        guard.begin("${fixture.priorGameId}:self")

        val priorTerminal = resourceText(fixtureDirectory.resolve(fixture.powerLog))
        assertTrue(hasAcceptedSurrenderTerminal(priorTerminal, fixture.priorGameId))
        assertFalse(hasAcceptedSurrenderTerminal(priorTerminal, fixture.nextGameId))
        assertNotNull(guard.authorizeTerminalCleanup(terminalEvidence(fixture.priorGameId)))

        val deckScreen = fixture.deckSelection.screen
        assertEquals("DECK_SELECTION", deckScreen)
        assertEquals(4, fixture.deckSelectionRankBadge, "the visible pre-match badge is rank 4")
        assertEquals(
            MandatoryRankSurrenderDeckSelectionRecovery.Result.COMPLETED,
            MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
                screenKind = deckScreen!!,
                confidence = fixture.deckSelection.confidence,
                visualEvidence = fixture.deckSelection.evidence,
                freshObservation = true,
            ),
        )

        val now = System.currentTimeMillis()
        val denied = preMatchPermit(fixture.deckSelectionRankBadge, now)
        var queueInputs = 0
        denied.permit?.dispatch(OfflinePreMatchRankQueuePermit.allowedRuntime(), now) { queueInputs++ }
        assertFalse(denied.allowed)
        assertEquals("rank-not-exact-5-or-10", denied.reason)
        assertEquals(0, queueInputs, "rank four must never create a second game or mandatory-surrender cycle")
    }

    @Test
    fun `rank four script excerpt proves request decision but not authoritative acceptance`() {
        val evidence = readFixture().surrenderRequestEvidence
        assertEquals("hs_script-2026-10-03.1.log", evidence.sourceFile)
        assertEquals(listOf(2999, 3012, 3013, 3014, 3015, 3016, 3017, 3018, 3019, 3020, 3021), evidence.sourceLineNumbers)
        assertEquals("2026-10-03", evidence.captureDate)
        assertEquals(58364, evidence.processId)
        assertEquals("Hearthstone_2026_10_03_04_40_56", evidence.powerLogSession)
        assertNull(evidence.gameId, "the script excerpt does not emit a gameId, so do not bind it to the sanitized Power.log id")
        assertTrue(evidence.sourceLogSha256.matches(Regex("[A-F0-9]{64}")))
        assertTrue(evidence.redactionMetadata.contains("<USER_HOME>"))

        val scriptTrace = resourceText(fixtureDirectory.resolve(evidence.file))
        assertTrue(scriptTrace.contains("RANK_ELIGIBILITY_CHECK stage=MULLIGAN") &&
            scriptTrace.contains("rank=4 tier=GOLD") && scriptTrace.contains("reason=rank-not-5-or-10"))
        assertTrue(scriptTrace.contains("SURRENDER_ACTION_REQUESTED") &&
            scriptTrace.contains("source=mulligan-rank-preflight"))
        assertTrue(scriptTrace.contains("SURRENDER_EXECUTOR_REQUESTED"))
        assertFalse(scriptTrace.contains("PLAYSTATE value=CONCEDED"))
        assertFalse(scriptTrace.contains("STEP value=FINAL_GAMEOVER"))
        // This is a request/decision excerpt only. The terminal Power.log fixture
        // remains the sole authority for accepted surrender in the separate lifecycle replay.
    }

    @Test
    fun `exact five and ten continue while every other numeric rank surrenders and OCR holds`() {
        val now = System.currentTimeMillis()
        for (rank in listOf(5, 10)) {
            val preMatch = preMatchPermit(rank, now)
            var queueInputs = 0
            assertTrue(requireNotNull(preMatch.permit).dispatch(OfflinePreMatchRankQueuePermit.allowedRuntime(), now) { queueInputs++ })
            assertEquals(1, queueInputs, "rank=$rank must authorize exactly one first queue input")
            val decision = RankEligibilityCorePolicy.evaluate(
                evidence = detection(rank, now),
                expectedMode = "GAMEPLAY",
                actualMode = "GAMEPLAY",
                expectedInWar = true,
                inWar = true,
                nowMs = now,
            )
            assertTrue(decision.eligible, "rank=$rank must remain playable: ${decision.reason}")
            val ticket = barrier.beginCurrentGame()
            assertTrue(barrier.authorizeEligibleRank(ticket, rank))
            assertFalse(guard.isPending(), "eligible rank=$rank must not arm mandatory surrender")
            assertTrue(postGameRecoveryUnlocked())
            assertEquals(MulliganRankDispatchBarrier.State.ELIGIBLE, barrier.currentState())
            barrier.resetForTest()
        }

        val nonTarget = RankEligibilityCorePolicy.evaluate(
            evidence = detection(4, now),
            expectedMode = "GAMEPLAY",
            actualMode = "GAMEPLAY",
            expectedInWar = true,
            inWar = true,
            nowMs = now,
        )
        assertFalse(nonTarget.eligible)
        assertFalse(preMatchPermit(4, now).allowed, "rank four must be rejected before this in-game defense runs")
        val ticket = barrier.beginCurrentGame()
        assertNotNull(barrier.requireSurrender(ticket), "resolved in-game rank 4 must trigger surrender")
        guard.begin()
        assertFalse(postGameRecoveryUnlocked(), "ordinary actions stay blocked for mandatory surrender")
        guard.resetForTest()
        barrier.resetForTest()

        for (rank in listOf(3, 21, 233, 5220)) {
            val denied = RankEligibilityCorePolicy.evaluate(
                evidence = detection(rank, now),
                expectedMode = "GAMEPLAY",
                actualMode = "GAMEPLAY",
                expectedInWar = true,
                inWar = true,
                nowMs = now,
            )
            assertFalse(denied.eligible, "rank=$rank must surrender rather than release gameplay")
        }

        val unresolved = RankEligibilityCorePolicy.evaluate(
            evidence = null,
            expectedMode = "GAMEPLAY",
            actualMode = "GAMEPLAY",
            expectedInWar = true,
            inWar = true,
            nowMs = now,
        )
        assertFalse(unresolved.eligible)
        assertEquals("rank-evidence-missing", unresolved.reason)
        // The app preflight retries this boundedly and then pauses while the
        // rank barrier stays pending; it must not authorize turns or surrender.
        val unresolvedTicket = barrier.beginCurrentGame()
        assertEquals(MulliganRankDispatchBarrier.State.PENDING, barrier.currentState())
        assertTrue(postGameRecoveryUnlocked(), "missing in-game rank alone does not mint a next-queue permit")
        assertEquals(
            MulliganRankDispatchBarrier.State.PENDING,
            barrier.currentState(),
            "pending in-game rank evidence must remain a dispatch barrier for ordinary turn actions",
        )
        assertTrue(unresolvedTicket > 0L)
    }

    @Test
    fun `deck screenshot without authoritative terminal state cannot unlock matchmaking`() {
        val fixture = readFixture()
        val ticket = barrier.beginCurrentGame()
        assertNotNull(barrier.requireSurrender(ticket))
        guard.begin()
        assertEquals(
            MandatoryRankSurrenderDeckSelectionRecovery.Result.BLOCKED,
            MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
                screenKind = fixture.deckSelection.screen,
                confidence = fixture.deckSelection.confidence,
                visualEvidence = fixture.deckSelection.evidence,
                freshObservation = true,
            ),
        )
        assertTrue(queueBlocked())
    }

    @Test
    fun `historical rank three decision trace remains source-bound and crop stays separate`() {
        val evidence = readFixture().historicalRank3Evidence
        assertEquals(3, evidence.rank)
        assertEquals("REPLACE_CARD", evidence.phase)
        assertEquals("PADDLEX", evidence.provider)
        assertEquals("DENY", evidence.decision)
        assertEquals("rank-evidence-stale", evidence.reason)
        assertEquals("hs_script-2026-10-03.7.log", evidence.sourceFile)
        assertEquals(listOf(19275, 19281, 19284, 19285, 19287), evidence.sourceLines)
        assertEquals(77664, evidence.processId)
        assertEquals("Hearthstone_2026_10_03_10_42_08", evidence.powerLogSession)
        assertNull(evidence.gameId, "gameId is not present in the copied script-log lines")
        assertTrue(evidence.sourceLogSha256.matches(Regex("[A-F0-9]{64}")))
        assertTrue(evidence.redactionMetadata.contains("<USER_HOME>"))

        val history = resourceText(fixtureDirectory.resolve(evidence.sourceLog))
        evidence.sourceLines.forEach { lineNumber ->
            assertTrue(history.lineSequence().any { it.startsWith("$lineNumber|") }, "missing source line $lineNumber")
        }
        assertTrue(history.contains("selectedRank=3") && history.contains("numericRank=3 rank=3"))
        assertTrue(history.contains("decision=DENY reason=rank-evidence-stale"))
        assertTrue(history.contains(evidence.sourceScreenshot))

        // This supplied 106x111 crop is kept as a separate visual fixture; the
        // script excerpt records its own full rank-ROI screenshot filename.
        val image = resourceBytes(Path.of("offline-ocr", "rank3-mulligan-badge.png"))
        val dimensions = pngDimensions(image)
        assertEquals(106, dimensions.first)
        assertEquals(111, dimensions.second)
    }

    private fun postGameRecoveryUnlocked(): Boolean = !guard.isPending()

    private fun queueBlocked(): Boolean = !postGameRecoveryUnlocked()

    private fun preMatchPermit(rank: Int?, now: Long) = OfflinePreMatchRankQueuePermit.evaluate(
        OfflinePreMatchRankQueuePermit.Evidence(
            rank = rank,
            confidence = if (rank == null) null else 0.99,
            capturedAtMs = now,
            outcome = if (rank == null) OfflinePreMatchRankQueuePermit.OcrOutcome.UNKNOWN else OfflinePreMatchRankQueuePermit.OcrOutcome.SUCCESS,
        ),
        now,
    )

    private fun terminalEvidence(gameId: String) = CurrentGameSurrenderTerminalEvidence(
        gameIdentity = "$gameId:self",
        ownEntityId = "self",
        opponentEntityId = "opponent",
        ownPlayState = "CONCEDED",
        opponentPlayState = "WON",
        finalGameOver = true,
        complete = true,
    )

    private fun readFixture(): Fixture = Json.decodeFromString(
        resourceText(fixtureDirectory.resolve("rank4-mandatory-surrender-cycle.json")),
    )

    private fun hasAcceptedSurrenderTerminal(powerLog: String, expectedGameId: String): Boolean {
        val gameStart = powerLog.indexOf("CREATE_GAME gameId=$expectedGameId")
        if (gameStart < 0) return false
        val nextGameStart = powerLog.indexOf("CREATE_GAME gameId=", startIndex = gameStart + 1)
            .let { if (it < 0) powerLog.length else it }
        val gameEvidence = powerLog.substring(gameStart, nextGameStart)
        return gameEvidence.contains("MULLIGAN_STATE value=INPUT") &&
            gameEvidence.contains("PLAYSTATE value=CONCEDED") &&
            gameEvidence.contains("PLAYSTATE value=LOST") &&
            gameEvidence.contains("PLAYSTATE value=WON") &&
            gameEvidence.contains("STEP value=FINAL_GAMEOVER")
    }

    private fun resourceText(path: Path): String = javaClass.classLoader
        .getResourceAsStream(path.toString().replace('\\', '/'))
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("Missing offline fixture: $path")

    private fun resourceBytes(path: Path): ByteArray = javaClass.classLoader
        .getResourceAsStream(path.toString().replace('\\', '/'))
        ?.use { it.readBytes() }
        ?: error("Missing offline fixture: $path")

    private fun pngDimensions(data: ByteArray): Pair<Int, Int> {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        assertTrue(data.size >= 24 && data.copyOfRange(0, 8).contentEquals(signature), "fixture must be a PNG")
        return ByteBuffer.wrap(data, 16, 8).let { it.int to it.int }
    }

    private fun detection(rank: Int, now: Long) = RankEvidence(
        rank = rank,
        confidence = 1.0,
        captureWidth = 105,
        captureHeight = 108,
        provider = "PADDLEX",
        capturedAtMs = now,
        agreementCount = 1,
    )
}
