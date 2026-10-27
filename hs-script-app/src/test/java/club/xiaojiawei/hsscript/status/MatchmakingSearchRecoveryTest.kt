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
    fun `fresh visual queue evidence clears stale result cleanup and leaves queue input enabled`() {
        val wasWorking = WorkTimeListener.working
        val wasPaused = PauseStatus.isPause
        MandatoryRankSurrenderGuard.resetForTest()
        MulliganRankDispatchBarrier.resetForTest()
        try {
            WorkTimeListener.working = true
            PauseStatus.setManualPause(false)
            val terminalCapability = beginTerminalCleanup()
            assertFalse(ActionDispatchGate.allow("matchmaking.start"))

            val searchScreen = syntheticSearchScreen()
            assertEquals(
                "MATCHMAKING",
                UpstreamScreenStateRecovery.classifyImageForTest("了了/人三三|于AS二是/", searchScreen),
            )
            assertEquals(
                "matchmaking-search-visual",
                UpstreamScreenStateRecovery.classificationEvidenceForImageTest("", searchScreen),
            )

            assertTrue(
                UpstreamScreenStateRecovery.confirmPendingTerminalCleanupAtMatchmaking(
                    screenKind = "MATCHMAKING",
                    confidence = 98,
                    freshCaptureAuthorized = true,
                ),
            )
            assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
            assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(terminalCapability))
            assertTrue(ActionDispatchGate.allowForState(
                action = "matchmaking.start",
                paused = false,
                working = true,
                currentSessionReady = true,
            ))
            assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
        } finally {
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
    fun `same current frame with HUB chooser geometry and menu OCR cannot be MATCHMAKING`() {
        val hubFrame = syntheticHubChooserOverQueueColors()
        assertTrue(MatchmakingSearchVisualPolicy.isVisible(hubFrame), "fixture reproduces the red/dark false-positive")
        assertTrue(HubModeChooserVisualPolicy.isVisible(hubFrame), "four stacked HUB buttons identify the chooser")
        assertEquals("TOURNAMENT", UpstreamScreenStateRecovery.classifyImageForTest("传统对战 酒馆战棋 竞技模式 其他模式", hubFrame))
        assertEquals(
            "hub-mode-chooser-visual-and-text",
            UpstreamScreenStateRecovery.classificationEvidenceForImageTest("传统对战 酒馆战棋 竞技模式 其他模式", hubFrame),
        )

        // Exercise the actual startup chain: frame classification -> policy ->
        // process/window-bound proof -> final dispatch action. This catches the
        // old failure where TournamentModeStrategy silently fell back to the
        // generic mouse.left tag and the last hard gate rejected the click.
        val ocr = "传统对战 酒馆战棋 竞技模式 其他模式"
        val evidence = UpstreamScreenStateRecovery.classificationEvidenceForImageTest(ocr, hubFrame)
        assertTrue(
            StartupHubModeChooserPolicy.canAuthorizeTraditionalBattleSelection(
                purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
                currentSessionReady = false,
                screenKind = UpstreamScreenStateRecovery.classifyImageForTest(ocr, hubFrame),
                confidence = 96,
                evidence = evidence,
                currentMode = "HUB",
                modalVisible = false,
                activeGame = false,
                activeMatchmaking = false,
                trustedCurrentWindow = true,
            ),
        )
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
