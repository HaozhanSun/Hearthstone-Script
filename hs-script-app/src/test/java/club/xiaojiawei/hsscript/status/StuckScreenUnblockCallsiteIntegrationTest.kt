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
        paused: Boolean = false,
        terminal: Boolean = false,
        result: Boolean = false,
    ) = StuckScreenUnblockPolicy.Evidence(
        nowMs = now,
        stableForMs = stableForMs,
        fingerprint = fingerprint,
        captureFresh = true,
        exactWindowVerified = true,
        eligibleIdleProbe = true,
        recognizedSafeBlocker = true,
        currentSessionReady = false,
        activeGame = false,
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
}
