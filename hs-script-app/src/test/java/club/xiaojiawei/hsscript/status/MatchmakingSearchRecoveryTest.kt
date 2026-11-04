package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.starter.StartupScreenProbeRetryPolicy
import club.xiaojiawei.hsscript.utils.RobotInputDispatchBoundary
import club.xiaojiawei.hsscript.status.surrender.CurrentGameSurrenderTerminalEvidence
import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingDialogRecoveryPolicy
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingInputGuardPolicy
import javax.imageio.ImageIO

class MatchmakingSearchRecoveryTest {
    @Test
    fun `rejected start dispatch records no matchmaking intent but accepted start still does`() {
        LifecycleTrace.resetMatchmakingEvidenceForTest()
        try {
            LifecycleTrace.markMatchmakingInputDispatched(now = 100_000L, dispatchAccepted = false)
            assertFalse(LifecycleTrace.hasRecentMatchmakingInputIntent(now = 100_001L))
            assertFalse(LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard(now = 100_001L))
            assertFalse(
                ScreenRecoveryCapturePurposePolicy.purposeForStateRecovery(
                    tournamentMode = true,
                    activeGame = false,
                    mulligan = false,
                    terminal = false,
                    recentInputIntent = LifecycleTrace.hasRecentMatchmakingInputIntent(now = 100_001L),
                ) == ScreenRecoveryCapturePurpose.MATCHMAKING_SEARCH,
                "rejected input cannot grant matchmaking capture authority or defer stale-screen recovery",
            )

            LifecycleTrace.markMatchmakingInputDispatched(now = 101_000L, dispatchAccepted = true)
            assertTrue(LifecycleTrace.hasRecentMatchmakingInputIntent(now = 101_001L))
            assertTrue(LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard(now = 101_001L))
        } finally {
            LifecycleTrace.resetMatchmakingEvidenceForTest()
        }
    }

    @Test
    fun `fresh confirmed search screen reconciles queue intent after final start proof expires`() {
        LifecycleTrace.resetMatchmakingEvidenceForTest()
        try {
            val rejectedAt = 100_000L
            LifecycleTrace.markMatchmakingInputDispatched(dispatchAccepted = false, now = rejectedAt)
            assertFalse(LifecycleTrace.hasRecentMatchmakingInputIntent(rejectedAt + 1))

            val confirmedAt = rejectedAt + 30_000L
            val visibleSearch = ScreenStateRecovery.StartGameErrorDialogProbe(
                state = MatchmakingDialogRecoveryPolicy.Probe.NO_ERROR_DIALOG,
                provider = "VISUAL_SIGNATURE",
                reason = "matchmaking-search-screen-visible",
            )
            assertFalse(MatchmakingQueueLifecycleEvidencePolicy.propagateIfAuthoritative(
                probe = visibleSearch,
                captureAuthorized = false,
                powerLogLength = 106_649L,
                observedAt = confirmedAt,
            ), "the ScreenStateRecovery probe callsite must require authorized current capture")
            assertFalse(LifecycleTrace.hasRecentMatchmakingInputIntent(confirmedAt + 1))
            assertTrue(MatchmakingQueueLifecycleEvidencePolicy.propagateIfAuthoritative(
                probe = visibleSearch,
                captureAuthorized = true,
                powerLogLength = 106_649L,
                observedAt = confirmedAt,
            ), "fresh authorized search-screen evidence is authoritative even after the final click gate rejects")
            assertTrue(LifecycleTrace.hasRecentActiveMatchmakingScreen(confirmedAt + 1))
            assertTrue(LifecycleTrace.hasRecentMatchmakingInputIntent(confirmedAt + 1),
                "a confirmed UI transition must establish queue intent without pretending the click succeeded")

            val unknown = visibleSearch.copy(provider = "LEGACY", reason = "ocr-unknown")
            assertFalse(MatchmakingQueueLifecycleEvidencePolicy.propagateIfAuthoritative(
                probe = unknown,
                captureAuthorized = false,
                powerLogLength = 106_649L,
                observedAt = confirmedAt + 1,
            ), "unverified screen probes cannot establish queue intent")
        } finally {
            LifecycleTrace.resetMatchmakingEvidenceForTest()
        }
    }

    @Test
    fun `mode changing recovery guard is limited to one minute after matchmaking dispatch`() {
        LifecycleTrace.clearActiveMatchmakingScreenObserved("entry-guard-test-reset")
        try {
            LifecycleTrace.markMatchmakingInputDispatched(dispatchAccepted = true, now = 100_000L)
            assertTrue(LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard(now = 159_999L))
            assertFalse(LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard(now = 160_000L))
            assertFalse(LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard(now = 99_999L))
        } finally {
            LifecycleTrace.clearActiveMatchmakingScreenObserved("entry-guard-test-cleanup")
        }
    }

