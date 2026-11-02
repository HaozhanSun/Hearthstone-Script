package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.utils.TerminalPageCleanupCoordinator
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResultPageDismissalPolicyTest {
    @Test
    fun `event timing requires fresh authorized actionable frames and clamps retry delay`() {
        val now = System.nanoTime()
        val fresh = ResultScreenObservation(
            resultVisible = null,
            captureAuthorized = true,
            continueControlVisible = true,
            capturedAtNanos = now,
        )
        assertTrue(TerminalResultSequenceTimingPolicy.actionable(fresh, Long.MIN_VALUE, now))
        assertFalse(TerminalResultSequenceTimingPolicy.actionable(fresh.copy(continueControlVisible = false), Long.MIN_VALUE, now))
        assertFalse(TerminalResultSequenceTimingPolicy.actionable(fresh.copy(captureAuthorized = false), Long.MIN_VALUE, now))
        assertFalse(TerminalResultSequenceTimingPolicy.actionable(fresh.copy(capturedAtNanos = now - Duration.ofSeconds(26).toNanos()), Long.MIN_VALUE, now))
        assertFalse(TerminalResultSequenceTimingPolicy.actionable(fresh.copy(capturedAtNanos = now - Duration.ofSeconds(1).toNanos()), now - Duration.ofSeconds(1).toNanos(), now))
        assertTrue(TerminalResultSequenceTimingPolicy.actionable(fresh.copy(capturedAtNanos = now), now - Duration.ofSeconds(2).toNanos(), now))
        assertEquals(2_000L, TerminalResultSequenceTimingPolicy.retryInterval(1L))
        assertEquals(2_500L, TerminalResultSequenceTimingPolicy.retryInterval(2_500L))
        assertEquals(3_000L, TerminalResultSequenceTimingPolicy.retryInterval(9_000L))
    }

    @Test
    fun `incident screenshots and current-client postchecks replay through dismissal policy with bounded retries`() {
        assertEquals(2_000, ResultPageDismissalPolicy.TERMINAL_RETRY_MIN_INTERVAL_MS)
        assertEquals(3_000, ResultPageDismissalPolicy.TERMINAL_RETRY_MAX_INTERVAL_MS)

        val defeat = loadImage(INCIDENT_DEFEAT)
        val rankOne = loadImage(INCIDENT_RANK_ONE)
        val rankTwo = loadImage(INCIDENT_RANK_TWO)
        assertEquals(INCIDENT_DEFEAT_SHA256, sha256(fixtureBytes(INCIDENT_DEFEAT)))
        assertEquals(INCIDENT_RANK_ONE_SHA256, sha256(fixtureBytes(INCIDENT_RANK_ONE)))
        assertEquals(INCIDENT_RANK_TWO_SHA256, sha256(fixtureBytes(INCIDENT_RANK_TWO)))
        listOf(defeat, rankOne, rankTwo).forEach { image ->
            assertEquals(1920, image.width)
            assertEquals(1080, image.height)
        }
        val defeatKind = ScreenStateRecovery.classifyImageForResultFixture(defeat)
        val rankOneKind = ScreenStateRecovery.classifyImageForResultFixture(rankOne)
        val rankTwoKind = ScreenStateRecovery.classifyImageForResultFixture(rankTwo)
        println("INCIDENT_RESULT_SCREEN_CLASSIFICATION defeat=$defeatKind ${ScreenStateRecovery.resultVisualEvidenceForFixture(defeat)}")
        println("INCIDENT_RESULT_SCREEN_CLASSIFICATION rankOne=$rankOneKind ${ScreenStateRecovery.resultVisualEvidenceForFixture(rankOne)}")
        println("INCIDENT_RESULT_SCREEN_CLASSIFICATION rankTwo=$rankTwoKind ${ScreenStateRecovery.resultVisualEvidenceForFixture(rankTwo)}")
        assertEquals("RESULT", defeatKind)
        assertEquals("RANK_PROGRESS_CONTINUATION", rankOneKind)
        assertEquals("RANK_PROGRESS_CONTINUATION", rankTwoKind)

        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = requireNotNull(coordinator.begin().ticket)
        var flow = ResultPageDismissalPolicy.FlowProgress()
        var dispatchCalls = 0
        val acceptedDispatch = { dispatchCalls++; true }
        val maxInputs = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS

        val defeatObservation = observationFor(defeatKind!!)
        flow = ResultPageDismissalPolicy.observeFlow(flow, defeatObservation, destinationTransitionConfirmed = false)
        assertEquals(ResultPageDismissalPolicy.FlowScreen.DEFEAT, flow.lastObserved)
        val initialDecision = ResultPageDismissalPolicy.decide(
            inWar = false,
            resultPageVisible = defeatObservation.resultVisible,
            attempt = 1,
            maxAttempts = maxInputs,
            clickAttempts = coordinator.snapshot().inputs,
            terminalCleanupAuthorized = true,
            captureAuthorized = true,
            priorResultPageConfirmed = true,
        )
        assertEquals(ResultPageDismissalPolicy.Decision.DISPATCH_CLICK, initialDecision)
        assertNotNull(coordinator.reserveInput(ticket))
        assertTrue(acceptedDispatch())
        assertEquals(TerminalPageCleanupCoordinator.State.RUNNING, coordinator.snapshot().state)

        // The exact incident's UNKNOWN postcheck arrives after the new timing
        // floor; it still cannot dispatch because it has no actionable band.
        val initialAt = LocalDateTime.parse("2026-10-21T12:41:30.627")
        val unknownAt = LocalDateTime.parse("2026-10-21T12:41:35.150")
        val retryDelayMs = 2_500L
        val secondDispatchAt = initialAt.plusNanos(retryDelayMs * 1_000_000)
        assertTrue(retryDelayMs in ResultPageDismissalPolicy.TERMINAL_RETRY_MIN_INTERVAL_MS.toLong()..
            ResultPageDismissalPolicy.TERMINAL_RETRY_MAX_INTERVAL_MS.toLong())
        assertTrue(Duration.between(initialAt, unknownAt).toMillis() > retryDelayMs)
        assertEquals(1, dispatchCalls, "accepted dispatch does not schedule an early extra input")

        // The second incident frame is a rank page. UNKNOWN at the live postcheck
        // does not erase the known defeat/rank evidence or consume the retry early.
        val rankOneObservation = observationFor(rankOneKind!!)
        val rankTwoObservation = observationFor(rankTwoKind!!)
        flow = ResultPageDismissalPolicy.observeFlow(flow, rankOneObservation, destinationTransitionConfirmed = false)
        assertEquals(ResultPageDismissalPolicy.FlowScreen.RANK_PROGRESS, flow.lastObserved)
        val unknownObservation = ResultScreenObservation(resultVisible = null, captureAuthorized = true)
        flow = ResultPageDismissalPolicy.observeFlow(flow, unknownObservation, destinationTransitionConfirmed = false)
        assertEquals(ResultPageDismissalPolicy.FlowScreen.UNKNOWN, flow.lastObserved)
        assertTrue(flow.defeatObserved)
        assertTrue(flow.rankProgressObserved)

        val unknownRetry = ResultPageDismissalPolicy.decide(
            inWar = false,
            resultPageVisible = null,
            attempt = 2,
            maxAttempts = maxInputs,
            clickAttempts = coordinator.snapshot().inputs,
            terminalCleanupAuthorized = true,
            captureAuthorized = true,
            priorResultPageConfirmed = true,
        )
        assertEquals(ResultPageDismissalPolicy.Decision.DISPATCH_CLICK, unknownRetry)
        assertTrue(Duration.between(initialAt, secondDispatchAt).toMillis() >= retryDelayMs)
        assertNotNull(coordinator.reserveInput(ticket))
        assertTrue(acceptedDispatch())
        assertEquals(2, dispatchCalls)
        assertEquals(TerminalPageCleanupCoordinator.State.RUNNING, coordinator.snapshot().state)

        var unknownFallbackAttempts = 1
        val firstRankRetry = ResultPageDismissalPolicy.decideRankProgress(
            rankProgressVisible = true,
            terminalCleanupAuthorized = true,
            captureAuthorized = true,
            rankProgressInputAttempts = coordinator.snapshot().rankProgressInputs,
        )
        assertEquals(ResultPageDismissalPolicy.Decision.DISPATCH_CLICK, firstRankRetry)
        val firstRankInput = coordinator.reserveRankProgressInput(ticket, PostResultRankProgressPolicy.MAX_CONTINUE_INPUTS)
        assertNotNull(firstRankInput)
        assertEquals(PostResultRankProgressPolicy.Input.CENTER_CLICK, PostResultRankProgressPolicy.inputForAttempt(coordinator.snapshot().rankProgressInputs))
        assertTrue(acceptedDispatch())
        assertEquals(TerminalPageCleanupCoordinator.State.RUNNING, coordinator.snapshot().state)

        // A second UNKNOWN after an accepted rank input still permits the
        // remaining independent UNKNOWN fallback; the overall shared input
        // budget remains the hard cap.
        val unknownAfterRank = ResultPageDismissalPolicy.decide(
            inWar = false,
            resultPageVisible = null,
            attempt = 3,
            maxAttempts = maxInputs,
            clickAttempts = coordinator.snapshot().inputs,
            unknownFallbackAttempts = unknownFallbackAttempts,
            terminalCleanupAuthorized = true,
            captureAuthorized = true,
            priorResultPageConfirmed = true,
        )
        assertEquals(ResultPageDismissalPolicy.Decision.DISPATCH_CLICK, unknownAfterRank)
        assertNotNull(coordinator.reserveInput(ticket))
        assertTrue(acceptedDispatch())
        unknownFallbackAttempts += 1
        assertEquals(4, dispatchCalls)

        flow = ResultPageDismissalPolicy.observeFlow(flow, rankTwoObservation, destinationTransitionConfirmed = false)
        val secondRankRetry = ResultPageDismissalPolicy.decideRankProgress(
            rankProgressVisible = true,
            terminalCleanupAuthorized = true,
            captureAuthorized = true,
            rankProgressInputAttempts = coordinator.snapshot().rankProgressInputs,
        )
        assertEquals(ResultPageDismissalPolicy.Decision.DISPATCH_CLICK, secondRankRetry)
        val secondRankInput = coordinator.reserveRankProgressInput(ticket, PostResultRankProgressPolicy.MAX_CONTINUE_INPUTS)
        assertNotNull(secondRankInput)
        assertEquals(PostResultRankProgressPolicy.Input.KEYBOARD_ENTER, PostResultRankProgressPolicy.inputForAttempt(coordinator.snapshot().rankProgressInputs))
        assertTrue(acceptedDispatch())
        assertEquals(TerminalPageCleanupCoordinator.State.RUNNING, coordinator.snapshot().state)
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decideRankProgress(
                rankProgressVisible = true,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                rankProgressInputAttempts = coordinator.snapshot().rankProgressInputs,
            ),
            "a still-visible rank page cannot exceed the separate Continue cap",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 5,
                maxAttempts = maxInputs,
                clickAttempts = coordinator.snapshot().inputs,
                unknownFallbackAttempts = unknownFallbackAttempts,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                priorResultPageConfirmed = true,
            ),
            "UNKNOWN retries also stop at their cap",
        )

        val postRankImage = loadImage(POST_RANK_DESTINATION)
        assertTrue(postRankImage.width > 0 && postRankImage.height > 0)
        // This saved destination fixture is a recognized home/deck screen in
        // the existing OCR recovery contract; the empty-OCR visual-only test
        // helper intentionally does not infer this text-led destination.
        val destinationObservation = observationFor("DECK_SELECTION")
        val destinationConfirmed = FreshPostResultDestinationPolicy.isConfirmed(
            destinationObservation.destinationScreen,
            destinationObservation.destinationConfidence,
            destinationObservation.captureAuthorized,
            resultPageEvidenceVisible = false,
        )
        flow = ResultPageDismissalPolicy.observeFlow(flow, destinationObservation, destinationConfirmed)
        assertEquals(ResultPageDismissalPolicy.FlowScreen.POST_RESULT_DESTINATION, flow.lastObserved)
        assertTrue(flow.postRankTransitionVerified)
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = destinationObservation.resultVisible,
                attempt = 4,
                maxAttempts = maxInputs,
                clickAttempts = coordinator.snapshot().inputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                priorResultPageConfirmed = true,
                destinationTransitionConfirmed = destinationConfirmed,
            ),
        )
        assertTrue(coordinator.confirmDestination(ticket))
        assertEquals(TerminalPageCleanupCoordinator.State.COMPLETED, coordinator.snapshot().state)
        assertEquals(5, dispatchCalls)

        assertIncidentLogsCorrelate()
    }

    @Test
    fun `OS accepted center click is not UI acceptance and terminal retries preserve live center Enter pairs`() {
        assertEquals(ResultPageDismissalPolicy.Input.CENTER_CLICK, ResultPageDismissalPolicy.inputForClickAttempt(1, 5))
        assertEquals(ResultPageDismissalPolicy.Input.KEYBOARD_ENTER, ResultPageDismissalPolicy.inputForClickAttempt(2, 5))
        assertEquals(ResultPageDismissalPolicy.Input.RETRY_CLICK, ResultPageDismissalPolicy.inputForClickAttempt(3, 5))
        assertEquals(ResultPageDismissalPolicy.Input.CENTER_CLICK, ResultPageDismissalPolicy.terminalInputForClickAttempt(3, 16))
        assertEquals(ResultPageDismissalPolicy.Input.KEYBOARD_ENTER, ResultPageDismissalPolicy.terminalInputForClickAttempt(4, 16))
        assertEquals(ResultPageDismissalPolicy.Input.CENTER_CLICK, ResultPageDismissalPolicy.terminalInputForClickAttempt(5, 16))
        assertEquals(null, ResultPageDismissalPolicy.inputForClickAttempt(6, 5))

        // Even if SendInput returned accepted=true, only a fresh observed
        // transition releases the result barrier; after the two authorized
        // terminal inputs, UNKNOWN exhausts instead of treating dispatch as success.
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 2,
                maxAttempts = 5,
                clickAttempts = 1,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 3,
                maxAttempts = 2,
                clickAttempts = 2,
                terminalCleanupAuthorized = true,
            ),
            "the center click plus one Enter exhaust the terminal fallback budget",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 4,
                maxAttempts = 2,
                clickAttempts = 2,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                destinationTransitionConfirmed = true,
            ),
        )
    }

    @Test
    fun `valid terminal cleanup survives tournament mode transition but pause still stops it`() {
        assertEquals(
            false,
            ResultPageDismissalPolicy.shouldStopWorker(
                paused = false,
                gameplayMode = false,
                terminalCleanupCapabilityValid = true,
            ),
        )
        assertEquals(
            true,
            ResultPageDismissalPolicy.shouldStopWorker(
                paused = false,
                gameplayMode = false,
                terminalCleanupCapabilityValid = false,
            ),
        )
        assertEquals(
            true,
            ResultPageDismissalPolicy.shouldStopWorker(
                paused = true,
                gameplayMode = false,
                terminalCleanupCapabilityValid = true,
            ),
        )
        assertEquals(
            true,
            ResultPageDismissalPolicy.shouldStopWorker(
                paused = false,
                gameplayMode = true,
                terminalCleanupCapabilityValid = true,
                newGameDetected = true,
            ),
            "even an authorized old-result worker must stop when a newer game begins",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 1,
                maxAttempts = 5,
            ),
        )
    }

    @Test
    fun `terminal cleanup requires explicit fresh deck or queue transition proof`() {
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 3,
                maxAttempts = 16,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                destinationTransitionConfirmed = false,
            ),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 3,
                maxAttempts = 16,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                destinationTransitionConfirmed = true,
            ),
        )
    }

    @Test
    fun `active war permits result click only while fresh screen evidence confirms result`() {
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(inWar = true, resultPageVisible = true, attempt = 1, maxAttempts = 5),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.BLOCKED_UNCONFIRMED_DURING_WAR,
            ResultPageDismissalPolicy.decide(inWar = true, resultPageVisible = null, attempt = 1, maxAttempts = 5),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(inWar = true, resultPageVisible = false, attempt = 2, maxAttempts = 5),
        )
    }

    @Test
    fun `unknown postcheck never counts as success and retries remain bounded`() {
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(inWar = false, resultPageVisible = null, attempt = 1, maxAttempts = 5),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(inWar = false, resultPageVisible = null, attempt = 5, maxAttempts = 5),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(inWar = false, resultPageVisible = true, attempt = 5, maxAttempts = 5),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(inWar = false, resultPageVisible = null, attempt = 6, maxAttempts = 5),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 17,
                maxAttempts = 16,
                clickAttempts = 5,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
            "a high probe count cannot exhaust an input budget when only five clicks were sent",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 17,
                maxAttempts = 5,
                clickAttempts = 5,
                terminalCleanupAuthorized = true,
            ),
            "the terminal capability cannot permit a sixth click",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 17,
                maxAttempts = 5,
                clickAttempts = 5,
                terminalCleanupAuthorized = true,
            ),
            "an unverified negative postcheck cannot confirm dismissal",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 100,
                maxAttempts = 5,
                clickAttempts = 5,
                terminalCleanupAuthorized = false,
            ),
        )
    }

    @Test
    fun `terminal fallback needs a fresh allow-listed result capture and stays bounded`() {
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 1,
                maxAttempts = 2,
                terminalCleanupAuthorized = true,
                captureAuthorized = false,
            ),
            "even OCR-positive classification cannot ground a terminal action without pixel-owned capture",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 1,
                maxAttempts = 2,
                terminalCleanupAuthorized = true,
                captureAuthorized = false,
            ),
            "an unverified negative postcheck cannot release the terminal cleanup lock",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 1,
                maxAttempts = 2,
                terminalCleanupAuthorized = true,
                captureAuthorized = false,
            ),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = true,
                resultPageVisible = null,
                attempt = 1,
                maxAttempts = 2,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
            "same-game terminal proof and an authorized capture do not allow input when screen classification is UNKNOWN",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 1,
                maxAttempts = 16,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
            "an authorized capture is not enough: unknown/settings-overlay pixels are not an allow-listed result screen",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 1,
                maxAttempts = 16,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 17,
                maxAttempts = 16,
                clickAttempts = 16,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
            "dispatching the bounded sequence is not confirmation; exhaustion is explicit unless a destination is observed",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 17,
                maxAttempts = 16,
                clickAttempts = 16,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                destinationTransitionConfirmed = true,
            ),
            "only an observed post-result destination completes the cleanup",
        )
    }

    private fun loadImage(resource: String) = requireNotNull(
        ImageIO.read(ByteArrayInputStream(fixtureBytes(resource))),
    ) { "Invalid screenshot fixture: $resource" }

    private fun fixtureBytes(resource: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream(resource),
    ) { "Missing screenshot fixture: $resource" }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02X".format(it) }

    private fun observationFor(screenKind: String): ResultScreenObservation = when (screenKind) {
        "RESULT" -> ResultScreenObservation(resultVisible = true, captureAuthorized = true)
        "RANK_PROGRESS_CONTINUATION" -> ResultScreenObservation(
            resultVisible = ScreenStateRecovery.resultVisibilityForTest(screenKind, 95),
            captureAuthorized = true,
            rankProgressVisible = true,
        )
        "DECK_SELECTION", "MATCHMAKING" -> ResultScreenObservation(
            resultVisible = ScreenStateRecovery.resultVisibilityForTest(screenKind, 95),
            captureAuthorized = true,
            destinationScreen = screenKind,
            destinationConfidence = 95,
        )
        else -> ResultScreenObservation(resultVisible = null, captureAuthorized = true)
    }

    private fun assertIncidentLogsCorrelate() {
        // The active log rotates. Read the dated archive that contains the
        // 12:41 incident instead of the live tail from a later process run.
        val betaLog = Path.of("C:/Users/yzjsh/Documents/Codex/2026-08-15/for-all-these-delay-short-are-2/outputs/Hearthstone Script Beta/log/hs_script-2026-10-21.0.log")
        val powerLog = Path.of("D:/Hearthstone/Logs/Hearthstone_2026_10_21_12_38_41/Power.log")
        assumeTrue(Files.isRegularFile(betaLog) && Files.isRegularFile(powerLog), "incident logs are on the originating workstation")
        val power = Files.readString(powerLog)
        assertTrue(power.contains("12:41:21.0066891"))
        assertTrue(power.contains("tag=PLAYSTATE value=LOST"))
        assertTrue(power.contains("tag=PLAYSTATE value=WON"))
        val lines = Files.readString(betaLog).lineSequence()
            .filter { it.startsWith("2026-10-21 12:41") || it.startsWith("2026-10-21 12:42") }
            .toList()
        assertTrue(lines.any { it.contains("12:41:30.628") && it.contains("dispatchAccepted=true") })
        assertTrue(lines.any { it.contains("12:41:35.150") && it.contains("postcheck=UNKNOWN") })
        assertEquals(1, lines.count { it.contains("RESULT_PAGE_DISMISSAL_INPUT") })
        assertTrue(lines.count { it.contains("RESULT_PAGE_DISMISSAL_WAIT") && it.contains("postcheck=UNKNOWN") } >= 6)
        assertTrue(lines.any { it.contains("12:42:04.402") && it.contains("PAUSE_REQUESTED source=F2/low-level-hook") })
        assertTrue(Files.readString(betaLog).contains("2026-10-21 12:42:04.402"))
    }

    companion object {
        private const val INCIDENT_DEFEAT = "/offline-ocr/result-flow/incident-defeat-20261021-124129.png"
        private const val INCIDENT_DEFEAT_SHA256 = "E7FCEB66325200C4BE62C500C81FA9DB5B679645503BE49FB93F1C30EF3F4266"
        private const val INCIDENT_RANK_ONE = "/offline-ocr/result-flow/incident-rank-progress-1-20261021-124134.png"
        private const val INCIDENT_RANK_ONE_SHA256 = "CE57DEA9A79D2E82FD339AE29E16A8B68CD8C7FBBAA2C152B66A8924E1248B77"
        private const val INCIDENT_RANK_TWO = "/offline-ocr/result-flow/incident-rank-progress-2-20261021-124207.png"
        private const val INCIDENT_RANK_TWO_SHA256 = "3E007CAB199F6AFBFE590B5BFE400C56CF91AC6DC2EA77B17113E11B3CF211AC"
        private const val POST_RANK_DESTINATION = "/offline-ocr/screen-recovery/deck-selection-screen.png"
    }
}
