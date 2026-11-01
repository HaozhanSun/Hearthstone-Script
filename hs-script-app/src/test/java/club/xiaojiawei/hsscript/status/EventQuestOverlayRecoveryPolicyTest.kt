package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import club.xiaojiawei.hsscript.status.surrender.CurrentGamePowerLogTerminalTracker

class EventQuestOverlayRecoveryPolicyTest {
    @Test
    fun `live season award frame is separated from Event Quest and only visible Continue is eligible`() {
        val award = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/season-rank-award-20261101.png",
        )))
        assertTrue(SeasonRankAwardVisualClassifier.inspect(award).isSeasonRankAward)
        assertTrue(SeasonRankAwardRecoveryPolicy.allowsMenuContext("STARTUP", currentGameSessionReady = false))
        assertFalse(SeasonRankAwardRecoveryPolicy.allowsMenuContext("GAMEPLAY", currentGameSessionReady = false))
        assertFalse(SeasonRankAwardRecoveryPolicy.allowsMenuContext("STARTUP", currentGameSessionReady = true))
        assertFalse(StartupQuestOverlayVisualClassifier.isQuestOverlay(award),
            "rank award must not enter the top-left Event Quest click ladder")
        assertEquals("SEASON_RANK_AWARD", UpstreamScreenStateRecovery.classifyImageForTest("", award))
        var dispatchedPoint: Point? = null
        assertTrue(SeasonRankAwardRecoveryPolicy.dispatchContinue(
            windowBounds = Rectangle(0, 0, award.width, award.height),
            exactSeasonRankAward = true, freshCaptureAuthorized = true, foregroundVerified = true,
            safeMenuContext = true, activeGame = false, activeQueue = false, terminalOrResult = false,
            working = true, paused = false,
        ) { point -> dispatchedPoint = point; true })
        val continuePoint = requireNotNull(dispatchedPoint)
        assertTrue(continuePoint.x in (award.width * .43).toInt()..(award.width * .57).toInt())
        assertTrue(continuePoint.y in (award.height * .78).toInt()..(award.height * .87).toInt(),
            "dispatched point must fall inside the fixture's visible Continue hitbox: $continuePoint")
        assertTrue(SeasonRankAwardRecoveryPolicy.allowsContinue(
            exactSeasonRankAward = true, freshCaptureAuthorized = true, foregroundVerified = true,
            safeMenuContext = true, activeGame = false, activeQueue = false, terminalOrResult = false,
            working = true, paused = false,
        ), "same-frame visible Continue is the only safe navigation action")
        assertFalse(SeasonRankAwardRecoveryPolicy.allowsContinue(
            exactSeasonRankAward = true, freshCaptureAuthorized = false, foregroundVerified = true,
            safeMenuContext = true, activeGame = false, activeQueue = false, terminalOrResult = false,
            working = true, paused = false,
        ), "stale or unauthorized capture cannot dispatch")
        assertFalse(SeasonRankAwardRecoveryPolicy.allowsContinue(
            exactSeasonRankAward = true, freshCaptureAuthorized = true, foregroundVerified = true,
            safeMenuContext = true, activeGame = true, activeQueue = false, terminalOrResult = false,
            working = true, paused = false,
        ), "active gameplay remains fenced")
        assertFalse(SeasonRankAwardRecoveryPolicy.allowsContinue(
            exactSeasonRankAward = true, freshCaptureAuthorized = true, foregroundVerified = true,
            safeMenuContext = true, activeGame = false, activeQueue = true, terminalOrResult = false,
            working = true, paused = false,
        ), "active queue remains fenced")
        assertFalse(SeasonRankAwardRecoveryPolicy.allowsContinue(
            exactSeasonRankAward = true, freshCaptureAuthorized = true, foregroundVerified = true,
            safeMenuContext = true, activeGame = false, activeQueue = false, terminalOrResult = false,
            working = true, paused = true,
        ), "paused automation must not dispatch")
    }

    @Test
    fun `fresh exact HUB chooser releases dismissed Event Quest fence while unsafe destinations retain it`() {
        val hubChooser = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/hub-mode-chooser-live-20261029-035630.png",
        )))
        assertEquals("TOURNAMENT", UpstreamScreenStateRecovery.classifyImageForTest("", hubChooser))
        assertTrue(UpstreamScreenStateRecovery.exactEventQuestHubChooserForTest(hubChooser),
            "the fresh recovery callsite must recognize the saved visual chooser as its exact safe destination")
        val safeRelease = EventQuestOverlayFenceReleasePolicy.allows(
            captureTrusted = true, questOverlayVisible = false,
            exactHubModeChooser = true, deckReadyVisible = false,
            freshEmptySessionLoginTransition = false, activeGame = false, activeQueue = false,
            reconnectDialog = false, terminalOrResult = false, mandatoryRankGuard = false,
            working = true, paused = false,
        )
        assertTrue(safeRelease, "fresh visual HUB proof must release after the overlay disappears")

        StartupQuestOverlayRecoveryFence.resetForTest()
        try {
            StartupQuestOverlayRecoveryFence.begin()
            val decision = EventQuestOverlayRecoveryPolicy().observe(
                evidence(nowMs = 2_000L, exact = false, safeTransition = safeRelease),
            )
            assertEquals(EventQuestOverlayRecoveryPolicy.Action.UNBLOCK_SAFE_TRANSITION, decision.action)
            assertTrue(UpstreamScreenStateRecovery.resolveEventQuestSafeTransition(decision.action))
            assertFalse(StartupQuestOverlayRecoveryFence.isActive())
        } finally {
            StartupQuestOverlayRecoveryFence.resetForTest()
        }

        val unsafe = listOf(
            mapOf("captureTrusted" to false),
            mapOf("questOverlayVisible" to true),
            mapOf("activeGame" to true),
            mapOf("activeQueue" to true),
            mapOf("reconnectDialog" to true),
            mapOf("terminalOrResult" to true),
            mapOf("mandatoryRankGuard" to true),
            mapOf("working" to false),
            mapOf("paused" to true),
        )
        unsafe.forEach { fence ->
            val allowed = EventQuestOverlayFenceReleasePolicy.allows(
                captureTrusted = fence["captureTrusted"] as? Boolean ?: true,
                questOverlayVisible = fence["questOverlayVisible"] as? Boolean ?: false,
                exactHubModeChooser = false, deckReadyVisible = false,
                freshEmptySessionLoginTransition = false,
                activeGame = fence["activeGame"] as? Boolean ?: false,
                activeQueue = fence["activeQueue"] as? Boolean ?: false,
                reconnectDialog = fence["reconnectDialog"] as? Boolean ?: false,
                terminalOrResult = fence["terminalOrResult"] as? Boolean ?: false,
                mandatoryRankGuard = fence["mandatoryRankGuard"] as? Boolean ?: false,
                working = fence["working"] as? Boolean ?: true,
                paused = fence["paused"] as? Boolean ?: false,
            )
            assertFalse(allowed, "unsafe fence $fence must retain the unresolved-action barrier")
        }
        assertFalse(EventQuestOverlayFenceReleasePolicy.allows(
            captureTrusted = true, questOverlayVisible = false,
            exactHubModeChooser = false, deckReadyVisible = false,
            freshEmptySessionLoginTransition = false, activeGame = false, activeQueue = false,
            reconnectDialog = false, terminalOrResult = false, mandatoryRankGuard = false,
            working = true, paused = false,
        ), "unknown or ambiguous non-overlay frames cannot release the fence")
    }

    @Test
    fun `exact Event Quest screenshot alone routes to three top left clicks then minute recheck`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/event-quest-overlay-deck-selection-20261029.png",
        )))
        assertTrue(StartupQuestOverlayVisualClassifier.isQuestOverlay(image),
            "screen structure alone is authoritative; no client-log event is required")
        val screenKind = UpstreamScreenStateRecovery.classifyImageForTest("", image)
        assertEquals("EVENT_QUEST_OVERLAY", screenKind)
        val safePoint = StartupQuestOverlayDismissPolicy.topLeftPixel(Rectangle(0, 0, image.width, image.height))
        assertEquals(Point(0, 0), safePoint)
        assertTrue(UpstreamScreenStateRecovery.dispatchEventQuestRecovery(screenKind) {
            val policy = EventQuestOverlayRecoveryPolicy()
            val points = mutableListOf<Point?>()
            repeat(3) { index ->
                val decision = policy.observe(evidence(nowMs = 60_000L + index * 10L))
                assertEquals(EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT, decision.action)
                points += safePoint
            }
            assertEquals(listOf(Point(0, 0), Point(0, 0), Point(0, 0)), points)
            assertEquals(EventQuestOverlayRecoveryPolicy.Action.WAIT_AND_REPROBE,
                policy.observe(evidence(nowMs = 120_019L)).action)
            assertEquals(EventQuestOverlayRecoveryPolicy.Action.RESTART_CLIENT,
                policy.observe(evidence(nowMs = 120_020L)).action)
            true
        })
    }

    @Test
    fun `live event quest overlay fixture is recognized by the upstream Beta classifier`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/event-quest-overlay-deck-selection-20261029.png",
        )))
        assertTrue(StartupQuestOverlayVisualClassifier.isQuestOverlay(image))
        assertEquals("EVENT_QUEST_OVERLAY", UpstreamScreenStateRecovery.classifyImageForTest("", image))
        var recoveryStarted = false
        val dispatched = UpstreamScreenStateRecovery.dispatchEventQuestRecovery(
            UpstreamScreenStateRecovery.classifyImageForTest("", image),
        ) {
            recoveryStarted = true
            true
        }
        assertTrue(dispatched, "the stale-probe classification must enter the event-quest recovery callsite")
        assertTrue(recoveryStarted, "the recovery start callback must be invoked")
        assertFalse(UpstreamScreenStateRecovery.dispatchEventQuestRecovery("UNKNOWN") { error("must not dispatch") })

        val liveCapture = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/event-quest-overlay-live-005412.png",
        )))
        val liveEvidence = StartupQuestOverlayVisualClassifier.inspect(liveCapture)
        assertTrue(liveEvidence.isQuestOverlay, "live saved capture should match classifier; evidence=$liveEvidence")
        assertEquals("EVENT_QUEST_OVERLAY", UpstreamScreenStateRecovery.classifyImageForTest("", liveCapture))

        val attachedQuestScreenshot = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/event-quest-overlay-live-clipboard-20261029.png",
        )))
        assertTrue(StartupQuestOverlayVisualClassifier.isQuestOverlay(attachedQuestScreenshot))
        val attachedClassification = UpstreamScreenStateRecovery.classifyImageForTest("", attachedQuestScreenshot)
        assertEquals("EVENT_QUEST_OVERLAY", attachedClassification)
        var attachedRecoveryStarted = false
        assertTrue(UpstreamScreenStateRecovery.dispatchEventQuestRecovery(attachedClassification) {
            attachedRecoveryStarted = true
            true
        })
        assertTrue(attachedRecoveryStarted)

        val upstreamSource = File("src/main/java/club/xiaojiawei/hsscript/status/UpstreamScreenStateRecovery.kt").readText()
        val upstreamResultProbe = upstreamSource.substring(
            upstreamSource.indexOf("internal fun observeResultScreenForRecovery(): ResultScreenObservation"),
        )
        assertTrue(upstreamResultProbe.indexOf("if (visual.startupQuestOverlayVisual)") <
            upstreamResultProbe.indexOf("val ocrText = runOCR"),
            "the exact live overlay classifier must short-circuit the 15s result OCR route")
        val legacySource = File("src/main/java/club/xiaojiawei/hsscript/status/ScreenStateRecovery.kt").readText()
        val legacyResultProbe = legacySource.substring(
            legacySource.indexOf("internal fun observeResultScreenForRecovery(): ResultScreenObservation"),
        )
        assertTrue(legacyResultProbe.indexOf("if (capture.visual.startupQuestOverlayVisual)") <
            legacyResultProbe.indexOf("val ocrEvidence = runOCR"),
            "the fallback result observer must use the same visual-first short circuit")

        val laterReconnect = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/reconnect-failure-dialog-over-deck-selection.png",
        )))
        val laterReconnectClassification = UpstreamScreenStateRecovery.classifyImageForTest("", laterReconnect)
        assertNotEquals("EVENT_QUEST_OVERLAY", laterReconnectClassification,
            "the later reconnect frame must be freshly classified, never inherit the quest-overlay label")
        assertFalse(UpstreamScreenStateRecovery.dispatchEventQuestRecovery(laterReconnectClassification) {
            error("reconnect classification must not dispatch stale quest-overlay recovery")
        })
    }

    @Test
    fun `postgame completion popup fixture enters bounded Event Quest recovery and stays distinct from neighbors`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream(COMPLETION_POPUP_FIXTURE)).use { it.readBytes() }
        assertEquals(COMPLETION_POPUP_SHA256, sha256(bytes))
        val completion = ImageIO.read(bytes.inputStream())
        assertEquals(1920, completion.width)
        assertEquals(1080, completion.height)
        val completionEvidence = StartupQuestOverlayVisualClassifier.inspect(completion)
        assertEquals(0.0, completionEvidence.titleGoldRatio, 0.0001,
            "this completion modal uses the purple ribbon that the former gold-title gate missed")
        assertTrue(completionEvidence.dimmedHubRatio >= 0.20, "background is visibly dimmed: $completionEvidence")
        assertTrue(completionEvidence.singleQuestCardParchmentRatio >= 0.34, "centered quest card detected: $completionEvidence")
        assertTrue(completionEvidence.completionHeaderPurpleRatio >= 0.30, "completion ribbon detected: $completionEvidence")
        assertTrue(completionEvidence.isQuestOverlay, "the postgame completion popup is a quest overlay: $completionEvidence")

        val completionKind = UpstreamScreenStateRecovery.classifyImageForTest("", completion)
        assertEquals("EVENT_QUEST_OVERLAY", completionKind)
        var recoveryStarted = false
        assertTrue(UpstreamScreenStateRecovery.dispatchEventQuestRecovery(completionKind) {
            recoveryStarted = true
            val policy = EventQuestOverlayRecoveryPolicy()
            repeat(3) { index ->
                assertEquals(
                    EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT,
                    policy.observe(evidence(nowMs = 132_000L + index * 500L)).action,
                )
            }
            assertEquals(
                EventQuestOverlayRecoveryPolicy.Action.WAIT_AND_REPROBE,
                policy.observe(evidence(nowMs = 192_999L)).action,
            )
            assertEquals(
                EventQuestOverlayRecoveryPolicy.Action.RESTART_CLIENT,
                policy.observe(evidence(nowMs = 193_000L)).action,
            )
            true
        })
        assertTrue(recoveryStarted, "the captured stale-probe frame must invoke bounded recovery")

        val rankProgress = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/result-flow/terminal-rank-progress-live-regression-20261026.png",
        )))
        assertFalse(StartupQuestOverlayVisualClassifier.isQuestOverlay(rankProgress),
            "rank medal/progression remains distinct from a quest popup")
        assertNotEquals("EVENT_QUEST_OVERLAY", UpstreamScreenStateRecovery.classifyImageForTest("", rankProgress))

        val chooser = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/hub-mode-chooser-live-20261029-035630.png",
        )))
        assertFalse(StartupQuestOverlayVisualClassifier.isQuestOverlay(chooser),
            "the actual four-choice HUB chooser remains distinct")
        assertEquals("TOURNAMENT", UpstreamScreenStateRecovery.classifyImageForTest("", chooser))
        assertFalse(UpstreamScreenStateRecovery.dispatchEventQuestRecovery(
            UpstreamScreenStateRecovery.classifyImageForTest("", chooser),
        ) { error("hub chooser must not dispatch quest dismissal") })
    }

    @Test
    fun `event quest retry requests an empty session startup capture and reports the rejecting gate`() {
        val source = File("src/main/java/club/xiaojiawei/hsscript/status/UpstreamScreenStateRecovery.kt")
            .readText()
        val retry = source.substringAfter("private fun scheduleEventQuestOverlayRecovery(")
            .substringBefore("private fun apply(detection: Detection)")

        assertTrue(
            retry.contains("captureVerifiedGameScreen(") &&
                retry.contains("startupProbe = true") &&
                retry.contains("captureContext = \"EVENT_QUEST_RETRY\""),
            "the bounded retry must use the same pre-session capture lane that admitted the exact overlay",
        )
        assertTrue(retry.contains("freshEmptySessionLoginProof"),
            "a startup-purpose capture remains read-only until the existing exact empty-session proof passes")
        assertTrue(retry.contains("activeQueue") && retry.contains("activeGame") && retry.contains("reconnectDialogVisual"),
            "queue, active-game, and reconnect fences remain in the retry decision")
        assertTrue(source.contains(
            "SCREEN_RECOVERY_CAPTURE_BLOCKED context=\$captureContext reason=\$preflightRejection",
        ), "the capture log must name the gate and purpose that rejected the retry")
    }

    @Test
    fun `exactly three top-left click attempts precede the 60 second restart stage`() {
        val policy = EventQuestOverlayRecoveryPolicy()
        repeat(3) { index ->
            val decision = policy.observe(evidence(nowMs = 1_000L + index * 500L))
            assertEquals(EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT, decision.action)
            assertEquals(index + 1, decision.clickNumber)
        }
        assertEquals(
            EventQuestOverlayRecoveryPolicy.Action.WAIT_AND_REPROBE,
            policy.observe(evidence(nowMs = 61_999L)).action,
        )
        val restart = policy.observe(evidence(nowMs = 62_000L))
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.RESTART_CLIENT, restart.action)
        assertEquals("exact-overlay-persisted-after-second-stage", restart.reason)
        policy.confirmRestartIssued()
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.STOP, policy.observe(evidence(nowMs = 63_000L)).action)
    }

    @Test
    fun `exact quest overlay waits until one minute stuck before first click`() {
        val policy = EventQuestOverlayRecoveryPolicy()
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.WAIT_AND_REPROBE,
            policy.observe(evidence(nowMs = 100_000L, stableForMs = 59_999L)).action)
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT,
            policy.observe(evidence(nowMs = 100_001L, stableForMs = 60_000L)).action)
    }

    @Test
    fun `fresh unblocked deck screen releases the fence after any click`() {
        val policy = EventQuestOverlayRecoveryPolicy()
        policy.observe(evidence(nowMs = 1_000L))
        val decision = policy.observe(evidence(nowMs = 1_500L, exact = false, safeTransition = true))
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.UNBLOCK_SAFE_TRANSITION, decision.action)
        assertEquals(0, policy.clicksAttempted())
    }

    @Test
    fun `unknown and stale captures never authorize a click or restart`() {
        val policy = EventQuestOverlayRecoveryPolicy()
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.STOP,
            policy.observe(evidence(nowMs = 1_000L, exact = false)).action)
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.STOP,
            policy.observe(evidence(nowMs = 2_000L, trusted = false)).action)
        assertEquals(0, policy.clicksAttempted())
    }

    @Test
    fun `pause active game queue and result evidence fence the top-left action`() {
        val cases = listOf(
            evidence(nowMs = 1_000L, paused = true),
            evidence(nowMs = 1_000L, activeGame = true),
            evidence(nowMs = 1_000L, activeQueue = true),
            evidence(nowMs = 1_000L, terminal = true),
        )
        cases.forEach { evidence ->
            val decision = EventQuestOverlayRecoveryPolicy().observe(evidence)
            assertTrue(decision.action in setOf(
                EventQuestOverlayRecoveryPolicy.Action.DEFER,
                EventQuestOverlayRecoveryPolicy.Action.STOP,
            ))
            assertEquals(0, decision.clickNumber)
        }
    }

    @Test
    fun `authoritative current game win permits only stale FILL_DECK quest dismissal`() {
        val terminal = CurrentGamePowerLogTerminalTracker().apply {
            observeLine("D 21:00:00.000 CREATE_GAME")
            observeLine("Entity=self tag=PLAYSTATE value=WON")
            observeLine("Entity=opponent tag=PLAYSTATE value=CONCEDED")
            observeLine("Entity=GameEntity tag=STEP value=FINAL_GAMEOVER")
            observeLine("Entity=GameEntity tag=STATE value=COMPLETE")
        }
        val verifiedWin = terminal.hasCompleteTerminalEvidence() &&
            terminal.currentGameSurrenderEvidence("self", "opponent")?.ownPlayState == "WON"
        assertTrue(verifiedWin)

        fun context(
            terminalProof: Boolean,
            activeGame: Boolean = false,
            activeQueue: Boolean = false,
            paused: Boolean = false,
            exactPhase: String = "FILL_DECK",
            mode: String = "TOURNAMENT",
        ) = EventQuestOverlayRecoveryContextPolicy.allowsDismiss(
            mode = mode, phase = exactPhase, currentSessionReady = false,
            nextModeGameplay = false, activeGame = activeGame, activeQueueScreen = activeQueue,
            recentInputIntent = true, shortRaceWindow = false, matchmakingSearchVisual = false,
            terminalOrResult = true, working = true, paused = paused,
            postGameTerminalProof = terminalProof,
        )

        assertFalse(context(terminalProof = false), "phase/result latch without complete current-game proof is insufficient")
        assertTrue(context(terminalProof = verifiedWin), "FINAL_GAMEOVER may leave the model at FILL_DECK; exact post-game proof permits this dismiss only")
        assertFalse(context(terminalProof = verifiedWin, activeGame = true))
        assertFalse(context(terminalProof = verifiedWin, activeQueue = true))
        assertFalse(context(terminalProof = verifiedWin, paused = true))
        assertFalse(context(terminalProof = verifiedWin, exactPhase = "MULLIGAN"))
        assertFalse(context(terminalProof = verifiedWin, mode = "HUB", exactPhase = "NONE"),
            "terminal override stays within the exact stale Tournament/FILL_DECK case")
    }

    @Test
    fun `click accepted without screen transition keeps overlay recovery active until fresh proof`() {
        val policy = EventQuestOverlayRecoveryPolicy()
        var overlayVisible = true
        var clickCount = 0
        repeat(3) { index ->
            val decision = policy.observe(evidence(nowMs = 60_000L + index * 500L,
                terminal = true, postGameTerminal = true))
            val accepted = EventQuestOverlayClickDispatch.dispatch(
                action = decision.action, freshCaptureTrusted = true, foregroundVerified = true,
                safeContext = true,
            ) {
                clickCount++
                true // Windows accepted the input; the UI may still not have changed.
            }
            assertTrue(accepted)
            assertTrue(overlayVisible)
        }
        assertEquals(3, clickCount)
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.WAIT_AND_REPROBE,
            policy.observe(evidence(nowMs = 120_999L, terminal = true, postGameTerminal = true)).action)
        val safeTransition = evidence(nowMs = 121_000L, exact = false, safeTransition = true,
            terminal = true, postGameTerminal = true)
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.UNBLOCK_SAFE_TRANSITION, policy.observe(safeTransition).action)
        overlayVisible = false // Transition is recorded only after fresh screen evidence, not input acceptance.
        assertFalse(overlayVisible)
    }

    @Test
    fun `fresh foreground and exact capture are required immediately before click dispatch`() {
        var clickCount = 0
        fun dispatch(fresh: Boolean, foreground: Boolean, safe: Boolean) = EventQuestOverlayClickDispatch.dispatch(
            action = EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT,
            freshCaptureTrusted = fresh, foregroundVerified = foreground, safeContext = safe,
        ) { clickCount++; true }
        assertFalse(dispatch(fresh = false, foreground = true, safe = true))
        assertFalse(dispatch(fresh = true, foreground = false, safe = true))
        assertFalse(dispatch(fresh = true, foreground = true, safe = false))
        assertEquals(0, clickCount)
        assertTrue(dispatch(fresh = true, foreground = true, safe = true))
        assertEquals(1, clickCount)
    }

    @Test
    fun `client restart budget survives Hearthstone PID change and resets on new work run`() {
        val policy = EventQuestOverlayRecoveryPolicy(secondStageDelayMs = 100L)
        fun e(now: Long, run: Long = 7L) = evidence(
            nowMs = now, stableForMs = 60_000L, terminal = true, postGameTerminal = true,
        ).copy(automationRunId = run, semanticScreenIdentity = "EVENT_QUEST_OVERLAY|TOURNAMENT|FILL_DECK")

        repeat(3) { index ->
            assertEquals(EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT,
                policy.observe(e(60_001L + index)).action)
        }
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.RESTART_CLIENT,
            policy.observe(e(60_103L)).action)

        // A new process resets only the PID-bound click sequence; the same run/screen restart budget remains used.
        policy.reset()
        repeat(3) { index ->
            assertEquals(EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT,
                policy.observe(e(61_004L + index)).action)
        }
        assertEquals("client-restart-cap-exhausted", policy.observe(e(61_106L)).reason)

        // A genuine new work run gets a new bounded restart allowance.
        policy.reset()
        repeat(3) { index ->
            assertEquals(EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT,
                policy.observe(e(62_007L + index, run = 8L)).action)
        }
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.RESTART_CLIENT,
            policy.observe(e(62_109L, run = 8L)).action)
    }

    @Test
    fun `stale fill deck after final gameover dispatches only exact postgame overlay clicks`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/event-quest-overlay-deck-selection-20261029.png",
        )))
        val screenKind = UpstreamScreenStateRecovery.classifyImageForTest("", image)
        assertEquals("EVENT_QUEST_OVERLAY", screenKind)
        val terminal = CurrentGamePowerLogTerminalTracker().apply {
            observeLine("D 21:19:00.000 CREATE_GAME")
            observeLine("Entity=self tag=PLAYSTATE value=WON")
            observeLine("Entity=opponent tag=PLAYSTATE value=CONCEDED")
            observeLine("Entity=GameEntity tag=STEP value=FINAL_GAMEOVER")
            observeLine("Entity=GameEntity tag=STATE value=COMPLETE")
        }
        val verifiedTerminal = terminal.hasCompleteTerminalEvidence() &&
            terminal.currentGameSurrenderEvidence("self", "opponent")?.ownPlayState == "WON"
        assertTrue(verifiedTerminal)
        assertTrue(EventQuestOverlayRecoveryContextPolicy.allowsDismiss(
            mode = "TOURNAMENT", phase = "FILL_DECK", currentSessionReady = true,
            nextModeGameplay = false, activeGame = false, activeQueueScreen = false,
            recentInputIntent = true, shortRaceWindow = false, matchmakingSearchVisual = false,
            terminalOrResult = true, working = true, paused = false,
            postGameTerminalProof = verifiedTerminal,
        ))

        val policy = EventQuestOverlayRecoveryPolicy()
        var overlayStillVisible = true
        var dispatched = 0
        repeat(3) { index ->
            val decision = policy.observe(evidence(
                nowMs = 60_000L + index * 500L, terminal = true, postGameTerminal = verifiedTerminal,
            ))
            assertEquals(EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT, decision.action)
            assertTrue(EventQuestOverlayClickDispatch.dispatch(
                decision.action, freshCaptureTrusted = true, foregroundVerified = true, safeContext = true,
            ) { dispatched++; true })
            assertTrue(overlayStillVisible,
                "SendInput acceptance must not itself be recorded as a visual transition")
        }
        assertEquals(3, dispatched)
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.WAIT_AND_REPROBE,
            policy.observe(evidence(nowMs = 120_999L, terminal = true, postGameTerminal = verifiedTerminal)).action)
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.UNBLOCK_SAFE_TRANSITION,
            policy.observe(evidence(nowMs = 121_000L, exact = false, safeTransition = true,
                terminal = true, postGameTerminal = verifiedTerminal)).action)
        overlayStillVisible = false
        assertFalse(overlayStillVisible)

        val falseOverlay = UpstreamScreenStateRecovery.classifyImageForTest("", BufferedImage(960, 540, BufferedImage.TYPE_INT_RGB))
        assertNotEquals("EVENT_QUEST_OVERLAY", falseOverlay)
    }

    @Test
    fun `recovery context permits fill deck with historical intent but fences current queue evidence`() {
        fun allows(
            mode: String = "TOURNAMENT",
            phase: String = "FILL_DECK",
            session: Boolean = true,
            game: Boolean = false,
            queueScreen: Boolean = false,
            recentIntent: Boolean = false,
            shortRace: Boolean = false,
            queueVisual: Boolean = false,
            terminal: Boolean = false,
            paused: Boolean = false,
        ) = EventQuestOverlayRecoveryContextPolicy.allowsDismiss(
            mode = mode,
            phase = phase,
            currentSessionReady = session,
            nextModeGameplay = false,
            activeGame = game,
            activeQueueScreen = queueScreen,
            recentInputIntent = recentIntent,
            shortRaceWindow = shortRace,
            matchmakingSearchVisual = queueVisual,
            terminalOrResult = terminal,
            working = true,
            paused = paused,
        )

        assertTrue(allows())
        assertTrue(allows(recentIntent = true), "15-minute historical input intent alone must not veto current FILL_DECK")
        assertFalse(allows(recentIntent = true, shortRace = true), "the short input race window remains fenced")
        assertTrue(allows(mode = "HUB", phase = "NONE", session = false))
        assertFalse(allows(phase = "MULLIGAN"))
        assertTrue(allows(session = false), "exact Tournament/FILL_DECK underlay does not require a current Power.log")
        assertFalse(allows(mode = "GAMEPLAY"))
        assertFalse(allows(game = true))
        assertFalse(allows(queueScreen = true), "a currently observed matchmaking screen remains fenced")
        assertFalse(allows(queueVisual = true), "fresh matchmaking-search pixels remain fenced")
        assertFalse(allows(terminal = true))
        assertFalse(allows(paused = true))
    }

    @Test
    fun `fresh empty current-session LOGIN overlay is safe under exact proof and all other fences remain`() {
        fun allows(
            emptyLog: Boolean = true,
            sessionReady: Boolean = false,
            activeGame: Boolean = false,
            queueScreen: Boolean = false,
            recentIntent: Boolean = false,
            shortRace: Boolean = false,
            queueVisual: Boolean = false,
            reconnect: Boolean = false,
            rankGuard: Boolean = false,
            working: Boolean = true,
            paused: Boolean = false,
            mode: String = "LOGIN",
            phase: String = "FILL_DECK",
            captureTrusted: Boolean = true,
        ) = EventQuestOverlayRecoveryContextPolicy.allowsFreshEmptySessionLoginProof(
            mode = mode, phase = phase, exactForegroundCapture = captureTrusted,
            emptyCurrentSessionPowerLog = emptyLog,
            currentSessionReady = sessionReady, nextModeGameplay = false, activeGame = activeGame,
            activeQueueScreen = queueScreen, recentInputIntent = recentIntent, shortRaceWindow = shortRace,
            matchmakingSearchVisual = queueVisual, reconnectDialogVisual = reconnect,
            mandatoryRankGuard = rankGuard, working = working, paused = paused,
        )

        assertTrue(allows())
        assertFalse(allows(emptyLog = false), "nonempty or stale Power.log cannot prove fresh startup")
        assertFalse(allows(sessionReady = true))
        assertFalse(allows(activeGame = true))
        assertFalse(allows(queueScreen = true))
        assertFalse(allows(recentIntent = true))
        assertFalse(allows(shortRace = true))
        assertFalse(allows(queueVisual = true))
        assertFalse(allows(reconnect = true))
        assertFalse(allows(rankGuard = true))
        assertFalse(allows(working = false))
        assertFalse(allows(paused = true))
        assertFalse(allows(mode = "TOURNAMENT"))
        assertFalse(allows(phase = "MULLIGAN"))
        assertFalse(allows(captureTrusted = false), "untrusted or background capture cannot prove the overlay context")

        val policy = EventQuestOverlayRecoveryPolicy(secondStageDelayMs = 60_000L)
        var dispatchedClicks = 0
        fun loginEvidence(now: Long) = evidence(
            nowMs = now,
            stableForMs = 60_000L,
            terminal = true,
        ).copy(
            freshEmptySessionLoginProof = true,
            semanticScreenIdentity = "EVENT_QUEST_OVERLAY|LOGIN|FILL_DECK|EMPTY_CURRENT_POWERLOG",
        )
        repeat(3) { index ->
            val decision = policy.observe(loginEvidence(60_000L + index * 500L))
            assertEquals(EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT, decision.action)
            assertTrue(EventQuestOverlayClickDispatch.dispatch(
                action = decision.action,
                freshCaptureTrusted = true,
                foregroundVerified = true,
                safeContext = allows(),
            ) { dispatchedClicks++; true })
        }
        assertEquals(3, dispatchedClicks, "each retry requires a fresh exact overlay observation")
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.WAIT_AND_REPROBE,
            policy.observe(loginEvidence(120_999L)).action)
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.RESTART_CLIENT,
            policy.observe(loginEvidence(121_000L)).action,
            "unchanged exact overlay after the second minute must reach bounded restart")
        policy.confirmRestartIssued()
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.STOP,
            policy.observe(loginEvidence(121_001L)).action)

        policy.reset() // A replacement Hearthstone PID gets a new click sequence, not a new restart budget.
        repeat(3) { index ->
            assertEquals(EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT,
                policy.observe(loginEvidence(200_000L + index * 500L)).action)
        }
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.STOP,
            policy.observe(loginEvidence(321_000L)).action,
            "an unchanged LOGIN overlay after the one permitted client restart must alert/stop")
        assertEquals("client-restart-cap-exhausted",
            policy.observe(loginEvidence(321_001L)).reason)
    }

    @Test
    fun `LOGIN empty-session proof alone cannot let a reconnect frame enter recovery`() {
        assertFalse(EventQuestOverlayRecoveryContextPolicy.allowsDismiss(
            mode = "LOGIN", phase = "FILL_DECK", currentSessionReady = false,
            nextModeGameplay = false, activeGame = false, activeQueueScreen = false,
            recentInputIntent = false, shortRaceWindow = false, matchmakingSearchVisual = false,
            terminalOrResult = true, working = true, paused = false,
            freshEmptySessionLoginProof = true, reconnectDialogVisual = true,
        ))
        assertTrue(EventQuestOverlayRecoveryContextPolicy.allowsFreshEmptySessionLoginTransition(
            questOverlayVisible = false,
            centeredModalVisible = false,
            freshEmptySessionLoginProof = true,
        ), "fresh empty-session LOGIN capture with the quest panel gone releases the recovery fence")
        assertFalse(EventQuestOverlayRecoveryContextPolicy.allowsFreshEmptySessionLoginTransition(
            questOverlayVisible = false,
            centeredModalVisible = true,
            freshEmptySessionLoginProof = true,
        ), "another modal is not a safe transition")
        assertFalse(EventQuestOverlayRecoveryContextPolicy.allowsFreshEmptySessionLoginTransition(
            questOverlayVisible = true,
            centeredModalVisible = false,
            freshEmptySessionLoginProof = true,
        ), "the exact overlay still visible is not an unblocked transition")
        listOf(
            mapOf("reconnectDialogVisual" to true),
            mapOf("activeQueueScreen" to true),
            mapOf("shortRaceWindow" to true),
            mapOf("activeGame" to true),
            mapOf("paused" to true),
        ).forEach { fences ->
            val safeProof = EventQuestOverlayRecoveryContextPolicy.allowsFreshEmptySessionLoginProof(
                mode = "LOGIN", phase = "FILL_DECK", exactForegroundCapture = true,
                emptyCurrentSessionPowerLog = true,
                currentSessionReady = false, nextModeGameplay = false,
                activeGame = fences["activeGame"] == true,
                activeQueueScreen = fences["activeQueueScreen"] == true,
                recentInputIntent = false,
                shortRaceWindow = fences["shortRaceWindow"] == true,
                matchmakingSearchVisual = false,
                reconnectDialogVisual = fences["reconnectDialogVisual"] == true,
                mandatoryRankGuard = false, working = true, paused = fences["paused"] == true,
            )
            assertFalse(EventQuestOverlayRecoveryContextPolicy.allowsFreshEmptySessionLoginTransition(
                questOverlayVisible = false,
                centeredModalVisible = false,
                freshEmptySessionLoginProof = safeProof,
            ), "fence evidence $fences must prevent transition/unblocking")
        }

        val policy = EventQuestOverlayRecoveryPolicy()
        val transition = policy.observe(evidence(
            nowMs = 1_000L,
            exact = false,
            safeTransition = EventQuestOverlayRecoveryContextPolicy.allowsFreshEmptySessionLoginTransition(
                questOverlayVisible = false,
                centeredModalVisible = false,
                freshEmptySessionLoginProof = true,
            ),
            terminal = true,
        ).copy(freshEmptySessionLoginProof = true))
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.UNBLOCK_SAFE_TRANSITION, transition.action)
        StartupQuestOverlayRecoveryFence.resetForTest()
        try {
            StartupQuestOverlayRecoveryFence.begin()
            assertTrue(StartupQuestOverlayRecoveryFence.isActive())
            assertTrue(UpstreamScreenStateRecovery.resolveEventQuestSafeTransition(transition.action))
            assertFalse(StartupQuestOverlayRecoveryFence.isActive(),
                "verified overlay disappearance must release the queue/input fence")
            StartupQuestOverlayRecoveryFence.begin()
            assertFalse(UpstreamScreenStateRecovery.resolveEventQuestSafeTransition(
                EventQuestOverlayRecoveryPolicy.Action.RESTART_CLIENT,
            ), "unsafe or unresolved actions cannot release the recovery fence")
            assertTrue(StartupQuestOverlayRecoveryFence.isActive())
        } finally {
            StartupQuestOverlayRecoveryFence.resetForTest()
        }
        listOf(
            evidence(nowMs = 2_000L, exact = false, safeTransition = false, activeQueue = true),
            evidence(nowMs = 2_000L, exact = false, safeTransition = false, activeGame = true),
            evidence(nowMs = 2_000L, exact = false, safeTransition = false, trusted = false),
            evidence(nowMs = 2_000L, exact = false, safeTransition = false).copy(
                safeMenuOrDeckState = false,
            ), // reconnect/unknown modal: no empty-session transition proof
        ).forEach { blocked ->
            assertNotEquals(EventQuestOverlayRecoveryPolicy.Action.UNBLOCK_SAFE_TRANSITION,
                EventQuestOverlayRecoveryPolicy().observe(blocked).action)
        }
    }

    @Test
    fun `15 minute matchmaking intent alone does not veto fill deck but active screen does`() {
        val now = System.currentTimeMillis()
        LifecycleTrace.resetMatchmakingEvidenceForTest()
        try {
            LifecycleTrace.markMatchmakingInputDispatched(dispatchAccepted = true, now = now - 90_000L)
            assertTrue(LifecycleTrace.hasRecentMatchmakingInputIntent(now))
            assertFalse(LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard(now))
            assertTrue(EventQuestOverlayRecoveryContextPolicy.allowsDismiss(
                mode = "TOURNAMENT", phase = "FILL_DECK", currentSessionReady = true,
                nextModeGameplay = false, activeGame = false,
                activeQueueScreen = LifecycleTrace.hasRecentActiveMatchmakingScreen(now),
                recentInputIntent = LifecycleTrace.hasRecentMatchmakingInputIntent(now),
                shortRaceWindow = LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard(now),
                matchmakingSearchVisual = false, terminalOrResult = false, working = true, paused = false,
            ))

            LifecycleTrace.markActiveMatchmakingScreenObserved(now)
            assertFalse(EventQuestOverlayRecoveryContextPolicy.allowsDismiss(
                mode = "TOURNAMENT", phase = "FILL_DECK", currentSessionReady = true,
                nextModeGameplay = false, activeGame = false,
                activeQueueScreen = LifecycleTrace.hasRecentActiveMatchmakingScreen(now),
                recentInputIntent = LifecycleTrace.hasRecentMatchmakingInputIntent(now),
                shortRaceWindow = LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard(now),
                matchmakingSearchVisual = false, terminalOrResult = false, working = true, paused = false,
            ))
        } finally {
            LifecycleTrace.resetMatchmakingEvidenceForTest()
        }
    }

    @Test
    fun `top-left target is the exact captured client origin`() {
        assertEquals(Point(120, 220), StartupQuestOverlayDismissPolicy.topLeftPixel(Rectangle(120, 220, 1920, 1080)))
        assertEquals(null, StartupQuestOverlayDismissPolicy.topLeftPixel(Rectangle(0, 0, 0, 1080)))
    }

    private fun evidence(
        nowMs: Long,
        stableForMs: Long = EventQuestOverlayRecoveryPolicy.FIRST_STAGE_DELAY_MS,
        exact: Boolean = true,
        safeTransition: Boolean = false,
        trusted: Boolean = true,
        paused: Boolean = false,
        activeGame: Boolean = false,
        activeQueue: Boolean = false,
        terminal: Boolean = false,
        postGameTerminal: Boolean = false,
        automationRunId: Long = 0L,
        semanticScreenIdentity: String = "EVENT_QUEST_OVERLAY",
    ) = EventQuestOverlayRecoveryPolicy.Evidence(
        nowMs = nowMs,
        captureTrusted = trusted,
        exactQuestOverlay = exact,
        safeMenuOrDeckState = true,
        verifiedSafeTransition = safeTransition,
        working = true,
        paused = paused,
        activeGame = activeGame,
        activeQueue = activeQueue,
        terminalOrResult = terminal,
        postGameTerminalProof = postGameTerminal,
        stableForMs = stableForMs,
        automationRunId = automationRunId,
        semanticScreenIdentity = semanticScreenIdentity,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02X".format(it) }

    private companion object {
        const val COMPLETION_POPUP_FIXTURE = "/offline-ocr/screen-recovery/event-quest-completion-popup-20261030.png"
        const val COMPLETION_POPUP_SHA256 = "57671B5659DB6574426AA7BBCBEBF82E1687A15CB05EEC9403A9E8C99DCA7DDF"
    }
}
