package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class StartupDeckModeNavigationIntegrationTest {
    @Test
    fun `live standard deck chooser with unrecognized mode dispatches only bounded back navigation`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/standard-deck-selection-live-20261029-045427.png",
        )))
        val screen = UpstreamScreenStateRecovery.classifyStartupDeckSelectionVisualForTest("", image)
        assertEquals("DECK_SELECTION", screen)
        assertTrue(ScreenWatchdog.hasEnabledDeckSelectionStartButton(image))
        assertTrue(ScreenStateRecovery.wrongModeTooltipVisualForTest(image))
        val visibleWrongMode = TournamentModeConfirmation.observeImage(image)
        assertEquals("UNKNOWN", visibleWrongMode.observedMode.name)

        val decision = StartupDeckModeNavigationPolicy.decide(
            purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE.name,
            screenKind = screen.orEmpty(),
            confidence = 95,
            evidence = "deck-selection-start-control-visual",
            expectedRunMode = RunModeEnum.WILD.name,
            observedRunMode = visibleWrongMode.observedMode.name,
            enabledStartButtonVisible = true,
            modalVisible = false,
            currentSessionReady = false,
            activeGame = false,
            activeMatchmaking = false,
            terminal = false,
            trustedCurrentWindow = true,
        )
        assertTrue(decision.allowed)
        assertTrue(StartupInputReadiness.recordDeckSelectionModeNavigation(
            pid = 25948,
            hwnd = "captured-live-window",
            expectedRunMode = "WILD",
            observedRunMode = visibleWrongMode.observedMode.name,
            confidence = 95,
            evidence = "deck-selection-start-control-visual",
            enabledStartButtonVisible = true,
            modalVisible = false,
            now = 10_000L,
        ))
        assertFalse(StartupInputReadiness.isDeckSelectionStartReady(25948, "captured-live-window", 10_001L))
        assertFalse(StartupInputAuthorizationPolicy.allowsDeckSelectionStart(
            action = "matchmaking.start",
            processId = 25948,
            hwnd = "captured-live-window",
            exactWindowVerified = true,
            freshDeckSelectionStartReady = StartupInputReadiness.isDeckSelectionStartReady(
                25948, "captured-live-window", 10_001L,
            ),
        ))

        var dispatches = 0
        var reprobes = 0
        assertTrue(StartupDeckModeNavigationPolicy.dispatchWrongModeTooltipReturn(
            image = image,
            expectedMode = RunModeEnum.WILD.name,
            decision = decision,
            dispatchReturn = { dispatches++; true },
            requestFreshSelectorProbe = { reprobes++ },
        ))
        assertEquals(1, dispatches, "the integration dispatch seam must be reached, not just intent-recorded")
        assertEquals(1, reprobes, "accepted Return must request a fresh selector capture")
        assertFalse(StartupDeckModeNavigationPolicy.dispatchWrongModeTooltipReturn(
            image = image,
            expectedMode = RunModeEnum.STANDARD.name,
            decision = decision,
            dispatchReturn = { dispatches++; true },
            requestFreshSelectorProbe = { reprobes++ },
        ), "tooltip affordance is scoped to configured Wild mode")
        assertEquals(1, dispatches)
        assertFalse(StartupInputAuthorizationPolicy.allows(
            action = StartupDeckModeNavigationPolicy.ACTION,
            currentSessionReady = true,
            freshOnlineMenuReady = true,
            startupDeckSelectionModeNavigationReady = false,
        ), "generic current-session or menu readiness must never authorize the Back action")
        assertTrue(StartupInputAuthorizationPolicy.allows(
            action = StartupDeckModeNavigationPolicy.ACTION,
            currentSessionReady = false,
            freshOnlineMenuReady = false,
            startupDeckSelectionModeNavigationReady = true,
        ))
        assertFalse(StartupInputAuthorizationPolicy.allowsDeckSelectionStart(
            action = "matchmaking.start", processId = 25948, hwnd = "live",
            exactWindowVerified = true, freshDeckSelectionStartReady = false,
        ), "the wrong-mode frame may dispatch only Return, never Start")
        assertTrue(StartupInputReadiness.recordDeckSelectionStart(
            pid = 25948, hwnd = "captured-live-window", confidence = 95,
            evidence = "deck-selection-start-control-visual", expectedRunMode = "WILD",
            observedRunMode = "WILD", enabledStartButtonVisible = true, modalVisible = false, now = 10_500L,
        ), "only the later fresh mode-matched capture may create Start proof")
        assertTrue(StartupInputAuthorizationPolicy.allowsDeckSelectionStart(
            action = "matchmaking.start", processId = 25948, hwnd = "captured-live-window",
            exactWindowVerified = true,
            freshDeckSelectionStartReady = StartupInputReadiness.isDeckSelectionStartReady(
                25948, "captured-live-window", 10_501L,
            ),
        ))
        StartupInputReadiness.resetForTest()
    }

    @Test
    fun `deck chooser start proof requires exact configured mode and keeps queue rank policy separate`() {
        val wrongFormat = StartupInputReadiness.recordDeckSelectionStart(
            pid = 25948, hwnd = "live", confidence = 95, evidence = "deck-selection-title",
            expectedRunMode = "WILD", observedRunMode = "STANDARD", enabledStartButtonVisible = true,
            modalVisible = false, now = 20_000L,
        )
        assertFalse(wrongFormat)
        assertFalse(StartupInputReadiness.isDeckSelectionStartReady(25948, "live", 20_001L))

        val exact = StartupInputReadiness.recordDeckSelectionStart(
            pid = 25948, hwnd = "live", confidence = 95, evidence = "deck-selection-title",
            expectedRunMode = "WILD", observedRunMode = "WILD", enabledStartButtonVisible = true,
            modalVisible = false, now = 30_000L,
        )
        assertTrue(exact)
        assertTrue(StartupInputAuthorizationPolicy.allowsDeckSelectionStart(
            action = "matchmaking.start", processId = 25948, hwnd = "live", exactWindowVerified = true,
            freshDeckSelectionStartReady = StartupInputReadiness.isDeckSelectionStartReady(25948, "live", 30_001L),
        ))
        StartupInputReadiness.resetForTest()
    }

    @Test
    fun `navigation is fenced for active gameplay queue terminal modal and unsupported proof`() {
        fun decide(
            purpose: String = "STARTUP_CLIENT_PROBE",
            screen: String = "DECK_SELECTION",
            confidence: Int = 95,
            evidence: String = "deck-selection-title",
            expected: String? = "WILD",
            observed: String? = "UNKNOWN",
            start: Boolean = true,
            modal: Boolean = false,
            session: Boolean = false,
            game: Boolean = false,
            queue: Boolean = false,
            terminal: Boolean = false,
            trusted: Boolean = true,
        ) = StartupDeckModeNavigationPolicy.decide(
            purpose, screen, confidence, evidence, expected, observed, start, modal,
            session, game, queue, terminal, trusted,
        )

        assertFalse(decide(game = true).allowed)
        assertFalse(decide(queue = true).allowed)
        assertFalse(decide(terminal = true).allowed)
        assertFalse(decide(modal = true).allowed)
        assertFalse(decide(purpose = "MATCHMAKING_SEARCH").allowed)
        assertFalse(decide(confidence = 80).allowed)
        assertFalse(decide(observed = "WILD").allowed)
        assertFalse(decide(expected = "ARENA").allowed)
        assertFalse(decide(start = false).allowed)
        assertFalse(StartupDeckModeNavigationPolicy.allowsDispatch(
            action = "matchmaking.start", proofFresh = true, exactWindowVerified = true,
            currentSessionReady = false, activeGame = false, activeMatchmaking = false, terminal = false,
        ))
        assertFalse(StartupDeckModeNavigationPolicy.allowsDispatch(
            action = StartupDeckModeNavigationPolicy.ACTION, proofFresh = true, exactWindowVerified = true,
            currentSessionReady = false, activeGame = false, activeMatchmaking = true, terminal = false,
        ))
    }
}
