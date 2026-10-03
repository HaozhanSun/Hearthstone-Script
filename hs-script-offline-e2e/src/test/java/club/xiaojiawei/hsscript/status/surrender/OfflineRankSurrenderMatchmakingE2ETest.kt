package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
        val surrenderRequestLog: String,
        val powerLog: String,
        val nextGameTerminalPowerLog: String,
        val historicalRank3Evidence: HistoricalRankEvidence,
        val deckSelection: DeckSelection,
    ) {
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

        @Serializable
        data class HistoricalRankEvidence(
            val rank: Int,
            val phase: String,
            val provider: String,
            val confidence: Double,
            val captureTime: String,
            val sourceScreenshot: String,
            val sourceLog: String,
            val sourceLines: String,
            val screenshot: String,
            val decision: String,
            val reason: String,
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
        assertFalse(queueAllowed(), "do not queue while this game's mandatory surrender is unconfirmed")

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
        assertFalse(queueAllowed())

        val completion = MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
            screenKind = detectedScreen,
            confidence = fixture.deckSelection.confidence,
            visualEvidence = fixture.deckSelection.evidence,
            freshObservation = true,
        )
        assertEquals(MandatoryRankSurrenderDeckSelectionRecovery.Result.COMPLETED, completion)
        assertFalse(guard.isPending())
        assertEquals(MulliganRankDispatchBarrier.State.IDLE, barrier.currentState())
        assertTrue(queueAllowed(), "the existing queue gate may resume after terminal + fresh deck evidence")

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
    fun `rank four deck selection queues first then mulligan policy mandates and accepts surrender`() {
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

        // Production startMatching calls this runtime-only gate. The current
        // rank badge is intentionally not an input to pre-match eligibility.
        assertTrue(queueAllowed(), "a rank-4 badge on deck selection must not block queue entry")
        assertTrue(
            MatchmakingGuardPolicy.runtimeAllowsInput(working = true, paused = false),
            "the production pre-match gate must not consume the rank-4 menu badge",
        )

        // After the next game is authoritative in Power.log, run the actual
        // eligibility policy on rank 4 and arm only the mandatory surrender.
        val nextGame = resourceText(fixtureDirectory.resolve(fixture.nextGamePowerLog))
        val surrenderRequest = resourceText(fixtureDirectory.resolve(fixture.surrenderRequestLog))
        assertTrue(surrenderRequest.contains("SURRENDER_ACTION_REQUESTED"))
        assertTrue(surrenderRequest.contains("SURRENDER_EXECUTOR_REQUESTED"))
        assertTrue(nextGame.contains("CREATE_GAME gameId=${fixture.nextGameId}"))
        assertTrue(nextGame.contains("MULLIGAN_STATE value=INPUT"))
        val ticket = barrier.beginCurrentGame()
        val now = System.currentTimeMillis()
        val denied = RankEligibilityCorePolicy.evaluate(
            evidence = detection(4, now),
            expectedMode = "GAMEPLAY",
            actualMode = "GAMEPLAY",
            expectedInWar = true,
            inWar = true,
            nowMs = now,
        )
        assertEquals("rank-not-5-or-10", denied.reason)
        val surrenderCapability = barrier.requireSurrender(ticket)
        assertNotNull(surrenderCapability)
        assertTrue(
            barrier.isSurrenderCapabilityValid(surrenderCapability),
            "the current game's mandatory surrender must retain a valid one-shot dispatch capability",
        )
        guard.begin("${fixture.nextGameId}:self")
        assertFalse(queueAllowed(), "ordinary actions stay blocked while the current game must be surrendered")

        // The next game's terminal must be scoped to that game's unique marker.
        // A stale terminal from the prior game cannot release this new guard.
        assertTrue(guard.isPending())
        assertFalse(hasAcceptedSurrenderTerminal(priorTerminal, fixture.nextGameId))
        assertFalse(hasAcceptedSurrenderTerminal(nextGame, fixture.nextGameId))
        assertFalse(
            hasAcceptedSurrenderTerminal(surrenderRequest, fixture.nextGameId),
            "request/executor log lines do not prove Power.log accepted the surrender",
        )
        assertEquals(
            MandatoryRankSurrenderDeckSelectionRecovery.Result.BLOCKED,
            MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
                screenKind = deckScreen,
                confidence = fixture.deckSelection.confidence,
                visualEvidence = fixture.deckSelection.evidence,
                freshObservation = true,
            ),
            "the previous game's terminal cannot release the new game's guard",
        )
        assertTrue(guard.isPending())

        val terminal = resourceText(fixtureDirectory.resolve(fixture.nextGameTerminalPowerLog))
        assertTrue(hasAcceptedSurrenderTerminal(terminal, fixture.nextGameId))
        assertNotNull(guard.authorizeTerminalCleanup(terminalEvidence(fixture.nextGameId)))
        assertEquals(
            MandatoryRankSurrenderDeckSelectionRecovery.Result.COMPLETED,
            MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
                screenKind = deckScreen,
                confidence = fixture.deckSelection.confidence,
                visualEvidence = fixture.deckSelection.evidence,
                freshObservation = true,
            ),
        )
        assertTrue(queueAllowed(), "the next cycle is unblocked only after authoritative surrender and fresh screen evidence")
    }

    @Test
    fun `exact ranks five and ten remain eligible while rank four unknown and OCR failure require surrender`() {
        val now = System.currentTimeMillis()
        for (rank in listOf(5, 10)) {
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
            assertTrue(queueAllowed())
            assertEquals(MulliganRankDispatchBarrier.State.ELIGIBLE, barrier.currentState())
            barrier.resetForTest()
        }

        val denied = listOf(
            detection(4, now),
            null, // unresolved/unknown OCR and provider exception are both fail-closed evidence
        )
        denied.forEach { evidence ->
            val decision = RankEligibilityCorePolicy.evaluate(
                evidence = evidence,
                expectedMode = "GAMEPLAY",
                actualMode = "GAMEPLAY",
                expectedInWar = true,
                inWar = true,
                nowMs = now,
            )
            assertFalse(decision.eligible)
            val ticket = barrier.beginCurrentGame()
            assertNotNull(barrier.requireSurrender(ticket))
            guard.begin()
            assertFalse(queueAllowed(), "denied evidence must not continue or start ordinary queue input")
            guard.resetForTest()
            barrier.resetForTest()
        }
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
    fun `historical rank three mulligan badge is bound to its recorded OCR evidence`() {
        val evidence = readFixture().historicalRank3Evidence
        assertEquals(3, evidence.rank, "the supplied historical badge is rank 3, not rank 4")
        assertEquals("REPLACE_CARD", evidence.phase)
        assertEquals("PADDLEX", evidence.provider)
        assertEquals("DENY", evidence.decision)
        assertEquals("rank-evidence-stale", evidence.reason)
        assertEquals("codex-clipboard-753456c0-3dd3-4f6e-b6c1-08c838c41a5f.png", evidence.sourceScreenshot)
        val image = resourceBytes(Path.of(evidence.screenshot))
        val dimensions = pngDimensions(image)
        assertEquals(106, dimensions.first)
        assertEquals(111, dimensions.second)
        val historicLog = resourceText(fixtureDirectory.resolve(evidence.sourceLog))
        assertTrue(historicLog.contains("RANK_OCR") && historicLog.contains("selectedRank=3"))
        assertTrue(historicLog.contains("RANK_OCR_EVIDENCE") && historicLog.contains("numericRank=3 rank=3"))
        assertTrue(historicLog.contains("RANK_ELIGIBILITY_CHECK") && historicLog.contains("reason=rank-evidence-stale"))
    }

    private fun queueAllowed(): Boolean = MatchmakingGuardPolicy.runtimeAllowsInput(
        working = true,
        paused = false,
        mandatoryRankSurrenderPending = guard.isPending(),
    )

    private fun queueBlocked(): Boolean = !queueAllowed()

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
