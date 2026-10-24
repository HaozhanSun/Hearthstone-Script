package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.listener.WorkTimeListener
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
            assertTrue(ActionDispatchGate.allow("matchmaking.start"))
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
}
