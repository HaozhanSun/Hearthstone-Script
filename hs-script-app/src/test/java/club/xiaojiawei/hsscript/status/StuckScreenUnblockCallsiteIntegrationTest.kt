package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

class StuckScreenUnblockCallsiteIntegrationTest {
    private val image: BufferedImage by lazy {
        ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/hub-mode-chooser-live-20261029-035630.png",
        )))
    }

    private fun evidence(
        now: Long,
        stableForMs: Long = 60_000L,
        fingerprint: String = "9704|native@0x44c086e|live-hub-chooser",
        activeQueue: Boolean = false,
        activeGame: Boolean = false,
        paused: Boolean = false,
        terminal: Boolean = false,
        result: Boolean = false,
        currentSessionReady: Boolean = false,
    ) = StuckScreenUnblockPolicy.Evidence(
        nowMs = now,
        stableForMs = stableForMs,
        fingerprint = fingerprint,
        captureFresh = true,
        exactWindowVerified = true,
        eligibleIdleProbe = true,
        recognizedSafeBlocker = true,
        currentSessionReady = currentSessionReady,
        activeGame = activeGame,
        activeQueue = activeQueue,
        terminal = terminal,
        paused = paused,
        resultOrTerminalScreen = result,
    )

    private fun assertLiveChooserClassifiesAsStructurallyVerifiedHub() {
        // With no OCR text, TOURNAMENT can only come from the fixed HUB chooser visual.
        val classification = UpstreamScreenStateRecovery.classifyImageForTest("", image)
        assertEquals("TOURNAMENT", classification)
        assertTrue(SafeIdleUnblockScreenPolicy.matches(
            screenKind = classification,
            screenMode = ModeEnum.HUB.name,
            hubModeChooserVisual = true,
            eventQuestOverlayVisual = false,
        ))
    }

    @Test
    fun `live HUB chooser dispatches three exact client-origin clicks then requests a fresh recheck and bounded restart`() {
        assertLiveChooserClassifiesAsStructurallyVerifiedHub()
        val policy = StuckScreenUnblockPolicy()
        val decision = policy.observe(evidence(now = 61_000L))
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT, decision.action)
        val point = StartupQuestOverlayDismissPolicy.topLeftPixel(Rectangle(0, 0, image.width, image.height))
        assertEquals(Point(0, 0), point)

        val clicks = mutableListOf<Pair<Int, Point>>()
        var rechecks = 0
        val batchInFlight = AtomicBoolean(false)
        assertTrue(StuckScreenUnblockDispatch.dispatchTopLeftBatch(
            batchInFlight = batchInFlight,
            action = decision.action,
            firstClick = decision.clickNumber,
            freshCaptureAuthorized = true,
            point = point,
            expectedFingerprint = "9704|native@0x44c086e|live-hub-chooser",
            completionTimeMs = { 61_000L },
            dispatchClick = { dispatchedPoint, number ->
                clicks += number to dispatchedPoint
                true
            },
            completeBatch = policy::completeTopLeftClickBatch,
            requestFreshRecheck = { rechecks++ },
        ))
        assertEquals(listOf(1, 2, 3), clicks.map { it.first })
        assertEquals(listOf(Point(0, 0), Point(0, 0), Point(0, 0)), clicks.map { it.second })
        assertEquals(1, rechecks)
        assertFalse(batchInFlight.get(), "the batch lock must release after the clicks and recheck request")

        assertEquals(StuckScreenUnblockPolicy.Action.REPROBE, policy.observe(
            evidence(now = 120_999L, stableForMs = 119_999L),
        ).action)
        val restart = policy.observe(evidence(now = 121_000L, stableForMs = 120_000L))
        assertEquals(StuckScreenUnblockPolicy.Action.RESTART_CLIENT, restart.action)
        var restartDispatches = 0
        assertTrue(StuckScreenUnblockDispatch.dispatchRestartIfAuthorized(restart.action, true) {
            restartDispatches++
        })
        assertEquals(1, restartDispatches)
        assertFalse(StuckScreenUnblockDispatch.dispatchRestartIfAuthorized(restart.action, false) {
            restartDispatches++
        })
        assertEquals(1, restartDispatches)
    }

    @Test
    fun `fresh HUB chooser after restart with empty Power log permits one Traditional click and never Start`() {
        assertLiveChooserClassifiesAsStructurallyVerifiedHub()
        val observedAt = System.currentTimeMillis()
        LifecycleTrace.resetMatchmakingEvidenceForTest()
        try {
            LifecycleTrace.markMatchmakingInputDispatched(dispatchAccepted = true, now = observedAt - 90_000L)
            assertTrue(LifecycleTrace.hasRecentMatchmakingInputIntent(observedAt),
                "fixture models historical queue intent from a previous session")
            val activeQueueEvidence = LifecycleTrace.hasRecentActiveMatchmakingScreen(observedAt) ||
                LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard(observedAt)
            assertFalse(activeQueueEvidence, "historical intent alone is not evidence of a current queue")
            assertTrue(StartupHubModeChooserPolicy.canAuthorizeTraditionalBattleSelection(
                purpose = ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
                currentSessionReady = false,
                screenKind = "TOURNAMENT",
                confidence = 96,
                evidence = "hub-mode-chooser-visual-and-text",
                currentMode = "HUB",
                modalVisible = false,
                activeGame = false,
                activeMatchmaking = activeQueueEvidence,
                trustedCurrentWindow = true,
            ), "exact HUB chooser proof remains valid with historical intent and no current queue evidence")
            assertFalse(StartupHubModeChooserPolicy.canAuthorizeTraditionalBattleSelection(
                purpose = ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
                currentSessionReady = false,
                screenKind = "TOURNAMENT",
                confidence = 96,
                evidence = "hub-mode-chooser-visual-and-text",
                currentMode = "HUB",
                modalVisible = false,
                activeGame = false,
                activeMatchmaking = true,
                trustedCurrentWindow = true,
            ), "actual current matchmaking evidence still fences HUB entry")
        } finally {
            LifecycleTrace.resetMatchmakingEvidenceForTest()
        }
        val purpose = ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE
        val stuckForMs = ScreenRecoveryCapturePurposePolicy.IDLE_CLIENT_PROBE_AFTER_MS
        assertTrue(ScreenRecoveryCapturePurposePolicy.allowsPreSessionIdleClientProbe(
            purpose = purpose,
            currentSessionReady = false,
            stuckForMs = stuckForMs,
        ), "fresh client-owned screenshot may classify the startup chooser before a new Power.log exists")
        assertTrue(StartupHubModeChooserPolicy.canAuthorizeTraditionalBattleSelection(
            purpose = purpose,
            currentSessionReady = false,
            screenKind = "TOURNAMENT",
            confidence = 96,
            evidence = "hub-mode-chooser-visual-and-text",
            currentMode = "HUB",
            modalVisible = false,
            activeGame = false,
            activeMatchmaking = false,
            trustedCurrentWindow = true,
        ))

        val now = System.currentTimeMillis()
        val pid = 36_892
        val hwnd = "native@0x24907bc"
        StartupInputReadiness.resetForTest()
        try {
            StartupInputReadiness.recordHubModeChooser(pid, hwnd, now)
            assertTrue(StartupInputReadiness.isHubTraditionalBattleSelectionReady(pid, hwnd, "HUB", now))
            val actions = mutableListOf<String>()
            assertTrue(StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
                chooserProofFresh = StartupInputReadiness.isHubTraditionalBattleSelectionReady(pid, hwnd, "HUB", now),
                paused = false,
                processId = pid,
                hwnd = hwnd,
            ) { action ->
                actions += action
                StartupInputAuthorizationPolicy.allowsHubTraditionalBattleSelection(
                    action = action,
                    processId = pid,
                    hwnd = hwnd,
                    exactWindowVerified = true,
                    freshHubChooserReady = StartupInputReadiness.isHubTraditionalBattleSelectionReady(
                        pid, hwnd, "HUB", now,
                    ),
                    currentSessionReady = false,
                    activeGame = false,
                    activeMatchmaking = false,
                    terminal = false,
                )
            })
            assertEquals(listOf(StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION), actions)
            assertFalse(StartupInputReadiness.isHubTraditionalBattleSelectionReady(pid, hwnd, "HUB", now),
                "accepted click consumes chooser proof; retry requires a fresh exact chooser capture")
            assertFalse(StartupInputAuthorizationPolicy.allows(
                action = "matchmaking.start",
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                freshDeckSelectionStartReady = false,
                selectedModeStartPermitValid = false,
            ), "HUB visual proof only authorizes Traditional Battle selection, never Start")
            assertFalse(StartupInputAuthorizationPolicy.allowsHubTraditionalBattleSelection(
                action = "matchmaking.start", processId = pid, hwnd = hwnd,
                exactWindowVerified = true, freshHubChooserReady = true,
            ))
        } finally {
            StartupInputReadiness.resetForTest()
        }
    }

    @Test
    fun `captured live chooser survives modal and queue pixel heuristics through the startup proof callsite`() {
        val nearThresholdChooser = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/hub-mode-chooser-live-20261101-234632.png",
        )))
        assertTrue(HubModeChooserVisualPolicy.isVisible(nearThresholdChooser))
        assertFalse(
            MatchmakingSearchVisualPolicy.isVisible(nearThresholdChooser),
            "the 23:46:32 chooser is a near-threshold negative; its timer dark ratio is below the queue signature",
        )

        val liveChooser = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/hub-mode-chooser-live-20261101-234533.png",
        )))
        val ocrText = ""
        val classification = UpstreamScreenStateRecovery.classifyImageDetailsForTest(ocrText, liveChooser)
        val screen = classification.screenKind
        val screenMode = classification.screenMode
        val evidence = classification.evidence
        val hubVisual = HubModeChooserVisualPolicy.isVisible(liveChooser)
        val queueVisual = MatchmakingSearchVisualPolicy.isVisible(liveChooser)
        val questVisual = StartupQuestOverlayVisualClassifier.isQuestOverlay(liveChooser)
        val modalPanelVisual = ScreenStateRecovery.hasCenteredModalPanel(liveChooser)

        assertTrue(hubVisual, "the saved live frame contains all four fixed HUB mode buttons")
        assertTrue(queueVisual, "the 23:45:33 saved live frame reproduces the broad red/dark queue-color false-positive")
        assertFalse(questVisual, "this capture is a mode chooser, not the Event Quest modal")
        assertFalse(modalPanelVisual, "the real chooser has no covering centered modal")
        assertEquals("TOURNAMENT", screen)
        assertEquals("HUB", screenMode)
        assertEquals("hub-mode-chooser-visual", evidence)
        assertEquals(
            StartupHandoffProgressPolicy.PreOcrAction.CLASSIFY_HUB_CHOOSER_VISUALLY,
            StartupHandoffProgressPolicy.preOcrAction(
                purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
                currentSessionReady = false,
                battleNetLauncherVisual = false,
                hubModeChooserVisual = hubVisual,
                modalVisible = modalPanelVisual,
                startupQuestOverlayVisual = questVisual,
            ),
            "the exact four-button chooser must skip expensive/noisy OCR even when queue-color heuristics also match",
        )

        val activeQueue = StartupHubModeChooserPolicy.hasActiveMatchmakingEvidence(
            matchmakingSearchVisual = queueVisual,
            hubModeChooserVisual = hubVisual,
            screenKind = screen,
            screenMode = screenMode,
            evidence = evidence,
            modalVisible = modalPanelVisual,
            recentActiveMatchmaking = false,
            activeEntryGuard = false,
        )
        assertFalse(activeQueue, "the same-frame queue color match is explained by the exact chooser geometry")
        val chooserProofMode = StartupHubModeChooserPolicy.modeForFreshChooserProof(
            screenKind = screen,
            detectedMode = screenMode,
            evidence = evidence,
            runtimeMode = "ONLINE_MENU",
        )
        assertEquals(
            "HUB",
            chooserProofMode,
            "the same fresh chooser capture must authorize its one-use proof before Mode recovery catches up",
        )
        assertEquals(
            "ONLINE_MENU",
            StartupHubModeChooserPolicy.modeForFreshChooserProof(
                screenKind = "DECK_SELECTION",
                detectedMode = "HUB",
                evidence = evidence,
                runtimeMode = "ONLINE_MENU",
            ),
            "only the exact tournament chooser may replace a lagging runtime mode",
        )
        assertTrue(StartupHubModeChooserPolicy.canAuthorizeTraditionalBattleSelection(
            purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            currentSessionReady = false,
            screenKind = screen,
            confidence = classification.confidence,
            evidence = evidence,
            currentMode = chooserProofMode,
            modalVisible = modalPanelVisual,
            activeGame = false,
            activeMatchmaking = activeQueue,
            trustedCurrentWindow = true,
        ))

        val pid = 41_710
        val hwnd = "native@0x-chooser-live-capture"
        val now = System.currentTimeMillis()
        StartupInputReadiness.resetForTest()
        try {
            StartupInputReadiness.recordHubModeChooser(pid, hwnd, now)
            val proof = StartupInputReadiness.isHubTraditionalBattleSelectionReady(pid, hwnd, "HUB", now + 1)
            val dispatched = mutableListOf<String>()
            assertTrue(StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
                chooserProofFresh = proof,
                paused = false,
                processId = pid,
                hwnd = hwnd,
            ) { action ->
                dispatched += action
                StartupInputAuthorizationPolicy.allowsHubTraditionalBattleSelection(
                    action = action,
                    processId = pid,
                    hwnd = hwnd,
                    exactWindowVerified = true,
                    freshHubChooserReady = proof,
                    currentSessionReady = false,
                    activeGame = false,
                    activeMatchmaking = activeQueue,
                    terminal = false,
                )
            })
            assertEquals(listOf(StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION), dispatched)
            assertFalse(StartupInputReadiness.isHubTraditionalBattleSelectionReady(pid, hwnd, "HUB", now + 2),
                "accepted entry consumes the one-use chooser proof")
        } finally {
            StartupInputReadiness.resetForTest()
        }


        assertEquals(
            StartupHandoffProgressPolicy.PreOcrAction.RUN_OCR,
            StartupHandoffProgressPolicy.preOcrAction(
                purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
                currentSessionReady = false,
                battleNetLauncherVisual = false,
                hubModeChooserVisual = true,
                modalVisible = true,
                startupQuestOverlayVisual = true,
            ),
            "a dedicated quest overlay vetoes chooser fast classification",
        )
    }

    @Test
    fun `chooser proof remains fenced when a centered modal masks one chooser button band`() {
        val chooser = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/hub-mode-chooser-live-20261101-234533.png",
        )))
        chooser.createGraphics().also { graphics ->
            graphics.color = java.awt.Color(20, 20, 20)
            graphics.fillRect(
                (chooser.width * .32).toInt(),
                (chooser.height * .25).toInt(),
                (chooser.width * .36).toInt(),
                (chooser.height * .40).toInt(),
            )
            graphics.dispose()
        }

        assertFalse(HubModeChooserVisualPolicy.isVisible(chooser),
            "masking one chooser face must revoke exact chooser geometry")
        assertFalse(MatchmakingSearchVisualPolicy.isVisible(chooser),
            "a covering modal must not be mistaken for a visible queue modal")
        assertTrue(ScreenStateRecovery.hasCenteredModalPanel(chooser),
            "the centered modal detector must keep the covering panel visible")

        val classification = UpstreamScreenStateRecovery.classifyImageDetailsForTest("", chooser)
        val screenKind = classification.screenKind
        val screenMode = classification.screenMode
        val evidence = classification.evidence
        val modalVisible = ScreenStateRecovery.hasCenteredModalPanel(chooser)
        assertTrue(modalVisible, "the production classifier input must retain the centered modal veto")
        val activeQueueWithoutExternalFence = StartupHubModeChooserPolicy.hasActiveMatchmakingEvidence(
            matchmakingSearchVisual = MatchmakingSearchVisualPolicy.isVisible(chooser),
            hubModeChooserVisual = HubModeChooserVisualPolicy.isVisible(chooser),
            screenKind = screenKind,
            screenMode = screenMode,
            evidence = evidence,
            modalVisible = modalVisible,
            recentActiveMatchmaking = false,
            activeEntryGuard = false,
        )
        assertFalse(activeQueueWithoutExternalFence,
            "the masked frame has no independent queue visual or chooser proof")
        val activeQueue = StartupHubModeChooserPolicy.hasActiveMatchmakingEvidence(
            matchmakingSearchVisual = MatchmakingSearchVisualPolicy.isVisible(chooser),
            hubModeChooserVisual = HubModeChooserVisualPolicy.isVisible(chooser),
            screenKind = screenKind,
            screenMode = screenMode,
            evidence = evidence,
            modalVisible = modalVisible,
            recentActiveMatchmaking = false,
            activeEntryGuard = true,
        )
        assertTrue(activeQueue, "an external active-entry fence must remain hard even while the modal masks the chooser")
        assertFalse(StartupHubModeChooserPolicy.canAuthorizeTraditionalBattleSelection(
            purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            currentSessionReady = false,
            screenKind = screenKind,
            confidence = classification.confidence,
            evidence = evidence,
            currentMode = screenMode,
            modalVisible = modalVisible,
            activeGame = false,
            activeMatchmaking = activeQueueWithoutExternalFence,
            trustedCurrentWindow = true,
        ), "modal and active queue evidence must deny chooser entry")

        var dispatched = 0
        assertFalse(StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
            chooserProofFresh = false,
            paused = false,
            processId = 41_710,
            hwnd = "native@0x-chooser-modal",
        ) {
            dispatched++
            true
        })
        assertEquals(0, dispatched, "a masked chooser must dispatch no click")
    }

    @Test
    fun `changed frame and active queue pause or terminal evidence prevent click and restart dispatch`() {
        assertLiveChooserClassifiesAsStructurallyVerifiedHub()
        val changedPolicy = StuckScreenUnblockPolicy()
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT,
            changedPolicy.observe(evidence(now = 61_000L)).action)
        assertTrue(changedPolicy.completeTopLeftClickBatch(
            "9704|native@0x44c086e|live-hub-chooser", 61_000L,
        ))
        val changed = changedPolicy.observe(evidence(
            now = 61_500L,
            stableForMs = 60_000L,
            fingerprint = "9704|native@0x44c086e|different-screen",
        ))
        assertEquals(StuckScreenUnblockPolicy.Action.UNBLOCKED, changed.action)
        var changedScreenClicks = 0
        var changedScreenRestarts = 0
        assertFalse(StuckScreenUnblockDispatch.dispatchTopLeftBatch(
            batchInFlight = AtomicBoolean(false),
            action = changed.action,
            firstClick = changed.clickNumber,
            freshCaptureAuthorized = true,
            point = Point(0, 0),
            expectedFingerprint = "9704|native@0x44c086e|different-screen",
            completionTimeMs = { 61_500L },
            dispatchClick = { _, _ -> changedScreenClicks++; true },
            completeBatch = changedPolicy::completeTopLeftClickBatch,
            requestFreshRecheck = {},
        ))
        assertFalse(StuckScreenUnblockDispatch.dispatchRestartIfAuthorized(changed.action, false) {
            changedScreenRestarts++
        })
        assertEquals(0, changedScreenClicks)
        assertEquals(0, changedScreenRestarts)

        val clickCounts = mutableListOf<Int>()
        listOf(
            evidence(now = 61_000L, activeQueue = true),
            evidence(now = 61_000L, paused = true),
            evidence(now = 61_000L, result = true),
        ).forEach { unsafeEvidence ->
            val policy = StuckScreenUnblockPolicy()
            val blocked = policy.observe(unsafeEvidence)
            assertEquals(StuckScreenUnblockPolicy.Action.STOP, blocked.action)
            assertFalse(StuckScreenUnblockDispatch.dispatchTopLeftBatch(
                batchInFlight = AtomicBoolean(false),
                action = blocked.action,
                firstClick = blocked.clickNumber,
                freshCaptureAuthorized = true,
                point = Point(0, 0),
                expectedFingerprint = unsafeEvidence.fingerprint,
                completionTimeMs = { unsafeEvidence.nowMs },
                dispatchClick = { _, _ -> clickCounts += 1; true },
                completeBatch = policy::completeTopLeftClickBatch,
                requestFreshRecheck = {},
            ))
            assertFalse(StuckScreenUnblockDispatch.dispatchRestartIfAuthorized(
                action = blocked.action,
                finalSafetyPassed = false,
                restart = { clickCounts += 99 },
            ))
        }
        assertTrue(clickCounts.isEmpty())
    }

    @Test
    fun `batch already in flight rejects a second click sequence`() {
        val clicks = mutableListOf<Int>()
        val rechecks = mutableListOf<Int>()
        val busyBatch = AtomicBoolean(true)
        assertFalse(StuckScreenUnblockDispatch.dispatchTopLeftBatch(
            batchInFlight = busyBatch,
            action = StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT,
            firstClick = 1,
            freshCaptureAuthorized = true,
            point = Point(0, 0),
            expectedFingerprint = "pid|hwnd|same-frame",
            completionTimeMs = { 61_000L },
            dispatchClick = { _, number -> clicks += number; true },
            completeBatch = { _, _ -> true },
            requestFreshRecheck = { rechecks += 1 },
        ))
        assertTrue(busyBatch.get(), "the active owner retains the lock")
        assertTrue(clicks.isEmpty(), "overlapping click sequences must not dispatch")
        assertTrue(rechecks.isEmpty(), "the rejected sequence must not schedule a second probe")
    }

    @Test
    fun `live reconnect animation fingerprints keep one exact three-click batch and guarded restart stage`() {
        val sourceFrame = StuckScreenUnblockFrameIdentity.create(
            processId = 29_000,
            windowId = "native@0x44c086e",
            perceptualFingerprint = "9b65c04348f33aed",
            exactReconnectDialog = true,
        )
        val freshAnimatedFrame = StuckScreenUnblockFrameIdentity.create(
            processId = 29_000,
            windowId = "native@0x44c086e",
            perceptualFingerprint = "9b61da4348ef7e44",
            exactReconnectDialog = true,
        )
        assertEquals("exact-reconnect-dialog", sourceFrame.screenIdentity)
        assertEquals(sourceFrame.fingerprint, freshAnimatedFrame.fingerprint)
        assertTrue(StuckScreenUnblockFrameIdentity.isSameClientScreen(sourceFrame, freshAnimatedFrame))
        assertFalse(StuckScreenUnblockFrameIdentity.isSameClientScreen(
            sourceFrame,
            StuckScreenUnblockFrameIdentity.create(
                processId = 29_000,
                windowId = "native@0x44c086e",
                perceptualFingerprint = "different-screen-hash",
                exactReconnectDialog = false,
            ),
        ), "a changed or no-longer-verified screen cannot reuse the reconnect identity")
        assertFalse(StuckScreenUnblockFrameIdentity.isSameClientScreen(
            sourceFrame,
            StuckScreenUnblockFrameIdentity.create(
                processId = 29_001,
                windowId = "native@0x44c086e",
                perceptualFingerprint = "9b61da4348ef7e44",
                exactReconnectDialog = true,
            ),
        ), "a new game process cannot inherit the previous dialog ladder")
        assertFalse(StuckScreenUnblockFrameIdentity.isSameClientScreen(
            sourceFrame,
            StuckScreenUnblockFrameIdentity.create(
                processId = 29_000,
                windowId = "native@0x44c086f",
                perceptualFingerprint = "9b61da4348ef7e44",
                exactReconnectDialog = true,
            ),
        ), "a different game window cannot inherit the previous dialog ladder")

        val policy = StuckScreenUnblockPolicy()
        val fingerprint = sourceFrame.fingerprint
        val decision = policy.observe(evidence(now = 61_000L, fingerprint = fingerprint))
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT, decision.action)
        val clicks = mutableListOf<Pair<Int, Point>>()
        var rechecks = 0
        assertTrue(StuckScreenUnblockDispatch.dispatchTopLeftBatch(
            batchInFlight = AtomicBoolean(false),
            action = decision.action,
            firstClick = decision.clickNumber,
            freshCaptureAuthorized = StuckScreenUnblockFrameIdentity.isSameClientScreen(
                sourceFrame, freshAnimatedFrame,
            ),
            point = Point(0, 0),
            expectedFingerprint = fingerprint,
            completionTimeMs = { 61_000L },
            dispatchClick = { point, number -> clicks += number to point; true },
            completeBatch = policy::completeTopLeftClickBatch,
            requestFreshRecheck = { rechecks++ },
        ))
        assertEquals(listOf(1, 2, 3), clicks.map { it.first })
        assertEquals(listOf(Point(0, 0), Point(0, 0), Point(0, 0)), clicks.map { it.second })
        assertEquals(1, rechecks)

        // The recheck sees a different perceptual hash from dialog animation,
        // but the exact same PID/HWND/structural reconnect identity.
        val recheckFrame = StuckScreenUnblockFrameIdentity.create(
            processId = 29_000,
            windowId = "native@0x44c086e",
            perceptualFingerprint = "9b61da4348ef7e44",
            exactReconnectDialog = true,
        )
        assertTrue(policy.hasProgressFor(recheckFrame.fingerprint))
        assertEquals(StuckScreenUnblockPolicy.Action.REPROBE, policy.observe(
            evidence(now = 120_999L, stableForMs = 119_999L, fingerprint = recheckFrame.fingerprint),
        ).action)
        val oneMinuteLaterSameDialog = policy.observe(
            evidence(now = 121_000L, stableForMs = 120_000L, fingerprint = recheckFrame.fingerprint),
        )
        assertEquals(StuckScreenUnblockPolicy.Action.RESTART_CLIENT, oneMinuteLaterSameDialog.action)
        var restartDispatches = 0
        assertFalse(StuckScreenUnblockDispatch.dispatchRestartIfAuthorized(
            oneMinuteLaterSameDialog.action, false,
        ) { restartDispatches++ })
        assertEquals(0, restartDispatches, "fresh active-game/queue/pause/result fences still defer restart")
        assertTrue(StuckScreenUnblockDispatch.dispatchRestartIfAuthorized(
            oneMinuteLaterSameDialog.action, true,
        ) { restartDispatches++ })
        assertEquals(1, restartDispatches)
    }

    @Test
    fun `wrong mode deck uses semantic continuity across animated frames then restarts only after second minute`() {
        fun frame(perceptual: String, observedMode: String) = StuckScreenUnblockFrameIdentity.create(
            processId = 25_948,
            windowId = "native@0x44c086e",
            perceptualFingerprint = perceptual,
            exactReconnectDialog = false,
            semanticScreenIdentity = StuckScreenUnblockFrameIdentity.semanticScreenIdentity(
                screenKind = "DECK_SELECTION",
                screenMode = "TOURNAMENT",
                expectedRunMode = "WILD",
                observedRunMode = observedMode,
            ),
        )

        val standardFrame = frame("pHash-12-24-03", "STANDARD")
        val animatedStandardFrame = frame("pHash-12-24-50", "STANDARD")
        val laterAnimatedStandardFrame = frame("pHash-12-25-20", "STANDARD")
        val wildFrame = frame("pHash-wild", "WILD")
        assertTrue(StuckScreenUnblockFrameIdentity.isSameClientScreen(standardFrame, animatedStandardFrame))
        assertTrue(StuckScreenUnblockFrameIdentity.isSameClientScreen(standardFrame, laterAnimatedStandardFrame))
        assertFalse(StuckScreenUnblockFrameIdentity.isSameClientScreen(standardFrame, wildFrame),
            "a positively observed mode transition ends the wrong-mode ladder")

        val policy = StuckScreenUnblockPolicy()
        val first = policy.observe(evidence(
            now = 61_000L,
            stableForMs = 61_000L,
            fingerprint = standardFrame.fingerprint,
        ))
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT, first.action)
        val clicks = mutableListOf<Int>()
        assertTrue(StuckScreenUnblockDispatch.dispatchTopLeftBatch(
            batchInFlight = AtomicBoolean(false), action = first.action, firstClick = first.clickNumber,
            freshCaptureAuthorized = StuckScreenUnblockFrameIdentity.isSameClientScreen(
                standardFrame, animatedStandardFrame,
            ), point = Point(0, 0), expectedFingerprint = standardFrame.fingerprint,
            completionTimeMs = { 61_100L },
            dispatchClick = { _, number -> clicks += number; true },
            completeBatch = policy::completeTopLeftClickBatch,
            requestFreshRecheck = {},
        ))
        assertEquals(listOf(1, 2, 3), clicks)
        assertEquals(StuckScreenUnblockPolicy.Action.REPROBE, policy.observe(evidence(
            now = 121_099L, stableForMs = 121_099L, fingerprint = animatedStandardFrame.fingerprint,
        )).action)
        assertEquals(StuckScreenUnblockPolicy.Action.RESTART_CLIENT, policy.observe(evidence(
            now = 121_100L, stableForMs = 121_100L, fingerprint = laterAnimatedStandardFrame.fingerprint,
        )).action)
        assertEquals(StuckScreenUnblockPolicy.Action.UNBLOCKED, policy.observe(evidence(
            now = 121_200L, stableForMs = 0L, fingerprint = wildFrame.fingerprint,
        )).action)
    }

    @Test
    fun `exact live mode selector visual uses semantic ladder with strict gameplay and queue fences`() {
        val selector = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/mode-selector-wild-standard-casual-live-20261029-190503.png",
        )))
        assertTrue(TournamentModeSelectorVisualPolicy.isVisible(selector))
        val kind = UpstreamScreenStateRecovery.classifyImageForTest("二=", selector)
        assertEquals("TOURNAMENT_MODE_SELECTOR", kind)
        assertTrue(SafeIdleUnblockScreenPolicy.matches(
            screenKind = kind,
            screenMode = ModeEnum.TOURNAMENT.name,
            hubModeChooserVisual = false,
            eventQuestOverlayVisual = false,
            tournamentModeSelectorVisual = true,
        ), "only the exact selector structure qualifies for the bounded startup ladder")
        assertFalse(SafeIdleUnblockScreenPolicy.matches(
            screenKind = kind,
            screenMode = ModeEnum.TOURNAMENT.name,
            hubModeChooserVisual = false,
            eventQuestOverlayVisual = false,
            tournamentModeSelectorVisual = false,
        ), "the semantic screen label without exact visual proof is insufficient")

        listOf(
            "hub-mode-chooser-live-20261029-035630.png",
            "reconnect-failure-dialog-over-deck-selection.png",
            "matchmaking-reward-modal-20261021-200538.png",
        ).forEach { filename ->
            val confuser = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
                "/offline-ocr/screen-recovery/$filename",
            )))
            assertFalse(TournamentModeSelectorVisualPolicy.isVisible(confuser), "$filename is not the selector")
        }

        fun semanticFrame(hash: String) = StuckScreenUnblockFrameIdentity.create(
            processId = 34_996,
            windowId = "native@selector",
            perceptualFingerprint = hash,
            exactReconnectDialog = false,
            semanticScreenIdentity = StuckScreenUnblockFrameIdentity.semanticScreenIdentity(
                screenKind = kind,
                screenMode = ModeEnum.TOURNAMENT.name,
            ),
        )
        val frame1 = semanticFrame("blue-card-animation-1")
        val frame2 = semanticFrame("blue-card-animation-2")
        val frame3 = semanticFrame("blue-card-animation-3")
        assertTrue(StuckScreenUnblockFrameIdentity.isSameClientScreen(frame1, frame3))

        val policy = StuckScreenUnblockPolicy()
        val first = policy.observe(evidence(
            now = 61_000L,
            stableForMs = 60_000L,
            fingerprint = frame1.fingerprint,
        ))
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT, first.action)
        val clicks = mutableListOf<Int>()
        var rechecks = 0
        assertTrue(StuckScreenUnblockDispatch.dispatchTopLeftBatch(
            batchInFlight = AtomicBoolean(false),
            action = first.action,
            firstClick = first.clickNumber,
            freshCaptureAuthorized = StuckScreenUnblockFrameIdentity.isSameClientScreen(frame1, frame2),
            point = Point(0, 0),
            expectedFingerprint = frame1.fingerprint,
            completionTimeMs = { 61_100L },
            dispatchClick = { point, number ->
                assertEquals(Point(0, 0), point)
                clicks += number
                true
            },
            completeBatch = policy::completeTopLeftClickBatch,
            requestFreshRecheck = { rechecks++ },
        ))
        assertEquals(listOf(1, 2, 3), clicks)
        assertEquals(1, rechecks)
        assertEquals(StuckScreenUnblockPolicy.Action.REPROBE, policy.observe(evidence(
            now = 121_099L,
            stableForMs = 120_099L,
            fingerprint = frame2.fingerprint,
        )).action)
        val restart = policy.observe(evidence(
            now = 121_100L,
            stableForMs = 120_100L,
            fingerprint = frame3.fingerprint,
        ))
        assertEquals(StuckScreenUnblockPolicy.Action.RESTART_CLIENT, restart.action)
        var restartCount = 0
        listOf(
            evidence(now = 121_100L, fingerprint = frame3.fingerprint, activeGame = true),
            evidence(now = 121_100L, fingerprint = frame3.fingerprint, activeQueue = true),
            evidence(now = 121_100L, fingerprint = frame3.fingerprint, paused = true),
            evidence(now = 121_100L, fingerprint = frame3.fingerprint, terminal = true),
            evidence(now = 121_100L, fingerprint = frame3.fingerprint, currentSessionReady = true),
        ).forEach { fenced ->
            assertEquals(StuckScreenUnblockPolicy.Action.STOP, StuckScreenUnblockPolicy().observe(fenced).action)
            assertFalse(StuckScreenUnblockDispatch.dispatchRestartIfAuthorized(
                action = restart.action,
                finalSafetyPassed = false,
                restart = { restartCount++ },
            ))
        }
        assertEquals(0, restartCount)
    }
}
