package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class ResultDeckSelectionStartProofPolicyTest {
    @AfterEach
    fun resetProof() = StartupInputReadiness.resetForTest()

    @Test
    fun `fresh authorized exact Wild deck destination records Start proof for immediate queue continuation`() {
        val image = fixture("/offline-ocr/screen-recovery/wild-deck-selection-live-20261030-030308.png")
        val mode = TournamentModeConfirmation.observeImageVisualOnly(image)
        assertEquals(ObservedTournamentMode.WILD, mode.observedMode)
        assertTrue(ScreenWatchdog.hasEnabledDeckSelectionStartButton(image))

        val recorded = ResultDeckSelectionStartProofPolicy.record(
            captureAuthorized = true,
            captureAgeMs = 25,
            pid = 40_020,
            hwnd = "exact-game-window",
            confidence = 100,
            evidence = "deck-selection-title",
            expectedMode = RunModeEnum.WILD.name,
            observedMode = mode.observedMode.name,
            enabledStartVisible = true,
            modalVisible = false,
            now = 10_000L,
        )

        assertTrue(recorded)
        assertTrue(StartupInputReadiness.isDeckSelectionStartReady(40_020, "exact-game-window", 10_001L))
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "matchmaking.start",
                paused = false,
                working = true,
                currentSessionReady = false,
                freshDeckSelectionStartReady = true,
            ),
            "fresh result-screen proof should let the existing post-result queue continuation reach its normal dispatch gates",
        )
        assertTrue(
            TerminalCleanupQueueResumePolicy.shouldResume(
                destinationScreen = "DECK_SELECTION",
                destinationConfirmed = true,
                working = true,
                paused = false,
                inWar = false,
                mandatoryRankSurrenderPending = false,
                terminalCleanupPending = false,
                deckStrategyReady = true,
            ),
            "fresh screen proof does not bypass the independent terminal-cleanup/rank workflow gate",
        )
    }

    @Test
    fun `stale unauthorized unknown and opposite mode result frames cannot leave Start proof`() {
        assertFalse(record(
            captureAuthorized = true,
            captureAgeMs = ResultDeckSelectionStartProofPolicy.MAX_CAPTURE_AGE_MS + 1,
            now = 20_000L,
        ))
        assertFalse(StartupInputReadiness.isDeckSelectionStartReady(40_020, "exact-game-window", 20_001L))

        assertFalse(record(
            captureAuthorized = false,
            captureAgeMs = 10,
            now = 20_000L,
        ))
        assertFalse(record(
            captureAuthorized = true,
            captureAgeMs = 10,
            observedMode = "UNKNOWN",
            now = 20_000L,
        ))
        val standardImage = fixture("/offline-ocr/screen-recovery/standard-deck-selection-live-20261029-040056.png")
        val observedStandard = TournamentModeConfirmation.observeImageVisualOnly(standardImage)
        assertEquals(ObservedTournamentMode.STANDARD, observedStandard.observedMode)
        assertFalse(record(
            captureAuthorized = true,
            captureAgeMs = 10,
            observedMode = observedStandard.observedMode.name,
            now = 20_000L,
        ))

        val unknownImage = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
        val observedUnknown = TournamentModeConfirmation.observeImageVisualOnly(unknownImage)
        assertEquals(ObservedTournamentMode.UNKNOWN, observedUnknown.observedMode)
        assertFalse(record(
            captureAuthorized = true,
            captureAgeMs = 10,
            observedMode = observedUnknown.observedMode.name,
            now = 20_000L,
        ))
        assertFalse(StartupInputReadiness.isDeckSelectionStartReady(40_020, "exact-game-window", 20_001L))
    }

    @Test
    fun `modal or disabled Start control invalidates result page readiness`() {
        assertFalse(record(
            captureAuthorized = true,
            captureAgeMs = 10,
            modalVisible = true,
            now = 30_000L,
        ))
        assertFalse(record(
            captureAuthorized = true,
            captureAgeMs = 10,
            enabledStartVisible = false,
            now = 30_000L,
        ))
        assertFalse(StartupInputReadiness.isDeckSelectionStartReady(40_020, "exact-game-window", 30_001L))
    }

    @Test
    fun `both result probe implementations derive proof from their own authorized fresh deck frame`() {
        val root = moduleRoot()
        val local = Files.readString(root.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "status", "ScreenStateRecovery.kt",
        )))
        val upstream = Files.readString(root.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "status", "UpstreamScreenStateRecovery.kt",
        )))

        assertTrue(local.contains("recordResultDeckSelectionStartProof(") &&
            local.contains("pid = capture.evidence.target?.processId") &&
            local.contains("captureAuthorized = true"),
        )
        assertTrue(upstream.contains("recordResultDeckSelectionStartProof(") &&
            upstream.contains("pid = authorizedCapture.capture.processId.toInt()") &&
            upstream.contains("captureAuthorized = authorizedCapture.foregroundVerified"),
        )
        assertTrue(local.contains("TournamentModeConfirmation.observeImageVisualOnly(capture.image)"))
        assertTrue(upstream.contains("TournamentModeConfirmation.observeImageVisualOnly(capture.image)"))
    }

    private fun fixture(path: String) = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(path)))

    private fun moduleRoot(): Path = listOf(Path.of("."), Path.of("hs-script-app")).first {
        Files.isRegularFile(it.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "status", "ScreenStateRecovery.kt",
        )))
    }

    private fun record(
        captureAuthorized: Boolean,
        captureAgeMs: Long,
        expectedMode: String = RunModeEnum.WILD.name,
        observedMode: String = RunModeEnum.WILD.name,
        enabledStartVisible: Boolean = true,
        modalVisible: Boolean = false,
        now: Long,
    ) = ResultDeckSelectionStartProofPolicy.record(
        captureAuthorized = captureAuthorized,
        captureAgeMs = captureAgeMs,
        pid = 40_020,
        hwnd = "exact-game-window",
        confidence = 100,
        evidence = "deck-selection-title",
        expectedMode = expectedMode,
        observedMode = observedMode,
        enabledStartVisible = enabledStartVisible,
        modalVisible = modalVisible,
        now = now,
    )
}
