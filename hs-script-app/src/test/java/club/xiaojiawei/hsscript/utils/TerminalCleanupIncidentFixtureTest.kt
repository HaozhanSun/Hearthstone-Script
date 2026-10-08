package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.status.ResultPageEvidencePolicy
import club.xiaojiawei.hsscript.status.ResultPageDismissalPolicy
import club.xiaojiawei.hsscript.status.ResultScreenObservation
import club.xiaojiawei.hsscript.status.PostResultRankProgressPolicy
import club.xiaojiawei.hsscript.status.ActionDispatchGate
import club.xiaojiawei.hsscript.status.FreshPostResultDestinationPolicy
import club.xiaojiawei.hsscript.status.ScreenStateRecovery
import club.xiaojiawei.hsscript.status.UpstreamScreenStateRecovery
import club.xiaojiawei.hsscript.status.surrender.CurrentGamePowerLogTerminalTracker
import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

class TerminalCleanupIncidentFixtureTest {
    @AfterEach
    fun resetTerminalGuard() = MandatoryRankSurrenderGuard.resetForTest()

    @Test
    fun `v570 pre-overlay board is rejected but fresh defeat panel dispatches once after same-game proof`() {
        val preOverlayBytes = requireNotNull(javaClass.getResourceAsStream(V570_PRE_OVERLAY)).use { it.readBytes() }
        val resultBytes = requireNotNull(javaClass.getResourceAsStream(V570_RESULT)).use { it.readBytes() }
        assertEquals(V570_PRE_OVERLAY_SHA256, sha256(preOverlayBytes))
        assertEquals(V570_RESULT_SHA256, sha256(resultBytes))
        val preOverlay = requireNotNull(ImageIO.read(ByteArrayInputStream(preOverlayBytes)))
        val result = requireNotNull(ImageIO.read(ByteArrayInputStream(resultBytes)))
        assertEquals(1920, preOverlay.width)
        assertEquals(1080, preOverlay.height)
        assertEquals(1920, result.width)
        assertEquals(1080, result.height)

        assertFalse(ScreenStateRecovery.classifyImageForResultFixture(preOverlay) == "RESULT")
        assertFalse(UpstreamScreenStateRecovery.classifyImageForResultFixture(preOverlay) == "RESULT")
        assertEquals("RESULT", ScreenStateRecovery.classifyImageForResultFixture(result))
        assertEquals("RESULT", UpstreamScreenStateRecovery.classifyImageForResultFixture(result))
        val visual = ScreenStateRecovery.resultVisualEvidenceForFixture(result)
        assertTrue(visual.contains("continue=0.04"), visual)
        assertTrue(visual.contains("banner=0.48"), visual)
        assertTrue(visual.contains("centerDark=0.26"), visual)

        val incident = requireNotNull(javaClass.getResourceAsStream(V570_LOG)).bufferedReader().use { it.readText() }
        val terminal = incident.indexOf("sameGame=true ownPlayState=LOST opponentPlayState=WON finalGameOver=true complete=true playerTerminal=CONCEDED")
        val capture = incident.indexOf("GAME_WINDOW_PIXEL_AUTHORITY targetPid=95920 accepted=true reason=exact-client-visible")
        val timeout = incident.indexOf("RESULT_PAGE_CLEANUP_FAILED reason=episode-deadline-exceeded inputs=0 probes=3")
        assertTrue(terminal >= 0 && capture > terminal && timeout > capture)

        val visualObservation = ResultScreenObservation(
            resultVisible = true,
            captureAuthorized = true,
            visualOnlyResultEvidence = true,
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = visualObservation.resultVisible,
                attempt = 1,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = 0,
                terminalCleanupAuthorized = false,
                captureAuthorized = visualObservation.captureAuthorized,
                visualOnlyResultEvidence = visualObservation.visualOnlyResultEvidence,
            ),
            "visual evidence alone must not authorize a terminal continue action",
        )

        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = requireNotNull(coordinator.begin().ticket)
        val probe = requireNotNull(coordinator.nextProbe(ticket))
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = visualObservation.resultVisible,
                attempt = probe,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = coordinator.snapshot().inputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = visualObservation.captureAuthorized,
                visualOnlyResultEvidence = visualObservation.visualOnlyResultEvidence,
            ),
            "the fresh result capture is actionable only after accepted same-game terminal proof",
        )
        assertEquals(1, coordinator.reserveInput(ticket), "reserve exactly one Continue input")
        assertEquals("CENTER_CLICK", GameUtil.terminalResultInputForAttempt(1)?.name)

        // A fresh post-input destination observation, not input reservation,
        // is what completes the episode.
        val postInputDestinationVisible = false
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = postInputDestinationVisible,
                attempt = probe + 1,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = coordinator.snapshot().inputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                destinationTransitionConfirmed = true,
            ),
        )
        assertTrue(coordinator.confirmDestination(ticket))
        assertEquals(1, coordinator.snapshot().inputs)
        assertEquals(TerminalPageCleanupCoordinator.State.COMPLETED, coordinator.snapshot().state)
        assertEquals(120_000L, TerminalPageCleanupCoordinator.DEFAULT_MAX_DURATION_MILLIS)
    }

    @Test
    fun `gameplay settings frame and exact live timeline do not masquerade as a result page`() {
        val screenshot = requireNotNull(javaClass.getResourceAsStream(SCREENSHOT)).use { it.readBytes() }
        assertEquals(SCREENSHOT_SHA256, sha256(screenshot))
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(screenshot)))
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)
        assertFalse(ScreenStateRecovery.classifyImageForResultFixture(image) == "RESULT")
        assertFalse(UpstreamScreenStateRecovery.classifyImageForResultFixture(image) == "RESULT")

        val incident = requireNotNull(javaClass.getResourceAsStream(LOG)).bufferedReader().use { it.readText() }
        val terminalPhase = incident.indexOf("17:12:40.302")
        val lateSettingsClick = incident.indexOf("17:12:42.191")
        val proofAccepted = incident.indexOf("17:12:45.336")
        val falseResultOcr = incident.indexOf("17:13:25.641")
        val duplicateSuppressed = incident.indexOf("single-flight-active generation=6")
        val prolongedWait = incident.indexOf("probe=7 clickAttempts=6 postcheck=UNKNOWN")
        val repeatedInput = incident.indexOf("probe=10 sharedInput=7")
        assertTrue(terminalPhase in 0 until lateSettingsClick, "terminal phase precedes stale settings retry")
        assertTrue(lateSettingsClick in 0 until proofAccepted, "the old retry clicked after FINAL_GAMEOVER but before proof acceptance")
        assertTrue(proofAccepted in 0 until falseResultOcr)
        assertTrue(falseResultOcr in 0 until duplicateSuppressed)
        assertTrue(duplicateSuppressed in 0 until prolongedWait)
        assertTrue(prolongedWait in 0 until repeatedInput)
        assertFalse(ResultPageEvidencePolicy.looksLikeResultText(":字XX示盾>|||||-二于7.扣击继续"))
    }

    @Test
    fun `rank progression screen is recognized as an intermediate state by both production classifiers`() {
        val screenshot = requireNotNull(javaClass.getResourceAsStream(RANK_RESULT_SCREENSHOT)).use { it.readBytes() }
        val image: BufferedImage = requireNotNull(ImageIO.read(ByteArrayInputStream(screenshot)))
        assertEquals("RANK_PROGRESS_CONTINUATION", ScreenStateRecovery.classifyImageForResultFixture(image))
        assertEquals("RANK_PROGRESS_CONTINUATION", UpstreamScreenStateRecovery.classifyImageForResultFixture(image))
    }

    @Test
    fun `v572 live rank progression capture is recognized and negative controls are rejected`() {
        val screenshotBytes = requireNotNull(javaClass.getResourceAsStream(V572_RANK_PROGRESS)).use { it.readBytes() }
        assertEquals(V572_RANK_PROGRESS_SHA256, sha256(screenshotBytes))
        val screenshot = requireNotNull(ImageIO.read(ByteArrayInputStream(screenshotBytes)))
        assertEquals(1920, screenshot.width)
        assertEquals(1080, screenshot.height)
        assertEquals("RANK_PROGRESS_CONTINUATION", ScreenStateRecovery.classifyImageForResultFixture(screenshot))
        assertEquals("RANK_PROGRESS_CONTINUATION", UpstreamScreenStateRecovery.classifyImageForResultFixture(screenshot))

        val loss = loadImage(V571_RESULT_185648)
        val menu = loadImage(SCREENSHOT)
        val preOverlay = loadImage(V570_PRE_OVERLAY)
        assertEquals("RESULT", ScreenStateRecovery.classifyImageForResultFixture(loss))
        assertEquals("RESULT", UpstreamScreenStateRecovery.classifyImageForResultFixture(loss))
        listOf(menu, preOverlay).forEach { image ->
            assertFalse(ScreenStateRecovery.classifyImageForResultFixture(image) == "RANK_PROGRESS_CONTINUATION")
            assertFalse(UpstreamScreenStateRecovery.classifyImageForResultFixture(image) == "RANK_PROGRESS_CONTINUATION")
        }
    }

    @Test
    fun `v575 captured post-surrender rank page is recognized and uses click then Enter sequence`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream(V575_RANK_PROGRESS)).use { it.readBytes() }
        assertEquals(V575_RANK_PROGRESS_SHA256, sha256(bytes))
        val screenshot = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(1920, screenshot.width)
        assertEquals(1080, screenshot.height)
        assertEquals("RANK_PROGRESS_CONTINUATION", ScreenStateRecovery.classifyImageForResultFixture(screenshot))
        assertEquals("RANK_PROGRESS_CONTINUATION", UpstreamScreenStateRecovery.classifyImageForResultFixture(screenshot))
        val metrics = ScreenStateRecovery.resultVisualEvidenceForFixture(screenshot)
        assertTrue(metrics.contains("continue=0.04685"), metrics)
        assertTrue(metrics.contains("banner=0.08998"), metrics)
        assertTrue(metrics.contains("centerDark=0.20482"), metrics)
        assertTrue(metrics.contains("bannerWarm=0.64902"), metrics)
        assertFalse(ResultPageEvidencePolicy.looksLikeResultVisual(0.047, 0.090, 0.205, 0.649))

        val loss = loadImage(V574_DEFEAT_RESULT)
        assertEquals("RESULT", ScreenStateRecovery.classifyImageForResultFixture(loss))
        assertEquals("RESULT", UpstreamScreenStateRecovery.classifyImageForResultFixture(loss))
        assertEquals(PostResultRankProgressPolicy.Input.CENTER_CLICK, PostResultRankProgressPolicy.inputForAttempt(1))
        assertEquals(PostResultRankProgressPolicy.Input.CENTER_CLICK, PostResultRankProgressPolicy.inputForAttempt(2))
        assertEquals(null, PostResultRankProgressPolicy.inputForAttempt(3))

        val incident = requireNotNull(javaClass.getResourceAsStream(V575_RANK_INCIDENT)).bufferedReader().use { it.readText() }
        assertTrue(incident.contains("PLAYSTATE value=CONCEDED"))
        assertTrue(incident.contains("PLAYSTATE value=LOST"))
        assertTrue(incident.contains("STATE value=COMPLETE"))
        assertTrue(incident.contains("resultBannerWarm=0.649"))
        assertTrue(incident.contains("detected=UNKNOWN"), "the failed v575 observation is retained as incident evidence")
        assertTrue(incident.contains("RESULT_PAGE_CLEANUP_FAILED reason=episode-deadline-exceeded"))
    }

    @Test
    fun `v576 low center-dark defeat frame dispatches bounded first click then requires transition proof`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream(V576_DEFEAT_RESULT)).use { it.readBytes() }
        assertEquals(V576_DEFEAT_RESULT_SHA256, sha256(bytes))
        val defeat = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(1920, defeat.width)
        assertEquals(1080, defeat.height)
        assertEquals("RESULT", ScreenStateRecovery.classifyImageForResultFixture(defeat))
        assertEquals("RESULT", UpstreamScreenStateRecovery.classifyImageForResultFixture(defeat))
        assertTrue(ResultPageEvidencePolicy.looksLikeResultText("败北 点击继续"))
        val metrics = ScreenStateRecovery.resultVisualEvidenceForFixture(defeat)
        assertTrue(metrics.contains("continue=0.04"), metrics)
        assertTrue(metrics.contains("banner=0.51"), metrics)
        assertTrue(metrics.contains("centerDark=0.17"), metrics)
        assertTrue(metrics.contains("bannerWarm=0.41"), metrics)

        val rankProgress = loadImage(V575_RANK_PROGRESS)
        assertEquals("RANK_PROGRESS_CONTINUATION", ScreenStateRecovery.classifyImageForResultFixture(rankProgress))
        assertEquals("RANK_PROGRESS_CONTINUATION", UpstreamScreenStateRecovery.classifyImageForResultFixture(rankProgress))

        // The fresh exact-PID frame plus terminal capability permits the first
        // bounded center click. It does not require the post-input observation
        // to already show RESULT or the destination.
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 1,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = 0,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                visualOnlyResultEvidence = true,
            ),
        )
        assertEquals("CENTER_CLICK", GameUtil.terminalResultInputForAttempt(1)?.name)
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 2,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = 1,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
            "an UNKNOWN postcheck after dispatch cannot confirm transition or release cleanup",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 1,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = 0,
                terminalCleanupAuthorized = false,
                captureAuthorized = true,
                visualOnlyResultEvidence = true,
            ),
            "visual evidence without same-game terminal capability cannot dispatch",
        )

        val incident = requireNotNull(javaClass.getResourceAsStream(V576_DEFEAT_INCIDENT)).bufferedReader().use { it.readText() }
        assertTrue(incident.contains("RESULT_PAGE_DISMISSAL_WAIT reason=screen-transition-unconfirmed"))
        assertTrue(incident.contains("postcheck=UNKNOWN dispatch=false captureAuthorized=true terminalCleanupAuthorized=true"))
        assertTrue(incident.contains("PLAYSTATE value=LOST"))
        assertTrue(incident.contains("detected=UNKNOWN confidence=0 evidence=none"))
    }

    @Test
    fun `v577 Gold 3 reward is a separate bounded rank-progress transition after defeat result`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream(V577_RANK_PROGRESS)).use { it.readBytes() }
        assertEquals(V577_RANK_PROGRESS_SHA256, sha256(bytes))
        val rankProgress = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(1920, rankProgress.width)
        assertEquals(1080, rankProgress.height)
        assertEquals("RANK_PROGRESS_CONTINUATION", ScreenStateRecovery.classifyImageForResultFixture(rankProgress))
        assertEquals("RANK_PROGRESS_CONTINUATION", UpstreamScreenStateRecovery.classifyImageForResultFixture(rankProgress))
        assertFalse(ResultPageEvidencePolicy.looksLikeResultVisual(0.050, 0.096, 0.240, 0.437))

        val defeat = loadImage(V576_DEFEAT_RESULT)
        assertEquals("RESULT", ScreenStateRecovery.classifyImageForResultFixture(defeat))
        assertEquals("RESULT", UpstreamScreenStateRecovery.classifyImageForResultFixture(defeat))
        assertFalse(ScreenStateRecovery.classifyImageForResultFixture(defeat) == "RANK_PROGRESS_CONTINUATION")

        val rankKind = ScreenStateRecovery.classifyImageForResultFixture(rankProgress)
        assertEquals("RANK_PROGRESS_CONTINUATION", rankKind)
        assertEquals(
            null,
            ScreenStateRecovery.resultVisibilityForTest(rankKind, 93),
            "rank reward is intermediate, not a terminal result or destination",
        )
        assertEquals(
            PostResultRankProgressPolicy.Action.CONTINUE,
            PostResultRankProgressPolicy.decide(
                rankProgressVisible = rankKind == "RANK_PROGRESS_CONTINUATION",
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                rankProgressInputAttempts = 0,
            ),
        )
        assertEquals(PostResultRankProgressPolicy.Input.CENTER_CLICK, PostResultRankProgressPolicy.inputForAttempt(1))
        assertEquals(PostResultRankProgressPolicy.Input.CENTER_CLICK, PostResultRankProgressPolicy.inputForAttempt(2))
        assertEquals(null, PostResultRankProgressPolicy.inputForAttempt(3))
        assertEquals(
            PostResultRankProgressPolicy.Action.WAIT_FOR_AUTHORIZED_CAPTURE,
            PostResultRankProgressPolicy.decide(true, terminalCleanupAuthorized = false, captureAuthorized = true, 0),
        )

        val settings = loadImage(SCREENSHOT)
        val preOverlay = loadImage(V570_PRE_OVERLAY)
        val unknowns = listOf(settings, preOverlay)
        unknowns.forEach { image ->
            assertFalse(ScreenStateRecovery.classifyImageForResultFixture(image) == "RANK_PROGRESS_CONTINUATION")
            assertFalse(UpstreamScreenStateRecovery.classifyImageForResultFixture(image) == "RANK_PROGRESS_CONTINUATION")
        }

        val incident = requireNotNull(javaClass.getResourceAsStream(V577_RANK_INCIDENT)).bufferedReader().use { it.readText() }
        assertTrue(incident.contains("generation=4 maxInputs=16 maxProbes=20"))
        assertTrue(incident.contains("input=CENTER_CLICK dispatchAccepted=true"))
        assertTrue(incident.contains("input=KEYBOARD_ENTER dispatchAccepted=true"))
        assertTrue(incident.contains("reason=episode-deadline-exceeded inputs=2 probes=3 confirmed=false"))
        assertTrue(incident.contains("PLAYSTATE value=LOST"))
        assertTrue(incident.contains("Current rank is 3"), "the current rank remains ineligible; cleanup does not authorize play")
    }

    @Test
    fun `v597 Gold 4 reward is recognized and terminal cleanup exits to safe hub through automatic pause only`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream(V597_RANK_PROGRESS)).use { it.readBytes() }
        assertEquals(V597_RANK_PROGRESS_SHA256, sha256(bytes))
        val rankReward = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(1920, rankReward.width)
        assertEquals(1080, rankReward.height)
        val rankKind = ScreenStateRecovery.classifyImageForResultFixture(rankReward)
        assertEquals("RANK_PROGRESS_CONTINUATION", rankKind)
        assertEquals(rankKind, UpstreamScreenStateRecovery.classifyImageForResultFixture(rankReward))
        val visual = ScreenStateRecovery.resultVisualEvidenceForFixture(rankReward)
        assertMetricNear(visual, "continue", 0.04609066017645269)
        assertMetricNear(visual, "banner", 0.09054834054834054)
        assertMetricNear(visual, "centerDark", 0.19529744636653415)
        assertMetricNear(visual, "bannerWarm", 0.4039201539201539)
        assertFalse(ResultPageEvidencePolicy.looksLikeResultVisual(0.046, 0.091, 0.195, 0.404))

        // Recreate proof from the same-game authoritative terminal sequence.
        val tracker = CurrentGamePowerLogTerminalTracker()
        tracker.observeLine("CREATE_GAME gameId=gold4-rank-reward")
        val identity = requireNotNull(tracker.currentGameIdentity("laz#12793"))
        MandatoryRankSurrenderGuard.begin(identity)
        tracker.observeLine("TAG_CHANGE Entity=laz#12793 tag=PLAYSTATE value=CONCEDED")
        tracker.observeLine("TAG_CHANGE Entity=laz#12793 tag=PLAYSTATE value=LOST")
        tracker.observeLine("TAG_CHANGE Entity=xXTHUGXx#1184 tag=PLAYSTATE value=WON")
        tracker.observeLine("TAG_CHANGE Entity=GameEntity tag=STEP value=FINAL_GAMEOVER")
        tracker.observeLine("TAG_CHANGE Entity=GameEntity tag=STATE value=COMPLETE")
        val proof = requireNotNull(tracker.currentGameSurrenderEvidence("laz#12793", "xXTHUGXx#1184"))
        assertEquals("LOST", proof.ownPlayState)
        val cleanupCapability = requireNotNull(MandatoryRankSurrenderGuard.authorizeTerminalCleanup(proof))
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", cleanupCapability))

        // The recognized reward is the only state allowed to keep terminal UI
        // cleanup running through an automatic unresolved-screen pause.
        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = requireNotNull(coordinator.begin().ticket)
        assertEquals(
            PostResultRankProgressPolicy.Action.CONTINUE,
            PostResultRankProgressPolicy.decide(
                rankProgressVisible = rankKind == "RANK_PROGRESS_CONTINUATION",
                terminalCleanupAuthorized = MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(cleanupCapability),
                captureAuthorized = true,
                rankProgressInputAttempts = coordinator.snapshot().rankProgressInputs,
            ),
        )
        val terminalDispatchAllowed = ActionDispatchGate.allowForState(
            action = "terminal-result.dismiss",
            paused = true,
            working = false,
            terminalCleanupPending = true,
            terminalCleanupCapabilityValid = MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(cleanupCapability),
            automaticPause = true,
        )
        assertTrue(terminalDispatchAllowed)
        var mockClientScreen = rankKind
        var targetAcceptedInputs = 0
        val reservedRankInput = if (terminalDispatchAllowed) {
            coordinator.reserveRankProgressInput(ticket, PostResultRankProgressPolicy.MAX_CONTINUE_INPUTS)
        } else null
        val rankInput = reservedRankInput?.let(PostResultRankProgressPolicy::inputForAttempt)
        if (rankInput == PostResultRankProgressPolicy.Input.CENTER_CLICK) {
            // Offline target adapter accepts the input and models the visible transition.
            targetAcceptedInputs++
            mockClientScreen = "HOME"
        }
        assertEquals(1, targetAcceptedInputs, "the simulated client accepts one rank-reward Continue input")
        assertEquals(PostResultRankProgressPolicy.Input.CENTER_CLICK, rankInput)
        assertFalse(
            ResultPageDismissalPolicy.shouldStopWorker(
                paused = true,
                gameplayMode = false,
                terminalCleanupCapabilityValid = true,
                automaticPause = true,
            ),
            "the valid terminal worker must continue through the automatic unresolved-screen pause",
        )
        assertTrue(
            ResultPageDismissalPolicy.shouldStopWorker(
                paused = true,
                gameplayMode = false,
                terminalCleanupCapabilityValid = true,
                automaticPause = false,
            ),
            "manual pause still stops cleanup dispatch",
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "terminal-result.dismiss",
                paused = true,
                working = false,
                terminalCleanupPending = true,
                terminalCleanupCapabilityValid = true,
                automaticPause = false,
            ),
            "F2/manual pause must still block the same UI input",
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "matchmaking.start",
                paused = true,
                working = false,
                terminalCleanupPending = true,
                terminalCleanupCapabilityValid = true,
                automaticPause = true,
            ),
            "cleanup proof cannot turn into matchmaking authorization",
        )

        val homeDestination = FreshPostResultDestinationPolicy.isConfirmed(
            mockClientScreen,
            95,
            freshCaptureAuthorized = true,
        )
        assertTrue(homeDestination, "fresh recognized HOME is a safe post-result destination")
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 2,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = 1,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                destinationTransitionConfirmed = homeDestination,
            ),
        )
        assertTrue(coordinator.confirmDestination(ticket), "fresh HOME confirmation completes the cleanup episode")
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED", cleanupCapability))
        assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "matchmaking.start",
                paused = true,
                working = false,
                automaticPause = true,
            ),
            "the script remains paused at HOME and cannot start a queue after terminal cleanup",
        )
    }

    private fun loadImage(resource: String): BufferedImage {
        val bytes = requireNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }
        return requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
    }

    private fun assertMetricNear(evidence: String, name: String, expected: Double) {
        val value = Regex("(?:^|\\s)${Regex.escape(name)}=([0-9]+\\.[0-9]+)")
            .find(evidence)
            ?.groupValues
            ?.get(1)
            ?.toDouble()
        assertTrue(value != null && kotlin.math.abs(value - expected) < 0.000001, "$name expected=$expected evidence=$evidence")
    }

    @Test
    fun `v571 live defeat panel variants are recognized and terminal cleanup stays single dispatch`() {
        val screenshots = listOf(
            V571_RESULT_185448 to V571_RESULT_185448_SHA256,
            V571_RESULT_185648 to V571_RESULT_185648_SHA256,
        )
        val images = screenshots.map { (resource, expectedHash) ->
            val bytes = requireNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }
            assertEquals(expectedHash, sha256(bytes))
            requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        }
        images.forEach { image ->
            assertEquals("RESULT", ScreenStateRecovery.classifyImageForResultFixture(image))
            assertEquals("RESULT", UpstreamScreenStateRecovery.classifyImageForResultFixture(image))
        }
        val visual = ScreenStateRecovery.resultVisualEvidenceForFixture(images.last())
        assertTrue(visual.contains("bannerWarm=0.419"), visual)

        val incident = requireNotNull(javaClass.getResourceAsStream(V571_LOG)).bufferedReader().use { it.readText() }
        val ownLost = incident.indexOf("PLAYSTATE=LOST")
        val finalGameOver = incident.indexOf("FINAL_GAMEOVER")
        val proofAccepted = incident.indexOf("playerTerminal=LOST")
        val cleanupFailed = incident.indexOf("RESULT_PAGE_CLEANUP_FAILED")
        assertTrue(ownLost >= 0 && finalGameOver > ownLost && proofAccepted > finalGameOver && cleanupFailed > proofAccepted)
        assertTrue(incident.contains("inputs=0 probes=3"), "failed experiment should prove no input was dispatched")

        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = requireNotNull(coordinator.begin().ticket)
        val probe = requireNotNull(coordinator.nextProbe(ticket))
        assertEquals(1, coordinator.reserveInput(ticket))
        assertEquals(1, coordinator.snapshot().inputs)
        assertEquals("CENTER_CLICK", GameUtil.terminalResultInputForAttempt(1)?.name)
        assertTrue(probe > 0)
    }

    @Test
    fun `v574 live defeat plaque variant is actionable only with terminal proof and fresh capture`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream(V574_DEFEAT_RESULT)).use { it.readBytes() }
        assertEquals(V574_DEFEAT_RESULT_SHA256, sha256(bytes))
        val defeat = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(1920, defeat.width)
        assertEquals(1080, defeat.height)
        assertEquals("RESULT", ScreenStateRecovery.classifyImageForResultFixture(defeat))
        assertEquals("RESULT", UpstreamScreenStateRecovery.classifyImageForResultFixture(defeat))
        val visual = ScreenStateRecovery.resultVisualEvidenceForFixture(defeat)
        assertTrue(visual.contains("continue=0.04"), visual)
        assertTrue(visual.contains("banner=0.52"), visual)
        assertTrue(visual.contains("bannerWarm=0.41"), visual)
        assertTrue(visual.contains("centerDark=0.229"), visual)

        val rankProgress = loadImage(V572_RANK_PROGRESS)
        val settings = loadImage(SCREENSHOT)
        val preOverlay = loadImage(V570_PRE_OVERLAY)
        listOf(rankProgress, settings, preOverlay).forEach { negative ->
            assertFalse(ScreenStateRecovery.classifyImageForResultFixture(negative) == "RESULT")
            assertFalse(UpstreamScreenStateRecovery.classifyImageForResultFixture(negative) == "RESULT")
        }

        val incident = requireNotNull(javaClass.getResourceAsStream(V574_DEFEAT_INCIDENT)).bufferedReader().use { it.readText() }
        assertTrue(incident.contains("own laz#12793 PLAYSTATE=CONCEDED"))
        assertTrue(incident.contains("own laz#12793 PLAYSTATE=LOST"))
        assertTrue(incident.contains("opponent FwHLRB#3332 PLAYSTATE=WON"))
        assertTrue(incident.contains("STATE=COMPLETE"))
        assertTrue(incident.contains("inputs=0 probes=3 confirmed=false dispatch=false"))
        assertTrue(incident.contains("SCREEN_RECOVERY_OBSERVATION provider=LEGACY detected=UNKNOWN"))

        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 1,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = 0,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                visualOnlyResultEvidence = true,
            ),
            "the current-process capture plus same-game terminal capability admits a bounded Continue input",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 1,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = 0,
                terminalCleanupAuthorized = false,
                captureAuthorized = true,
                visualOnlyResultEvidence = true,
            ),
            "recognition alone must not authorize Continue without terminal proof",
        )
        assertEquals("CENTER_CLICK", GameUtil.terminalResultInputForAttempt(1)?.name)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02X".format(it) }

    companion object {
        private const val SCREENSHOT = "/club/xiaojiawei/hsscript/status/surrender/v569-settings-menu-after-terminal-20261005-171244-936.png"
        private const val LOG = "/club/xiaojiawei/hsscript/status/surrender/v569-settings-menu-terminal-cleanup-20261005-1712.txt"
        private const val SCREENSHOT_SHA256 = "8A86E3B2AFC054954CC7C9B05D44F73238B8DF5751DA10545240EFF4AAB0467A"
        private const val RANK_RESULT_SCREENSHOT = "/club/xiaojiawei/hsscript/status/surrender/v568-rank-result-screen-20261005-160420-871.png"
        private const val V570_PRE_OVERLAY = "/club/xiaojiawei/hsscript/status/surrender/v570-pre-overlay-20261005-181521-209.png"
        private const val V570_RESULT = "/club/xiaojiawei/hsscript/status/surrender/v570-defeat-result-20261005-181755-903.png"
        private const val V570_LOG = "/club/xiaojiawei/hsscript/status/surrender/v570-terminal-result-cleanup-20261005-1815.txt"
        private const val V570_PRE_OVERLAY_SHA256 = "63BDC7A2523ACF203966C5E6270B95A7A736047FB87DEFED7A45C834DC0509BF"
        private const val V570_RESULT_SHA256 = "47EB19615CC0D1D4F936FA8DB8F718D92228119C95AD77F27F8EECC9F7CE8E96"
        private const val V571_RESULT_185448 = "/club/xiaojiawei/hsscript/status/surrender/v571-defeat-result-20261005-185448-467.png"
        private const val V571_RESULT_185648 = "/club/xiaojiawei/hsscript/status/surrender/v571-defeat-result-20261005-185648-493.png"
        private const val V571_RESULT_185448_SHA256 = "B5764586B997F81D7219496E4E8BD7410297F1B910A5BE01A6D867D040275217"
        private const val V571_RESULT_185648_SHA256 = "DCA54D3694EBA9A514A431DD2C2A3408E0EA01C35D503F3BA7B3F29B28E2DB82"
        private const val V571_LOG = "/club/xiaojiawei/hsscript/status/surrender/v571-terminal-result-cleanup-20261005-1854.txt"
        private const val V572_RANK_PROGRESS = "/club/xiaojiawei/hsscript/status/surrender/v572-rank-progression-20261005-195449-255.png"
        private const val V572_RANK_PROGRESS_SHA256 = "A4E440C16425D13169345588302DDA559A5AF28656F8C4A9F46EB50A789ADEE7"
        private const val V574_DEFEAT_RESULT = "/club/xiaojiawei/hsscript/status/surrender/v574-defeat-result-20261005-204311-968.png"
        private const val V574_DEFEAT_INCIDENT = "/club/xiaojiawei/hsscript/status/surrender/v574-defeat-result-cleanup-incident.txt"
        private const val V574_DEFEAT_RESULT_SHA256 = "75E4D321FFCD6285C99D6F0545E4E0935F1B149E8A364BAAA299E66A318740A8"
        private const val V575_RANK_PROGRESS = "/club/xiaojiawei/hsscript/status/surrender/v575-rank-progress-warm-20261005-211215-089.png"
        private const val V575_RANK_INCIDENT = "/club/xiaojiawei/hsscript/status/surrender/v575-rank-progress-cleanup-incident.txt"
        private const val V575_RANK_PROGRESS_SHA256 = "2FF3E87C2B67CE38C27FC6C825AC3CD6B00A8481223ED79E46D0F037029A9D2E"
        private const val V576_DEFEAT_RESULT = "/club/xiaojiawei/hsscript/status/surrender/v576-defeat-result-low-center-dark-20261005-213924-000.png"
        private const val V576_DEFEAT_INCIDENT = "/club/xiaojiawei/hsscript/status/surrender/v576-defeat-result-cleanup-incident.txt"
        private const val V576_DEFEAT_RESULT_SHA256 = "C843DB524222EE76CEBBF91040F7FBBA25F31DFD83F608F047726C091F5001FB"
        private const val V577_RANK_PROGRESS = "/club/xiaojiawei/hsscript/status/surrender/v577-rank-up-gold3-20261005-221531-323.png"
        private const val V577_RANK_INCIDENT = "/club/xiaojiawei/hsscript/status/surrender/v577-rank-up-cleanup-incident.txt"
        private const val V577_RANK_PROGRESS_SHA256 = "383BA353BCA5459CAA12C41EA4522284DE369AECC32A294209A0F523ED288837"
        private const val V597_RANK_PROGRESS = "/club/xiaojiawei/hsscript/status/surrender/v597-gold4-rank-progress-unrecognized-20261007-125248-573.png"
        private const val V597_RANK_PROGRESS_SHA256 = "A670A8A140D48037D11C9B2805DD9641594F2E82F487D5D4CCA4B65B9EF76794"
    }
}
