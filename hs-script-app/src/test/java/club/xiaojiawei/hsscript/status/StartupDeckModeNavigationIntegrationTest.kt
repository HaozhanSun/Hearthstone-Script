package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class StartupDeckModeNavigationIntegrationTest {
    @Test
    fun `fresh selector observation renews exactly one wrong-mode Return attempt`() {
        val attempts = StartupDeckModeNavigationAttemptGate()
        assertTrue(attempts.tryBegin(9704, "live-v709").allowed)
        assertFalse(attempts.tryBegin(9704, "live-v709").allowed)
        // Runtime calls reset only after the exact HUB chooser proof is accepted.
        attempts.reset()
        assertTrue(attempts.tryBegin(9704, "live-v709").allowed)
        assertFalse(attempts.tryBegin(9704, "live-v709").allowed)
    }

    @Test
    fun `v709 live standard selector frame reaches bounded Return action`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/standard-deck-selection-live-20261029-063038.png",
        )))
        val screen = UpstreamScreenStateRecovery.classifyStartupDeckSelectionVisualForTest("二=", image)
        assertEquals("DECK_SELECTION", screen)
        assertTrue(ScreenWatchdog.hasEnabledDeckSelectionStartButton(image))
        assertTrue(ScreenStateRecovery.wrongModeTooltipVisualForTest(image))
        val observed = TournamentModeConfirmation.observeImage(image)
        assertEquals("STANDARD", observed.observedMode.name)

        val decision = StartupDeckModeNavigationPolicy.decide(
            purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE.name,
            screenKind = screen.orEmpty(),
            confidence = 94,
            evidence = "deck-selection-start-control-visual",
            expectedRunMode = RunModeEnum.WILD.name,
            observedRunMode = observed.observedMode.name,
            enabledStartButtonVisible = true,
            modalVisible = false,
            currentSessionReady = false,
            activeGame = false,
            activeMatchmaking = false,
            terminal = false,
            trustedCurrentWindow = true,
        )
        var returnDispatches = 0
        var selectorReprobes = 0
        assertTrue(StartupDeckModeNavigationPolicy.dispatchWrongModeTooltipReturn(
            image = image,
            expectedMode = "WILD",
            decision = decision,
            dispatchReturn = { returnDispatches++; true },
            requestFreshSelectorProbe = { selectorReprobes++ },
        ))
        assertEquals(1, returnDispatches)
        assertEquals(1, selectorReprobes)
        assertFalse(StartupInputAuthorizationPolicy.allowsDeckSelectionStart(
            action = "matchmaking.start", processId = 9704, hwnd = "live-v709",
            exactWindowVerified = true, freshDeckSelectionStartReady = false,
        ), "confirmed Standard mode on the live frame must never authorize Wild Start")
    }

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
        assertEquals("STANDARD", visibleWrongMode.observedMode.name)

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
    fun `legacy recovery dispatches only after fresh configured mode start proof`() {
        var deckSelectionOrStartDispatches = 0
        val standardLiveFrame = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/standard-deck-selection-live-20261029-063038.png",
        )))
        assertEquals("STANDARD", TournamentModeConfirmation.observeImage(standardLiveFrame).observedMode.name)
        assertFalse(StartupInputAuthorizationPolicy.dispatchDeckSelectionRecoveryIfReady(
            startupScreenConfirmed = false,
        ) { deckSelectionOrStartDispatches++ }, "UNKNOWN Standard frame must not enter deck recovery or Start")
        assertEquals(0, deckSelectionOrStartDispatches)

        assertTrue(StartupInputReadiness.recordDeckSelectionStart(
            pid = 25948, hwnd = "captured-live-window", confidence = 95,
            evidence = "deck-selection-start-control-visual", expectedRunMode = "WILD",
            observedRunMode = "WILD", enabledStartButtonVisible = true, modalVisible = false, now = 40_000L,
        ))
        assertTrue(StartupInputAuthorizationPolicy.dispatchDeckSelectionRecoveryIfReady(
            startupScreenConfirmed = StartupInputAuthorizationPolicy.allowsDeckSelectionStart(
                action = "matchmaking.start", processId = 25948, hwnd = "captured-live-window",
                exactWindowVerified = true,
                freshDeckSelectionStartReady = StartupInputReadiness.isDeckSelectionStartReady(
                    25948, "captured-live-window", 40_001L,
                ),
            ),
        ) { deckSelectionOrStartDispatches++ })
        assertEquals(1, deckSelectionOrStartDispatches, "fresh Wild proof reaches the recovery callsite once")
        StartupInputReadiness.resetForTest()
    }

    @Test
    fun `stale upstream deck probe classifies unknown mode and cannot apply start recovery`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/standard-deck-selection-live-20261029-063038.png",
        )))
        assertTrue(StartupDeckModeNavigationPolicy.isModeProofProbe(ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE))
        assertEquals("DECK_SELECTION", UpstreamScreenStateRecovery.classifyStartupDeckSelectionVisualForTest("", image))
        assertTrue(ScreenWatchdog.hasEnabledDeckSelectionStartButton(image))
        assertEquals("STANDARD", TournamentModeConfirmation.observeImage(image).observedMode.name)
        assertFalse(StartupInputReadiness.recordStartupOnlineScreen(
            pid = 25948,
            hwnd = "captured-live-window",
            screen = "DECK_SELECTION",
            confidence = 94,
            evidence = "deck-selection-start-control-visual",
            expectedRunMode = "WILD",
            observedRunMode = "UNKNOWN",
            enabledStartButtonVisible = true,
            modalVisible = false,
            now = 50_000L,
        ), "stale Standard/UNKNOWN mode frame must not record Start readiness")

        var recoveryStarts = 0
        assertFalse(StartupInputAuthorizationPolicy.dispatchDeckSelectionRecoveryIfReady(
            startupScreenConfirmed = StartupInputReadiness.isDeckSelectionStartReady(
                25948, "captured-live-window", 50_001L,
            ),
        ) { recoveryStarts++ })
        assertEquals(0, recoveryStarts, "the upstream apply-to-start observable seam must stay uncalled")
        assertTrue(StartupDeckModeNavigationPolicy.requiresClassificationOnly(
            purpose = ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
            screenKind = "DECK_SELECTION",
            freshConfiguredModeStartProof = false,
        ))
        StartupInputReadiness.resetForTest()
    }

    @Test
    fun `idle client deck classifier cannot apply recovery even when start button and mode look valid`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/standard-deck-selection-live-20261029-063038.png",
        )))
        assertTrue(StartupDeckModeNavigationPolicy.isModeProofProbe(ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE))
        assertTrue(StartupDeckModeNavigationPolicy.requiresClassificationOnly(
            purpose = ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
            screenKind = "DECK_SELECTION",
            freshConfiguredModeStartProof = true,
        ), "IDLE_CLIENT_PROBE is capture-only by contract")
        assertEquals("DECK_SELECTION", UpstreamScreenStateRecovery.classifyStartupDeckSelectionVisualForTest("", image))
        assertTrue(ScreenWatchdog.hasEnabledDeckSelectionStartButton(image))
        assertEquals("STANDARD", TournamentModeConfirmation.observeImage(image).observedMode.name)

        var startDispatches = 0
        assertFalse(StartupInputAuthorizationPolicy.dispatchDeckSelectionRecoveryIfReady(
            startupScreenConfirmed = !StartupDeckModeNavigationPolicy.requiresClassificationOnly(
                purpose = ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
                screenKind = "DECK_SELECTION",
                freshConfiguredModeStartProof = true,
            ),
        ) { startDispatches++ })
        assertEquals(0, startDispatches, "repeated idle probes must not repeatedly request a queue input")
        StartupInputReadiness.resetForTest()
    }

    @Test
    fun `upstream idle probe and final apply callsites share the mode proof gate`() {
        val source = File("src/main/java/club/xiaojiawei/hsscript/status/UpstreamScreenStateRecovery.kt")
            .readText()
        assertTrue(source.contains(
            "val startupClientProbe = StartupDeckModeNavigationPolicy.isModeProofProbe(authorizedCapture.purpose)",
        ), "upstream startup/stale/idle captures must enter the common mode proof branch")
        assertTrue(source.contains("purpose = authorizedCapture.purpose"), "classification policy receives exact probe purpose")
        assertTrue(source.contains("StartupDeckModeNavigationPolicy.requiresClassificationOnly("),
            "deck classification cannot flow directly to apply on unknown mode")
        val startProofCheck = source.indexOf("if (!ActionDispatchGate.isFreshConfiguredDeckSelectionStartProofReady())")
        val recoveryApplied = source.indexOf("SCREEN_RECOVERY_APPLIED screen=DECK_SELECTION next=START_MATCHING")
        assertTrue(startProofCheck >= 0 && recoveryApplied > startProofCheck,
            "upstream apply must require fresh configured-mode proof before logging/scheduling Start")
    }

    @Test
    fun `fresh Standard title frame takes bounded selector return and cannot dispatch Wild Start`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/wild-config-standard-live-20261029-091917.png",
        )))
        assertEquals("DECK_SELECTION", UpstreamScreenStateRecovery.classifyStartupDeckSelectionVisualForTest("=", image))
        assertTrue(ScreenWatchdog.hasEnabledDeckSelectionStartButton(image))
        assertFalse(ScreenStateRecovery.wrongModeTooltipVisualForTest(image))
        val observed = TournamentModeConfirmation.observeImage(image)
        assertEquals("STANDARD", observed.observedMode.name)
        assertEquals("title-visual-template-standard", observed.evidence)

        val decision = StartupDeckModeNavigationPolicy.decide(
            purpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY.name,
            screenKind = "DECK_SELECTION",
            confidence = 100,
            evidence = "deck-selection-title",
            expectedRunMode = "WILD",
            observedRunMode = observed.observedMode.name,
            enabledStartButtonVisible = true,
            modalVisible = false,
            currentSessionReady = false,
            activeGame = false,
            activeMatchmaking = false,
            terminal = false,
            trustedCurrentWindow = true,
        )
        assertTrue(decision.allowed)
        var returnDispatches = 0
        var selectorReprobes = 0
        assertTrue(StartupDeckModeNavigationPolicy.dispatchWrongModeTooltipReturn(
            image = image,
            expectedMode = "WILD",
            decision = decision,
            dispatchReturn = { returnDispatches++; true },
            requestFreshSelectorProbe = { selectorReprobes++ },
        ), "confirmed Standard title should dispatch the bounded Back action without tooltip/OCR")
        assertEquals(1, returnDispatches)
        assertEquals(1, selectorReprobes)
        assertFalse(StartupInputReadiness.recordDeckSelectionStart(
            pid = 16992,
            hwnd = "live-standard-window",
            confidence = 100,
            evidence = "deck-selection-title",
            expectedRunMode = "WILD",
            observedRunMode = observed.observedMode.name,
            enabledStartButtonVisible = true,
            modalVisible = false,
        ))
        assertFalse(StartupInputAuthorizationPolicy.allows(
            action = "matchmaking.start",
            currentSessionReady = true,
            freshOnlineMenuReady = false,
            freshDeckSelectionStartReady = false,
            selectedModeStartPermitValid = false,
        ), "current-session readiness cannot bypass a confirmed opposite mode")
        assertFalse(StartupDeckModeNavigationPolicy.decide(
            purpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY.name,
            screenKind = "DECK_SELECTION",
            confidence = 100,
            evidence = "deck-selection-title",
            expectedRunMode = "WILD",
            observedRunMode = observed.observedMode.name,
            enabledStartButtonVisible = true,
            modalVisible = false,
            currentSessionReady = false,
            activeGame = true,
            activeMatchmaking = false,
            terminal = false,
            trustedCurrentWindow = true,
        ).allowed, "active gameplay remains fenced from mode navigation")
        StartupInputReadiness.resetForTest()
    }

    @Test
    fun `idle client probe dispatches only bounded wrong mode Back and schedules a fresh probe`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/wild-config-standard-live-20261029-091917.png",
        )))
        val observed = TournamentModeConfirmation.observeImage(image)
        assertEquals("STANDARD", observed.observedMode.name)
        val decision = StartupDeckModeNavigationPolicy.decide(
            purpose = ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE.name,
            screenKind = "DECK_SELECTION",
            confidence = 100,
            evidence = "deck-selection-title",
            expectedRunMode = "WILD",
            observedRunMode = observed.observedMode.name,
            enabledStartButtonVisible = true,
            modalVisible = false,
            currentSessionReady = false,
            activeGame = false,
            activeMatchmaking = false,
            terminal = false,
            trustedCurrentWindow = true,
        )
        assertTrue(decision.allowed, "the exact idle probe may navigate back to the mode selector")
        var backDispatches = 0
        var freshProbes = 0
        assertTrue(StartupDeckModeNavigationPolicy.dispatchWrongModeTooltipReturn(
            image = image,
            expectedMode = "WILD",
            decision = decision,
            dispatchReturn = { backDispatches++; true },
            requestFreshSelectorProbe = { freshProbes++ },
        ), "exercise the same bounded Back dispatch seam used by UpstreamScreenStateRecovery")
        assertEquals(1, backDispatches)
        assertEquals(1, freshProbes)
        assertFalse(StartupInputAuthorizationPolicy.allows(
            action = "matchmaking.start",
            currentSessionReady = false,
            freshOnlineMenuReady = false,
            freshDeckSelectionStartReady = false,
            selectedModeStartPermitValid = false,
        ), "IDLE_CLIENT_PROBE must never authorize Start from the opposite mode")
        val source = File("src/main/java/club/xiaojiawei/hsscript/status/UpstreamScreenStateRecovery.kt").readText()
        assertTrue(source.contains("purpose = authorizedCapture.purpose"),
            "the runtime callsite must pass IDLE_CLIENT_PROBE through this same purpose gate")
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