    @Test
    fun `game17 fresh queue screenshot supersedes unconfirmed result probe and stale FILL_DECK state`() {
        val wasWorking = WorkTimeListener.working
        val wasPaused = PauseStatus.isPause
        LifecycleTrace.resetMatchmakingEvidenceForTest()
        MandatoryRankSurrenderGuard.resetForTest()
        MulliganRankDispatchBarrier.resetForTest()
        try {
            WorkTimeListener.working = true
            PauseStatus.setManualPause(false)
            val terminalCapability = beginTerminalCleanup()
            assertFalse(ActionDispatchGate.allow("matchmaking.start"))

            // Game17's result probe saw TOURNAMENT but could not confirm the
            // destination. The delayed watchdog still carried this terminal
            // fingerprint after the UI had already moved into active search.
            val searchScreen = syntheticSearchScreen()
            assertEquals(
                "MATCHMAKING",
                UpstreamScreenStateRecovery.classifyImageForTest("了了/人三三|于AS二是/", searchScreen),
            )
            assertEquals(
                "matchmaking-search-visual",
                UpstreamScreenStateRecovery.classificationEvidenceForImageTest("", searchScreen),
            )
            val freshQueueTransition = ScreenStateRecovery.recoveryTransitionForTest("搜寻对手 取消")
            assertEquals("MATCHMAKING", freshQueueTransition?.screen)
            assertEquals("WAIT_FOR_GAMEPLAY", freshQueueTransition?.action)
            assertFalse(freshQueueTransition?.enterStrategy ?: true)
            assertFalse(MatchmakingQueueLifecycleEvidencePolicy.propagateClassifiedSearchScreenIfAuthorized(
                screenKind = "MATCHMAKING",
                captureAuthorized = false,
                observedAt = 199_999L,
            ), "an unverified upstream frame must not establish queue intent")
            assertFalse(MatchmakingQueueLifecycleEvidencePolicy.propagateClassifiedSearchScreenIfAuthorized(
                screenKind = "UNKNOWN",
                captureAuthorized = true,
                observedAt = 199_999L,
            ), "a different classified screen must not establish queue intent")
            assertFalse(LifecycleTrace.hasRecentMatchmakingInputIntent(200_000L))

            assertTrue(
                UpstreamScreenStateRecovery.confirmPendingTerminalCleanupAtMatchmaking(
                    screenKind = "MATCHMAKING",
                    confidence = 98,
                    freshCaptureAuthorized = true,
                    resultPageEvidenceVisible = false,
                ),
            )
            assertTrue(MatchmakingQueueLifecycleEvidencePolicy.propagateClassifiedSearchScreenIfAuthorized(
                screenKind = "MATCHMAKING",
                captureAuthorized = true,
                observedAt = 200_000L,
            ), "the Upstream recovery MATCHMAKING callsite must reconcile only the classified authorized search screen")
            assertTrue(LifecycleTrace.hasRecentActiveMatchmakingScreen(200_001L))
            assertTrue(LifecycleTrace.hasRecentMatchmakingInputIntent(200_001L))
            assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
            assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(terminalCapability))
            assertFalse(ActionDispatchGate.allowForState(
                action = "matchmaking.start",
                paused = false,
                working = true,
                currentSessionReady = true,
            ), "terminal cleanup release is separate from the required fresh configured-mode Start proof")
            assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
        } finally {
            LifecycleTrace.resetMatchmakingEvidenceForTest()
            MandatoryRankSurrenderGuard.resetForTest()
            MulliganRankDispatchBarrier.resetForTest()
            WorkTimeListener.working = wasWorking
            PauseStatus.setManualPause(wasPaused)
        }
    }

    @Test
    fun `unknown popup evidence stays unresolved and cannot clear result cleanup`() {
        val wasWorking = WorkTimeListener.working
        val wasPaused = PauseStatus.isPause
        MandatoryRankSurrenderGuard.resetForTest()
        MulliganRankDispatchBarrier.resetForTest()
        try {
            WorkTimeListener.working = true
            PauseStatus.setManualPause(false)
            beginTerminalCleanup()

            val unknownPopup = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().also { graphics ->
                    graphics.color = Color(65, 65, 65)
                    graphics.fillRect(0, 0, width, height)
                    graphics.dispose()
                }
            }
            assertNull(UpstreamScreenStateRecovery.classifyImageForTest("完全无法识别", unknownPopup))
            assertFalse(
                UpstreamScreenStateRecovery.confirmPendingTerminalCleanupAtMatchmaking(
                    screenKind = "UNKNOWN",
                    confidence = 0,
                    freshCaptureAuthorized = false,
                    resultPageEvidenceVisible = false,
                ),
            )
            assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
            // Unknown screens must not schedule a matchmaking action attempt;
            // keeping the cleanup barrier is safe and produces no gate spam.
        } finally {
            MandatoryRankSurrenderGuard.resetForTest()
            MulliganRankDispatchBarrier.resetForTest()
            WorkTimeListener.working = wasWorking
            PauseStatus.setManualPause(wasPaused)
        }
    }

    @Test
    fun `matchmaking classification cannot clear terminal cleanup while result Continue is visible`() {
        val wasWorking = WorkTimeListener.working
        val wasPaused = PauseStatus.isPause
        MandatoryRankSurrenderGuard.resetForTest()
        try {
            WorkTimeListener.working = true
            PauseStatus.setManualPause(false)
            val capability = beginTerminalCleanup()

            assertFalse(
                UpstreamScreenStateRecovery.confirmPendingTerminalCleanupAtMatchmaking(
                    screenKind = "MATCHMAKING",
                    confidence = 98,
                    freshCaptureAuthorized = true,
                    resultPageEvidenceVisible = true,
                ),
                "contradictory visible result/Continue evidence must override generic queue classification",
            )
            assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
            assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(capability))

            assertTrue(
                MandatoryRankSurrenderGuard.confirmAtFreshDeckSelection(
                    screenKind = "DECK_SELECTION",
                    confidence = 98,
                    visualEvidence = "deck-selection-title",
                    freshObservation = true,
                ),
                "a clean, fresh deck destination remains able to complete terminal cleanup",
            )
            assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        } finally {
            MandatoryRankSurrenderGuard.resetForTest()
            WorkTimeListener.working = wasWorking
            PauseStatus.setManualPause(wasPaused)
        }
    }

    @Test
    fun `search visual bypasses slow and unreliable error-modal OCR`() {
        val probe = ScreenStateRecovery.probeStartGameErrorDialogForImage(
            syntheticSearchScreen(),
            "offline-search-fixture",
        ) { _, _ -> error("a visually identified active queue must not invoke OCR") }
        assertEquals(MatchmakingDialogRecoveryPolicy.Probe.NO_ERROR_DIALOG, probe.state)
        assertEquals("VISUAL_SIGNATURE", probe.provider)
        assertEquals("matchmaking-search-screen-visible", probe.reason)
    }

    @Test
    fun `visual search fixture blocks duplicate queue input with empty Power log`() {
        val probe = ScreenStateRecovery.probeStartGameErrorDialogForImage(
            syntheticSearchScreen(),
            "offline-search-queue-input-guard",
        ) { _, _ -> error("visual search must not invoke OCR") }
        assertEquals(MatchmakingDialogRecoveryPolicy.Probe.NO_ERROR_DIALOG, probe.state)
        assertEquals(
            MatchmakingInputGuardPolicy.Decision.BLOCK_ACTIVE_SEARCH,
            MatchmakingInputGuardPolicy.decide(
                observedScreen = "MATCHMAKING",
                recentActiveSearchEvidence = false,
            ),
        )
    }

    @Test
    fun `queue start establishes bounded pre-session capture authority`() {
        assertTrue(
            ScreenRecoveryCapturePurposePolicy.allowsPreSessionMatchmaking(
                purpose = ScreenRecoveryCapturePurpose.MATCHMAKING_SEARCH,
                tournamentMode = true,
                activeGame = false,
                mulligan = false,
                terminal = false,
                recentInputIntent = true,
            ),
        )
    }

    @Test
    fun `upstream stale recovery uses matchmaking purpose before current session log exists`() {
        assertEquals(
            ScreenRecoveryCapturePurpose.MATCHMAKING_SEARCH,
            ScreenRecoveryCapturePurposePolicy.purposeForStateRecovery(
                tournamentMode = true,
                activeGame = false,
                mulligan = false,
                terminal = false,
                recentInputIntent = true,
            ),
        )

        val transition = ScreenStateRecovery.recoveryTransitionForImageForTest(
            image = syntheticSearchScreen(),
            ocrText = "",
            targeted = emptyMap(),
        )
        assertEquals("MATCHMAKING", transition?.screen)
        assertEquals("WAIT_FOR_GAMEPLAY", transition?.action)
        assertFalse(
            ScreenRecoveryCapturePurposePolicy.purposeForStateRecovery(
                tournamentMode = true,
                activeGame = false,
                mulligan = false,
                terminal = false,
                recentInputIntent = false,
            ) == ScreenRecoveryCapturePurpose.MATCHMAKING_SEARCH,
            "without a queue intent, stale recovery must not gain pre-session authority",
        )
    }

    @Test
    fun `search signature rejects deck error and reconnect dialogs`() {
        listOf(
            "deck-selection-screen.png",
            "start-game-error-dialog-over-deck-selection.png",
            "reconnect-failure-dialog-over-deck-selection.png",
        ).forEach { name ->
            val path = "/offline-ocr/screen-recovery/$name"
            val image = requireNotNull(javaClass.getResourceAsStream(path)) { "missing fixture $path" }
                .use(ImageIO::read)
            assertFalse(MatchmakingSearchVisualPolicy.isVisible(image), "false search classification for $name")
        }
    }

    @Test
    fun `saved Battle net launcher capture cannot classify as queue or grant startup menu proof`() {
        val path = "/offline-ocr/screen-recovery/battlenet-launcher-home-20261029-035301.png"
        val image = requireNotNull(javaClass.getResourceAsStream(path)) { "missing fixture $path" }
            .use(ImageIO::read)
        assertTrue(BattleNetLauncherVisualPolicy.isVisible(image))
        assertFalse(MatchmakingSearchVisualPolicy.isVisible(image))
        assertNull(UpstreamScreenStateRecovery.classifyImageForTest("搜寻对手 取消", image))
        assertNull(ScreenStateRecovery.recoveryTransitionForImageForTest(image, "搜寻对手 取消", emptyMap()))
        assertFalse(
            StartupInputReadiness.recordStartupOnlineScreen(
                pid = 7188,
                hwnd = "native@0x1605ca",
                screen = "MATCHMAKING",
                confidence = 98,
                evidence = "matchmaking-search-visual",
                enabledStartButtonVisible = false,
                modalVisible = false,
                now = 100L,
            ),
            "queue visuals must not mint generic startup online-menu readiness",
        )
        assertFalse(StartupInputReadiness.isMenuReady(7188, "native@0x1605ca", "TOURNAMENT"))
    }

    @Test
    fun `startup handoff defers on captured Battle net frame then classifies fresh hub chooser without OCR`() {
        val launcherPath = "/offline-ocr/screen-recovery/battlenet-launcher-startup-live-20261031-redacted.png"
        val launcher = requireNotNull(javaClass.getResourceAsStream(launcherPath)) { "missing fixture $launcherPath" }
            .use(ImageIO::read)
        val chooserPath = "/offline-ocr/screen-recovery/hub-mode-chooser-live-20261029-035630.png"
        val chooser = requireNotNull(javaClass.getResourceAsStream(chooserPath)) { "missing fixture $chooserPath" }
            .use(ImageIO::read)

        StartupInputReadiness.resetForTest()
        try {
            assertEquals(
                StartupHandoffProgressPolicy.PreOcrAction.DEFER_BATTLENET_LAUNCHER,
                StartupHandoffProgressPolicy.preOcrAction(
                    purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
                    currentSessionReady = false,
                    battleNetLauncherVisual = BattleNetLauncherVisualPolicy.isVisible(launcher),
                    hubModeChooserVisual = HubModeChooserVisualPolicy.isVisible(launcher),
                    modalVisible = false,
                ),
            )
            assertFalse(StartupInputReadiness.isMenuReady(7188, "native@0x1605ca", "TOURNAMENT"),
                "a launcher handoff frame defers; it grants no startup readiness")
            assertFalse(StartupInputReadiness.isHubTraditionalBattleSelectionReady(
                7188, "native@0x1605ca", "HUB",
            ), "a launcher frame grants no chooser action")
            assertEquals(
                StartupHandoffProgressPolicy.PreOcrAction.RUN_OCR,
                StartupHandoffProgressPolicy.preOcrAction(
                    purpose = ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
                    currentSessionReady = false,
                    battleNetLauncherVisual = true,
                    hubModeChooserVisual = false,
                    modalVisible = false,
                ),
                "launcher fast-defer is restricted to the startup handoff purpose",
            )

            assertEquals(
                StartupHandoffProgressPolicy.PreOcrAction.CLASSIFY_HUB_CHOOSER_VISUALLY,
                StartupHandoffProgressPolicy.preOcrAction(
                    purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
                    currentSessionReady = false,
                    battleNetLauncherVisual = BattleNetLauncherVisualPolicy.isVisible(chooser),
                    hubModeChooserVisual = HubModeChooserVisualPolicy.isVisible(chooser),
                    modalVisible = false,
                ),
            )
            assertEquals("TOURNAMENT", UpstreamScreenStateRecovery.classifyImageForTest("", chooser),
                "the fresh four-button chooser is classified visually without waiting on broad OCR")
            assertTrue(StartupHubModeChooserPolicy.canAuthorizeTraditionalBattleSelection(
                purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
                currentSessionReady = false,
                screenKind = "TOURNAMENT",
                confidence = 94,
                evidence = "hub-mode-chooser-visual",
                currentMode = "HUB",
                modalVisible = false,
                activeGame = false,
                activeMatchmaking = false,
                trustedCurrentWindow = true,
            ), "fresh chooser evidence permits only its exact Traditional Battle entry action")
            assertFalse(StartupInputReadiness.isMenuReady(7188, "native@0x1605ca", "TOURNAMENT"))
            StartupInputReadiness.recordHubModeChooser(7188, "native@0x1605ca", now = 2_000L)
            assertTrue(StartupInputReadiness.isHubTraditionalBattleSelectionReady(
                7188, "native@0x1605ca", "HUB", now = 2_001L,
            ), "only the later fresh chooser frame creates its narrow one-use entry proof")
            assertFalse(StartupInputReadiness.isMenuReady(7188, "native@0x1605ca", "TOURNAMENT"),
                "a chooser action proof never becomes generic online-menu readiness")
            assertFalse(StartupInputReadiness.isDeckSelectionStartReady(7188, "native@0x1605ca", now = 2_001L),
                "chooser proof cannot authorize matchmaking Start")
        } finally {
            StartupInputReadiness.resetForTest()
        }
    }

    @Test
    fun `saved Hearthstone hub chooser keeps its narrow configured mode entry proof`() {
        val path = "/offline-ocr/screen-recovery/hub-mode-chooser-live-20261029-035630.png"
        val image = requireNotNull(javaClass.getResourceAsStream(path)) { "missing fixture $path" }
            .use(ImageIO::read)
        assertFalse(BattleNetLauncherVisualPolicy.isVisible(image))
        assertEquals("TOURNAMENT", UpstreamScreenStateRecovery.classifyImageForTest("", image))
        assertTrue(StartupHubModeChooserPolicy.shouldDeferGenericIdleUnblock(
            screenKind = "TOURNAMENT", screenMode = "HUB", hubModeChooserVisual = true,
        ), "the exact chooser has a specific authorized entry action; generic top-left clicks must defer")
        assertFalse(StartupHubModeChooserPolicy.shouldDeferGenericIdleUnblock(
            screenKind = "TOURNAMENT", screenMode = "TOURNAMENT", hubModeChooserVisual = true,
        ), "a tournament screen does not inherit the HUB chooser exception")
        StartupInputReadiness.resetForTest()
        try {
            StartupInputReadiness.recordHubModeChooser(7188, "native@0x1605ca", now = 1_000L)
            assertTrue(StartupInputReadiness.isHubTraditionalBattleSelectionReady(
                7188, "native@0x1605ca", "HUB", now = 2_000L,
            ))
            var dispatched: String? = null
            assertTrue(StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
                chooserProofFresh = true,
                paused = false,
                processId = 7188,
                hwnd = "native@0x1605ca",
            ) { dispatched = it; true })
            assertEquals(StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION, dispatched)
        } finally {
            StartupInputReadiness.resetForTest()
        }
    }

    @Test
    fun `same current frame with HUB chooser geometry and menu OCR cannot be MATCHMAKING`() {
        val hubFrame = syntheticHubChooserOverQueueColors()
        assertTrue(MatchmakingSearchVisualPolicy.isVisible(hubFrame), "fixture reproduces the red/dark false-positive")
        assertTrue(HubModeChooserVisualPolicy.isVisible(hubFrame), "four stacked HUB buttons identify the chooser")
        assertEquals("TOURNAMENT", UpstreamScreenStateRecovery.classifyImageForTest("传统对战 酒馆战棋 竞技模式 其他模式", hubFrame))
        assertEquals(
            "hub-mode-chooser-visual-and-text",
            UpstreamScreenStateRecovery.classificationEvidenceForImageTest("传统对战 酒馆战棋 竞技模式 其他模式", hubFrame),
        )
        assertEquals(
            "TOURNAMENT",
            UpstreamScreenStateRecovery.classifyImageForTest("选择套牌 卡牌包 选择套牌", hubFrame),
            "the visible chooser must outrank unrelated global OCR from other UI regions",
        )
        assertEquals(
            "hub-mode-chooser-visual",
            UpstreamScreenStateRecovery.classificationEvidenceForImageTest("选择套牌 卡牌包", hubFrame),
        )
        val chooserEvidence = UpstreamScreenStateRecovery.classificationEvidenceForImageTest(
            "传统对战 酒馆战棋 竞技模式 其他模式", hubFrame,
        )
        val sameFrameActiveQueueEvidence = StartupHubModeChooserPolicy.hasActiveMatchmakingEvidence(
            matchmakingSearchVisual = MatchmakingSearchVisualPolicy.isVisible(hubFrame),
            hubModeChooserVisual = HubModeChooserVisualPolicy.isVisible(hubFrame),
            screenKind = "TOURNAMENT",
            screenMode = "HUB",
            evidence = chooserEvidence,
            modalVisible = false,
            recentActiveMatchmaking = false,
            activeEntryGuard = false,
        )
        assertFalse(
            sameFrameActiveQueueEvidence,
            "the exact four-option chooser explains the same-frame red/dark queue-color false positive",
        )
        assertTrue(
            StartupHubModeChooserPolicy.hasActiveMatchmakingEvidence(
                matchmakingSearchVisual = true,
                hubModeChooserVisual = true,
                screenKind = "TOURNAMENT",
                screenMode = "HUB",
                evidence = chooserEvidence,
                modalVisible = false,
                recentActiveMatchmaking = true,
                activeEntryGuard = false,
            ),
            "independent recent queue evidence remains a hard veto",
        )
        assertTrue(
            StartupHubModeChooserPolicy.canAuthorizeTraditionalBattleSelection(
                purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
                currentSessionReady = false,
                screenKind = "TOURNAMENT",
                confidence = 94,
                evidence = "hub-mode-chooser-visual",
                currentMode = "HUB",
                modalVisible = false,
                activeGame = false,
                activeMatchmaking = sameFrameActiveQueueEvidence,
                trustedCurrentWindow = true,
            ),
            "a structural visual proof should remain sufficient for the one traditional-entry action",
        )
        assertTrue(MatchmakingEntryRecoveryPolicy.shouldBlockModeChangingRecovery(true, "PACK_OPENING"))
        assertTrue(MatchmakingEntryRecoveryPolicy.shouldBlockModeChangingRecovery(true, "DECK_SELECTION"))
        assertFalse(MatchmakingEntryRecoveryPolicy.shouldBlockModeChangingRecovery(true, "MATCHMAKING"))
        assertFalse(MatchmakingEntryRecoveryPolicy.shouldBlockModeChangingRecovery(true, "GAMEPLAY"))
        assertFalse(MatchmakingEntryRecoveryPolicy.shouldBlockModeChangingRecovery(false, "PACK_OPENING"))

        // Exercise the actual startup chain: frame classification -> policy ->
        // process/window-bound proof -> final dispatch action. This catches the
        // old failure where TournamentModeStrategy silently fell back to the
        // generic mouse.left tag and the last hard gate rejected the click.
        val ocr = "传统对战 酒馆战棋 竞技模式 其他模式"
        val evidence = UpstreamScreenStateRecovery.classificationEvidenceForImageTest(ocr, hubFrame)
        val activeQueueEvidence = StartupHubModeChooserPolicy.hasActiveMatchmakingEvidence(
            matchmakingSearchVisual = MatchmakingSearchVisualPolicy.isVisible(hubFrame),
            hubModeChooserVisual = HubModeChooserVisualPolicy.isVisible(hubFrame),
            screenKind = UpstreamScreenStateRecovery.classifyImageForTest(ocr, hubFrame),
            screenMode = "HUB",
            evidence = evidence,
            modalVisible = false,
            recentActiveMatchmaking = false,
            activeEntryGuard = false,
        )
        val activeGameFromStaleLatch = StartupGamePhaseEvidencePolicy.hasAuthoritativeActiveGame(
            warModelAuthoritative = false,
            warInProgress = true,
        )
        assertFalse(activeGameFromStaleLatch, "WarEx.inWar with an empty, unbound Power.log is stale state")
        assertTrue(
            StartupGamePhaseEvidencePolicy.hasAuthoritativeActiveGame(
                warModelAuthoritative = true,
                warInProgress = true,
            ),
            "a fresh current-session game model retains its active-game fence",
        )
        val startupPurpose = ScreenRecoveryCapturePurposePolicy.purposeForStateRecovery(
            tournamentMode = false,
            activeGame = activeGameFromStaleLatch,
            mulligan = false,
            terminal = false,
            recentInputIntent = false,
            warModelAuthoritative = false,
            startupProbe = true,
            noActiveQueueEvidence = !activeQueueEvidence,
        )
        assertEquals(ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE, startupPurpose)
        assertTrue(
            StartupHubModeChooserPolicy.canAuthorizeTraditionalBattleSelection(
                purpose = startupPurpose,
                currentSessionReady = false,
                screenKind = UpstreamScreenStateRecovery.classifyImageForTest(ocr, hubFrame),
                confidence = 96,
                evidence = evidence,
                currentMode = "HUB",
                modalVisible = false,
                activeGame = activeGameFromStaleLatch,
                activeMatchmaking = activeQueueEvidence,
                trustedCurrentWindow = true,
            ),
        )
        assertTrue(StartupInputAuthorizationPolicy.allowsHubTraditionalBattleSelection(
            action = StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION,
            processId = 26500,
            hwnd = "current-game-window",
            exactWindowVerified = true,
            freshHubChooserReady = true,
        ), "fresh exact HUB chooser proof is sufficient before Power.log has bytes")
        assertFalse(StartupInputAuthorizationPolicy.allowsHubTraditionalBattleSelection(
            action = "mouse.left",
            processId = 26500,
            hwnd = "current-game-window",
            exactWindowVerified = true,
            freshHubChooserReady = true,
        ))
        assertFalse(StartupInputAuthorizationPolicy.allowsHubTraditionalBattleSelection(
            action = StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION,
            processId = 26501,
            hwnd = "different-window",
            exactWindowVerified = false,
            freshHubChooserReady = true,
        ))
        assertFalse(StartupInputAuthorizationPolicy.allowsHubTraditionalBattleSelection(
            action = StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION,
            processId = 26500,
            hwnd = "current-game-window",
            exactWindowVerified = true,
            freshHubChooserReady = true,
            activeMatchmaking = true,
        ), "a previously fresh chooser proof cannot authorize entry after active queue evidence appears")
        assertFalse(StartupInputAuthorizationPolicy.allowsHubTraditionalBattleSelection(
            action = StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION,
            processId = 26500,
            hwnd = "current-game-window",
            exactWindowVerified = true,
            freshHubChooserReady = true,
            activeGame = true,
        ), "a previously fresh chooser proof cannot authorize entry after gameplay begins")
        // Startup's first capture can be a loading/ad frame. It must neither
        // mint readiness nor stop the bounded probe before the chooser settles.
        val initialLoadingFrame = BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().also { graphics ->
                graphics.color = Color(128, 128, 128)
                graphics.fillRect(0, 0, width, height)
                graphics.dispose()
            }
        }
        assertNull(UpstreamScreenStateRecovery.classifyImageForTest("", initialLoadingFrame))
        StartupInputReadiness.invalidate(101, "hwnd-a")
        assertFalse(StartupInputReadiness.isHubTraditionalBattleSelectionReady(101, "hwnd-a", "HUB", 30_000L))
        assertTrue(StartupScreenProbeRetryPolicy.shouldContinue(30_000L, paused = false))
        assertFalse(ActionDispatchGate.allowForState(
            action = StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION,
            paused = false,
            working = true,
            currentSessionReady = false,
            startupTraditionalBattleSelectionReady = false,
        ), "initial UNKNOWN observation cannot authorize the mode click")

        val now = 70_000L
        StartupInputReadiness.recordHubModeChooser(101, "hwnd-a", now)
        val exactProof = StartupInputReadiness.isHubTraditionalBattleSelectionReady(101, "hwnd-a", "HUB", now + 1)
        val dispatched = java.util.concurrent.atomic.AtomicInteger()
        if (ActionDispatchGate.allowForState(
                action = StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION,
                paused = false,
                working = true,
                currentSessionReady = false,
                startupTraditionalBattleSelectionReady = exactProof,
            )
        ) dispatched.incrementAndGet()
        assertEquals(1, dispatched.get(), "exact Traditional Battle action reaches dispatch")
        assertFalse(ActionDispatchGate.allowForState(
            action = "mouse.left",
            paused = false,
            working = true,
            currentSessionReady = false,
            startupTraditionalBattleSelectionReady = exactProof,
        ), "untyped generic click remains blocked")
        assertFalse(StartupScreenProbeRetryPolicy.shouldContinue(90_000L, paused = false))
    }

    @Test
    fun `notice postcheck preserves exact HUB chooser proof and idle probe can refresh it`() {
        StartupInputReadiness.resetForTest()
        try {
            val frame = syntheticHubChooserOverQueueColors()
            val ocr = "传统对战 酒馆战棋 竞技模式 其他模式"
            val screen = UpstreamScreenStateRecovery.classifyImageForTest(ocr, frame)
            val evidence = UpstreamScreenStateRecovery.classificationEvidenceForImageTest(ocr, frame)
            assertEquals("TOURNAMENT", screen)
            assertEquals("hub-mode-chooser-visual-and-text", evidence)

            val postcheckProof = StartupNoticePostcheckProofPolicy.choose(
                purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
                currentSessionReady = false,
                screenKind = screen,
                confidence = 96,
                evidence = evidence,
                currentMode = "HUB",
                modalVisible = false,
                activeGame = false,
                activeMatchmaking = false,
                terminalState = false,
                trustedCurrentWindow = true,
            )
            assertEquals(StartupNoticePostcheckProofPolicy.Proof.HUB_MODE_CHOOSER, postcheckProof)
            StartupInputReadiness.recordHubModeChooser(48336, "native@0x40a0728", now = 90_000L)
            val chooserReady = StartupInputReadiness.isHubTraditionalBattleSelectionReady(
                48336, "native@0x40a0728", "HUB", now = 90_001L,
            )
            assertTrue(chooserReady, "chooser proof is keyed to the exact process, HWND, mode, and fresh observation")
            assertFalse(
                StartupInputReadiness.isMenuReady(48336, "native@0x40a0728", "HUB"),
                "a chooser observation must not become broad online-menu readiness",
            )
            assertTrue(ActionDispatchGate.allowForState(
                action = StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION,
                paused = false,
                working = true,
                currentSessionReady = false,
                startupTraditionalBattleSelectionReady = chooserReady,
            ))

            val idleProbeProof = StartupNoticePostcheckProofPolicy.choose(
                purpose = ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
                currentSessionReady = false,
                screenKind = screen,
                confidence = 96,
                evidence = evidence,
                currentMode = "HUB",
                modalVisible = false,
                activeGame = false,
                activeMatchmaking = false,
                terminalState = false,
                trustedCurrentWindow = true,
            )
            assertEquals(
                StartupNoticePostcheckProofPolicy.Proof.HUB_MODE_CHOOSER,
                idleProbeProof,
                "a fresh idle probe may refresh only this exact chooser proof",
            )
            assertEquals(
                StartupNoticePostcheckProofPolicy.Proof.NONE,
                StartupNoticePostcheckProofPolicy.choose(
                    purpose = ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
                    currentSessionReady = false,
                    screenKind = screen,
                    confidence = 96,
                    evidence = evidence,
                    currentMode = "HUB",
                    modalVisible = false,
                    activeGame = false,
                    activeMatchmaking = true,
                    terminalState = false,
                    trustedCurrentWindow = true,
                ),
                "active queue evidence blocks chooser proof",
            )
        } finally {
            StartupInputReadiness.resetForTest()
        }
    }

    @Test
    fun `delayed HUB chooser proof reaches final robot boundary and sends only exact traditional action`() {
        val action = StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION
        StartupInputReadiness.resetForTest()
        try {
            var sentAction: String? = null
            var authorizedAction: String? = null
            var sends = 0
            val noProofSent = RobotInputDispatchBoundary.dispatchIfAuthorized(
                action = action,
                authorize = { candidate ->
                    authorizedAction = candidate
                    ActionDispatchGate.allowForState(
                        action = candidate,
                        paused = false,
                        working = true,
                        currentSessionReady = false,
                        startupTraditionalBattleSelectionReady = false,
                    )
                },
            ) { sends++; sentAction = authorizedAction }
            assertFalse(noProofSent)
            assertEquals(0, sends)

            assertTrue(StartupHubModeChooserPolicy.canAuthorizeTraditionalBattleSelection(
                purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
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
            StartupInputReadiness.recordHubModeChooser(47756, "native@0xa6906f4", now = 70_000L)
            val proof = StartupInputReadiness.isHubTraditionalBattleSelectionReady(
                47756,
                "native@0xa6906f4",
                "HUB",
                now = 70_001L,
            )
            assertTrue(proof)

            val sent = RobotInputDispatchBoundary.dispatchIfAuthorized(
                action = action,
                authorize = { candidate ->
                    authorizedAction = candidate
                    ActionDispatchGate.allowForState(
                        action = candidate,
                        paused = false,
                        working = true,
                        currentSessionReady = false,
                        startupTraditionalBattleSelectionReady = proof,
                    )
                },
            ) { sends++; sentAction = authorizedAction }
            assertTrue(sent)
            assertEquals(1, sends, "accepted final Robot boundary invokes the input sink exactly once")
            assertEquals(action, sentAction)

            val genericSent = RobotInputDispatchBoundary.dispatchIfAuthorized(
                action = "mouse.robot",
                authorize = { candidate -> ActionDispatchGate.allowForState(
                    action = candidate,
                    paused = false,
                    working = true,
                    currentSessionReady = false,
                    startupTraditionalBattleSelectionReady = proof,
                ) },
            ) { sends++ }
            assertFalse(genericSent, "chooser proof cannot authorize a generic Robot click")
            assertEquals(1, sends)
        } finally {
            StartupInputReadiness.resetForTest()
        }
    }

    @Test
    fun `conflicting menu text prevents visual queue false positive without chooser geometry`() {
        val redOrnamentFrame = syntheticSearchScreen()
        assertTrue(MatchmakingSearchVisualPolicy.isVisible(redOrnamentFrame))
        assertFalse(HubModeChooserVisualPolicy.isVisible(redOrnamentFrame))
        assertNull(UpstreamScreenStateRecovery.classifyImageForTest("OCR噪声 传统对战", redOrnamentFrame))
    }

    @Test
    fun `deck selection fixture clears prior search guard while genuine search still blocks`() {
        val deckPath = "/offline-ocr/screen-recovery/deck-selection-screen.png"
        val deckImage = requireNotNull(javaClass.getResourceAsStream(deckPath)) { "missing fixture $deckPath" }
            .use(ImageIO::read)
        assertFalse(
            ScreenStateRecovery.looksLikeAmazonAppstoreSunsetNoticeVisual(deckImage),
            "the warm deck-selection screen must not be mistaken for the Amazon notice",
        )
        val deckScreen = UpstreamScreenStateRecovery.classifyImageForTest("狂野对战 选择套牌", deckImage)
        assertEquals("DECK_SELECTION", deckScreen)

        LifecycleTrace.markActiveMatchmakingScreenObserved()
        assertTrue(LifecycleTrace.hasRecentActiveMatchmakingScreen())
        LifecycleTrace.clearActiveMatchmakingScreenObserved("fresh-deck-selection-test")
        assertFalse(LifecycleTrace.hasRecentActiveMatchmakingScreen())
        assertEquals(
            MatchmakingInputGuardPolicy.Decision.ALLOW_INPUT,
            MatchmakingInputGuardPolicy.decide(
                observedScreen = deckScreen,
                recentActiveSearchEvidence = LifecycleTrace.hasRecentActiveMatchmakingScreen(),
            ),
        )
        assertEquals(
            MatchmakingInputGuardPolicy.Decision.ALLOW_INPUT,
            MatchmakingInputGuardPolicy.decide(
                observedScreen = "UNKNOWN",
                recentActiveSearchEvidence = LifecycleTrace.hasRecentActiveMatchmakingScreen(),
            ),
            "cleared evidence must not turn an unavailable watchdog screenshot into a false active-search block",
        )

        val searchScreen = UpstreamScreenStateRecovery.classifyImageForTest("", syntheticSearchScreen())
        assertEquals("MATCHMAKING", searchScreen)
        assertEquals(
            MatchmakingInputGuardPolicy.Decision.BLOCK_ACTIVE_SEARCH,
            MatchmakingInputGuardPolicy.decide(
                observedScreen = searchScreen,
                recentActiveSearchEvidence = false,
            ),
        )
    }

    @Test
    fun `fresh queue observation suppresses stale FILL_DECK lifecycle recovery during normal search`() {
        assertTrue(LifecycleTrace.shouldSuppressStateRecoveryForMatchmaking(observedAt = 10_000, now = 10_001))
        assertTrue(LifecycleTrace.shouldSuppressStateRecoveryForMatchmaking(observedAt = 10_000, now = 129_999))
        assertFalse(LifecycleTrace.shouldSuppressStateRecoveryForMatchmaking(observedAt = 10_000, now = 130_000))
        assertFalse(LifecycleTrace.shouldSuppressStateRecoveryForMatchmaking(observedAt = 0, now = 10_001))
    }

    @Test
    fun `authorized visual queue evidence propagates with empty Power log but unknown screen does not`() {
        val queueProbe = ScreenStateRecovery.StartGameErrorDialogProbe(
            state = MatchmakingDialogRecoveryPolicy.Probe.NO_ERROR_DIALOG,
            provider = "VISUAL_SIGNATURE",
            reason = "matchmaking-search-screen-visible",
        )
        assertTrue(
            MatchmakingQueueLifecycleEvidencePolicy.isAuthoritativeActiveSearch(
                queueProbe,
                captureAuthorized = true,
                powerLogLength = 0L,
            ),
            "a verified current-client capture is sufficient while the new session Power.log is empty",
        )

        val unknownProbe = queueProbe.copy(
            state = MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
            provider = "LEGACY",
            reason = "ocr-unverified-or-low-confidence",
        )
        assertFalse(
            MatchmakingQueueLifecycleEvidencePolicy.isAuthoritativeActiveSearch(
                unknownProbe,
                captureAuthorized = true,
                powerLogLength = 0L,
            ),
        )
        assertFalse(
            MatchmakingQueueLifecycleEvidencePolicy.isAuthoritativeActiveSearch(
                queueProbe,
                captureAuthorized = false,
                powerLogLength = 0L,
            ),
        )
    }

    private fun beginTerminalCleanup() = run {
        MandatoryRankSurrenderGuard.begin("game-123:self")
        val capability = requireNotNull(
            MandatoryRankSurrenderGuard.authorizeTerminalCleanup(
                CurrentGameSurrenderTerminalEvidence(
                    gameIdentity = "game-123:self",
                    ownEntityId = "self",
                    opponentEntityId = "opponent",
                    ownPlayState = "CONCEDED",
                    opponentPlayState = "WON",
                    finalGameOver = true,
                    complete = true,
                ),
            ),
        )
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", capability))
        capability
    }

    private fun syntheticSearchScreen(): BufferedImage =
        BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().also { graphics ->
                graphics.color = Color(44, 44, 44)
                graphics.fillRect(0, 0, width, height)
                graphics.color = Color(205, 35, 12)
                graphics.fillRect((width * .41).toInt(), (height * .28).toInt(), (width * .20).toInt(), (height * .38).toInt())
                graphics.color = Color(239, 190, 67)
                graphics.fillRect((width * .45).toInt(), (height * .80).toInt(), (width * .14).toInt(), (height * .09).toInt())
                graphics.color = Color(30, 30, 30)
                graphics.fillRect((width * .61).toInt(), (height * .84).toInt(), (width * .13).toInt(), (height * .12).toInt())
                graphics.dispose()
            }
        }

    private fun syntheticHubChooserOverQueueColors(): BufferedImage = syntheticSearchScreen().apply {
        createGraphics().also { graphics ->
            graphics.color = Color(232, 218, 194)
            listOf(.285, .365, .445, .495).forEach { y ->
                graphics.fillRect((width * .39).toInt(), (height * y).toInt(), (width * .22).toInt(), (height * .035).toInt())
            }
            graphics.dispose()
        }
    }
}
