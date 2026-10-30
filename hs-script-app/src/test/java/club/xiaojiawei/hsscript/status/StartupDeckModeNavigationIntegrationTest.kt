package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscript.consts.GameRationConst
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import club.xiaojiawei.hsscript.strategy.mode.TournamentModeStrategy
import java.io.File
import java.awt.Point
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class StartupDeckModeNavigationIntegrationTest {
    @Test
    fun `captured three choice selector authorizes only fresh configured Wild control proof`() {
        val selector = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/mode-selector-wild-standard-casual-live-20261029-190503.png",
        )))
        assertTrue(TournamentModeSelectorVisualPolicy.isVisible(selector))
        assertEquals(
            StartupDeckModeNavigationPolicy.MODE_SELECTOR_SCREEN,
            UpstreamScreenStateRecovery.classifyImageForTest("二=", selector),
            "the captured three-card chooser must classify despite garbage OCR and generic modal=false",
        )
        val ordinaryDeck = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/deck-selection-screen.png",
        )))
        assertFalse(TournamentModeSelectorVisualPolicy.isVisible(ordinaryDeck))
        listOf(
            "hub-mode-chooser-live-20261029-035630.png",
            "reconnect-failure-dialog-over-deck-selection.png",
            "matchmaking-reward-modal-20261021-200538.png",
        ).forEach { fixture ->
            val confuser = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
                "/offline-ocr/screen-recovery/$fixture",
            )))
            assertFalse(TournamentModeSelectorVisualPolicy.isVisible(confuser), "$fixture must not match selector visual")
            assertFalse(
                UpstreamScreenStateRecovery.classifyImageForTest("二=", confuser) ==
                    StartupDeckModeNavigationPolicy.MODE_SELECTOR_SCREEN,
                "$fixture must not classify as TOURNAMENT_MODE_SELECTOR",
            )
        }

        StartupInputReadiness.resetForTest()
        try {
            assertFalse(StartupInputReadiness.recordWildModeSelectorControl(
                pid = 25948,
                hwnd = "captured-game-window",
                confidence = 96,
                modalVisible = true,
                wildAndStandardOptionsVisible = true,
                structuralSelectorVisualVisible = false,
                now = 20_000,
            ), "generic option labels/modal flags without mismatch or structural proof must not authorize a click")
            assertFalse(StartupDeckModeNavigationPolicy.allowsDispatch(
                action = StartupDeckModeNavigationPolicy.SELECT_WILD_ACTION,
                proofFresh = StartupInputReadiness.isWildModeSelectorControlReady(
                    25948, "captured-game-window", 20_000,
                ),
                exactWindowVerified = true,
                currentSessionReady = false,
                activeGame = false,
                activeMatchmaking = false,
                terminal = false,
            ), "missing structural selector proof keeps the input gate closed")

            assertTrue(StartupInputReadiness.recordWildModeSelectorControl(
                pid = 25948,
                hwnd = "captured-game-window",
                confidence = 96,
                modalVisible = true,
                wildAndStandardOptionsVisible = true,
                structuralSelectorVisualVisible = true,
                now = 20_001,
            ), "exact visual selector proof can stand alone when the stale phase/title OCR is unknown")
            assertTrue(StartupDeckModeNavigationPolicy.allowsDispatch(
                action = StartupDeckModeNavigationPolicy.SELECT_WILD_ACTION,
                proofFresh = StartupInputReadiness.isWildModeSelectorControlReady(
                    25948, "captured-game-window", 20_002,
                ),
                exactWindowVerified = true,
                currentSessionReady = false,
                activeGame = false,
                activeMatchmaking = false,
                terminal = false,
            ), "only the short-lived exact-window selector proof permits the configured Wild option")
            assertFalse(StartupDeckModeNavigationPolicy.allowsDispatch(
                action = "matchmaking.start",
                proofFresh = StartupInputReadiness.isWildModeSelectorControlReady(
                    25948, "captured-game-window", 20_002,
                ),
                exactWindowVerified = true,
                currentSessionReady = false,
                activeGame = false,
                activeMatchmaking = false,
                terminal = false,
            ), "mode-selection proof cannot authorize matchmaking Start")
        } finally {
            StartupInputReadiness.resetForTest()
        }
    }

    @Test
    fun `exact Standard deck proof opens selector only and fresh modal evidence alone authorizes Wild control`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/standard-ranked-deck-selection-user-20261029.png",
        )))
        assertTrue(TournamentModeConfirmation.isConfirmedModeMismatch(image, "WILD"))
        val decision = StartupDeckModeNavigationPolicy.decide(
            purpose = ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE.name,
            screenKind = "DECK_SELECTION",
            confidence = 96,
            evidence = "deck-selection-start-control-visual",
            expectedRunMode = "WILD",
            observedRunMode = "STANDARD",
            enabledStartButtonVisible = true,
            modalVisible = false,
            currentSessionReady = false,
            activeGame = false,
            activeMatchmaking = false,
            terminal = false,
            trustedCurrentWindow = true,
        )
        assertTrue(decision.allowed)
        var crestClicks = 0
        var reprobes = 0
        val opened = StartupDeckModeNavigationPolicy.dispatchOpenSelectorForVerifiedDeckMismatch(
            image = image,
            expectedMode = "WILD",
            decision = decision,
            dispatchOpenSelector = { crestClicks++; true },
            requestFreshSelectorProbe = { reprobes++ },
        )
        assertTrue(opened)
        assertEquals(1, crestClicks, "only the exact observed mode crest is clicked in stage one")
        assertEquals(1, reprobes, "stage one must schedule a fresh capture before mode selection")

        assertFalse(StartupDeckModeNavigationPolicy.hasFreshWildSelectorEvidence(
            StartupDeckModeNavigationPolicy.MODE_SELECTOR_SCREEN,
            modalVisible = false,
            wildTargetLabelVisible = true,
            ocrText = "标准对战 狂野对战",
        ))
        assertFalse(StartupDeckModeNavigationPolicy.hasFreshWildSelectorEvidence(
            StartupDeckModeNavigationPolicy.MODE_SELECTOR_SCREEN,
            modalVisible = true,
            wildTargetLabelVisible = false,
            ocrText = "标准对战 狂野对战",
        ), "generic Wild text outside the configured target rectangle is insufficient")
        assertFalse(StartupDeckModeNavigationPolicy.hasFreshWildSelectorEvidence(
            StartupDeckModeNavigationPolicy.MODE_SELECTOR_SCREEN,
            modalVisible = true,
            wildTargetLabelVisible = false,
            ocrText = "标准对战",
        ))
        assertTrue(StartupDeckModeNavigationPolicy.hasFreshWildSelectorEvidence(
            StartupDeckModeNavigationPolicy.MODE_SELECTOR_SCREEN,
            modalVisible = true,
            wildTargetLabelVisible = true,
            ocrText = "标准对战 狂野对战",
        ))

        StartupInputReadiness.resetForTest()
        try {
            assertTrue(StartupInputReadiness.recordDeckSelectionModeNavigation(
                pid = 29428,
                hwnd = "exact-game-window",
                expectedRunMode = "WILD",
                observedRunMode = "STANDARD",
                confidence = 96,
                evidence = "deck-selection-start-control-visual",
                enabledStartButtonVisible = true,
                modalVisible = false,
                now = 10_000,
            ))
            assertFalse(
                StartupInputReadiness.isWildModeSelectorControlReady(29428, "exact-game-window", 10_001),
                "the deck mismatch proof alone must never authorize the Wild-option coordinate",
            )
            assertTrue(StartupInputReadiness.recordWildModeSelectorControl(
                pid = 29428,
                hwnd = "exact-game-window",
                confidence = 96,
                modalVisible = true,
                wildAndStandardOptionsVisible = true,
                now = 10_002,
            ))
            assertTrue(StartupInputReadiness.isWildModeSelectorControlReady(29428, "exact-game-window", 10_003))
            assertFalse(StartupInputReadiness.isWildModeSelectorControlReady(
                29428,
                "exact-game-window",
                10_002 + StartupInputReadiness.WILD_MODE_SELECTOR_CONTROL_PROOF_MAX_AGE_MS + 1,
            ), "the target-control proof expires quickly if dispatch is delayed")
            assertFalse(StartupInputReadiness.isWildModeSelectorControlReady(29429, "exact-game-window", 10_003))
            assertFalse(StartupInputReadiness.isWildModeSelectorControlReady(29428, "other-window", 10_003))
        } finally {
            StartupInputReadiness.resetForTest()
        }
    }

    @Test
    fun `legacy change mode rectangle targets the visible top right format crest`() {
        val gameRect = ScriptStatus.GAME_RECT
        val old = intArrayOf(gameRect.left, gameRect.top, gameRect.right, gameRect.bottom)
        try {
            gameRect.left = 0
            gameRect.top = 0
            gameRect.right = 1920
            gameRect.bottom = 1080
            val target = TournamentModeStrategy.CHANGE_MODE_RECT.getRelativeRect()
            val centerX = target.x + target.width / 2.0
            val centerY = target.y + target.height / 2.0
            assertTrue(centerX in 1390.0..1420.0, "mode crest center X=$centerX")
            assertTrue(centerY in 45.0..70.0, "mode crest center Y=$centerY")
            assertTrue(GameRationConst.GAME_WINDOW_CONTENT_WIDTH_HEIGHT_RATIO > 1.3f)
        } finally {
            gameRect.left = old[0]
            gameRect.top = old[1]
            gameRect.right = old[2]
            gameRect.bottom = old[3]
        }
    }

    @Test
    fun `user supplied 1919px Standard ranked screen is recognized but cannot dispatch unverified Wild selector coordinates`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/standard-ranked-deck-selection-user-20261029.png",
        )))
        assertEquals(1919, image.width)
        assertEquals(1079, image.height)
        assertTrue(TournamentModeConfirmation.classifyStandardTitleVisual(image),
            "the exact Standard title template must still classify when the capture omits the last row/column")
        assertEquals("STANDARD", TournamentModeConfirmation.observeImage(image).observedMode.name)
        assertEquals("DECK_SELECTION", UpstreamScreenStateRecovery.classifyStartupDeckSelectionVisualForTest("", image))
        assertTrue(ScreenWatchdog.hasEnabledDeckSelectionStartButton(image))

        val pid = 29_428
        val hwnd = "user-supplied-standard-deck-window"
        StartupInputReadiness.resetForTest()
        try {
            assertFalse(StartupInputReadiness.recordDeckSelectionStart(
                pid = pid,
                hwnd = hwnd,
                confidence = 96,
                evidence = "deck-selection-start-control-visual",
                expectedRunMode = "WILD",
                observedRunMode = "STANDARD",
                enabledStartButtonVisible = true,
                modalVisible = false,
            ), "Standard screen proof cannot authorize Wild Start")

            val mismatchDecision = StartupDeckModeNavigationPolicy.decide(
                purpose = ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE.name,
                screenKind = "DECK_SELECTION",
                confidence = 96,
                evidence = "deck-selection-start-control-visual",
                expectedRunMode = "WILD",
                observedRunMode = "STANDARD",
                enabledStartButtonVisible = true,
                modalVisible = false,
                currentSessionReady = false,
                activeGame = false,
                activeMatchmaking = false,
                terminal = false,
                trustedCurrentWindow = true,
            )
            assertTrue(mismatchDecision.allowed, "fresh exact mismatch is recognized for classification/recovery policy")
            var selectorClicks = 0
            var modeReprobes = 0
            assertTrue(StartupDeckModeNavigationPolicy.dispatchOpenSelectorForVerifiedDeckMismatch(
                image = image,
                expectedMode = "WILD",
                decision = mismatchDecision,
                dispatchOpenSelector = { selectorClicks++; true },
                requestFreshSelectorProbe = { modeReprobes++ },
            ), "the exact Standard deck crest may open the selector, then require a fresh selector capture")
            assertEquals(1, selectorClicks)
            assertEquals(1, modeReprobes)

            val confirmedWild = TournamentModeConfirmation.evaluate(
                expectedMode = RunModeEnum.WILD,
                observation = TournamentModeObservation(
                    ObservedTournamentMode.WILD,
                    "狂野对战",
                    "fresh-mode-selector-postcheck",
                ),
                strategyId = "configured-wild",
                strategyName = "configured-wild",
                deckSlot = 2,
            )
            assertEquals(TournamentModeConfirmationState.CONFIRMED, confirmedWild.state)
            assertTrue(StartupInputReadiness.recordDeckSelectionStart(
                pid = pid,
                hwnd = hwnd,
                confidence = 96,
                evidence = "deck-selection-start-control-visual",
                expectedRunMode = "WILD",
                observedRunMode = "WILD",
                enabledStartButtonVisible = true,
                modalVisible = false,
            ), "only a fresh subsequent capture confirming WILD can mint Start proof")
            assertTrue(StartupInputAuthorizationPolicy.allows(
                action = "matchmaking.start",
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                freshDeckSelectionStartReady = StartupInputReadiness.isDeckSelectionStartReady(pid, hwnd),
            ))
        } finally {
            StartupInputReadiness.resetForTest()
        }
    }

    @Test
    fun `fresh selector observation renews exactly one wrong-mode Return attempt`() {
        val attempts = StartupDeckModeNavigationAttemptGate()
        val rejectedDispatch = attempts.tryBegin(9704, "live-v709")
        assertTrue(rejectedDispatch.allowed)
        assertFalse(attempts.tryBegin(9704, "live-v709").allowed, "a concurrent probe cannot reserve a second dispatch")
        assertFalse(attempts.resolve(9704, "live-v709", inputAccepted = false))
        val acceptedDispatch = attempts.tryBegin(9704, "live-v709")
        assertTrue(acceptedDispatch.allowed, "a rejected final dispatch must leave the recovery attempt available")
        assertTrue(attempts.resolve(9704, "live-v709", inputAccepted = true))
        assertFalse(attempts.tryBegin(9704, "live-v709").allowed, "only an accepted input consumes the single attempt")
        // Runtime calls reset only after the exact HUB chooser proof is accepted.
        attempts.reset()
        assertTrue(attempts.tryBegin(9704, "live-v709").allowed)
        assertTrue(attempts.resolve(9704, "live-v709", inputAccepted = true))
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
        StartupInputReadiness.recordSelectedTournamentMode(9042, "rank-nine-standard", "WILD", 50_000L)
        assertFalse(StartupInputReadiness.recordStartupOnlineScreen(
            pid = 9042,
            hwnd = "rank-nine-standard",
            screen = "DECK_SELECTION",
            confidence = 94,
            evidence = "deck-selection-start-control-visual",
            expectedRunMode = "WILD",
            observedRunMode = observed.observedMode.name,
            enabledStartButtonVisible = true,
            modalVisible = false,
            now = 50_001L,
        ))
        assertFalse(StartupInputReadiness.hasSelectedTournamentModeStartPermit(
            9042, "rank-nine-standard", "WILD", 50_002L,
        ), "fresh opposite-mode screen revokes any prior selected-mode permit")

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
    fun `fresh exact deck selection with visually confirmed Wild title dispatches without mode OCR dependency`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/deck-selection-screen.png",
        )))
        assertEquals("DECK_SELECTION", UpstreamScreenStateRecovery.classifyStartupDeckSelectionVisualForTest("", image))
        assertTrue(ScreenWatchdog.hasEnabledDeckSelectionStartButton(image))
        val observedMode = TournamentModeConfirmation.observeImage(image).observedMode.name
        assertEquals("WILD", observedMode, "the exact title template must override unavailable title OCR")

        try {
            assertTrue(StartupInputReadiness.recordStartupOnlineScreen(
                pid = 25948,
                hwnd = "captured-live-window",
                screen = "DECK_SELECTION",
                confidence = 94,
                evidence = "deck-selection-start-control-visual",
                expectedRunMode = "WILD",
                observedRunMode = observedMode,
                enabledStartButtonVisible = true,
                modalVisible = false,
                now = 50_001L,
            ), "fresh visible Wild title plus exact Start control may mint the narrow Start proof")
            assertTrue(StartupInputAuthorizationPolicy.allows(
                action = "matchmaking.start",
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                freshDeckSelectionStartReady = StartupInputReadiness.isDeckSelectionStartReady(
                    25948, "captured-live-window", 50_002L,
                ),
                selectedModeStartPermitValid = StartupInputReadiness.hasSelectedTournamentModeStartPermit(
                    25948, "captured-live-window", "WILD", 50_002L,
                ),
            ), "both proofs authorize only the named matchmaking start action")

            val freshStartProof = StartupInputReadiness.isDeckSelectionStartReady(
                25948, "captured-live-window", 50_003L,
            )
            val selectedModeProof = StartupInputReadiness.hasSelectedTournamentModeStartPermit(
                25948, "captured-live-window", "WILD", 50_003L,
            )
            MulliganRankDispatchBarrier.beginCurrentGame()
            assertEquals(MulliganRankDispatchBarrier.State.PENDING, MulliganRankDispatchBarrier.currentState())
            assertTrue(ActionDispatchGate.allowForState(
                action = "matchmaking.start",
                paused = false,
                working = true,
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                freshDeckSelectionStartReady = freshStartProof,
                selectedModeStartPermitValid = selectedModeProof,
                rankBarrierState = MulliganRankDispatchBarrier.currentState(),
            ), "the production last dispatch gate authorizes only matchmaking.start from exact screen + selected mode proofs")
            assertFalse(ActionDispatchGate.allowForState(
                action = "strategy.card.play",
                paused = false,
                working = true,
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                freshDeckSelectionStartReady = freshStartProof,
                selectedModeStartPermitValid = selectedModeProof,
            ), "deck-selection proof cannot authorize gameplay actions")
            var queueStartDispatches = 0
            assertTrue(TournamentModeStrategy.dispatchMatchmakingStartControlIfAuthorized(
                isAuthorized = {
                    ActionDispatchGate.allowForState(
                        action = "matchmaking.start",
                        paused = false,
                        working = true,
                        currentSessionReady = false,
                        freshOnlineMenuReady = false,
                        freshDeckSelectionStartReady = freshStartProof,
                        selectedModeStartPermitValid = selectedModeProof,
                        rankBarrierState = MulliganRankDispatchBarrier.currentState(),
                    )
                },
                dispatchStartControl = { queueStartDispatches++; true },
            ), "the same immediately-before-click gate used by production must pass")
            assertEquals(1, queueStartDispatches, "authorized proof reaches the physical Start-control callback exactly once")
        } finally {
            MulliganRankDispatchBarrier.resetForTest()
            StartupInputReadiness.resetForTest()
        }
    }

    @Test
    fun `unknown OCR on exact selected Wild deck screen reaches the real matchmaking dispatch gate`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/deck-selection-screen.png",
        )))
        assertEquals("DECK_SELECTION", UpstreamScreenStateRecovery.classifyStartupDeckSelectionVisualForTest("二=", image))
        assertTrue(ScreenWatchdog.hasEnabledDeckSelectionStartButton(image))
        assertEquals("WILD", TournamentModeConfirmation.observeImage(image).observedMode.name)
        val upstreamSource = File("src/main/java/club/xiaojiawei/hsscript/status/UpstreamScreenStateRecovery.kt")
            .readText()
        assertTrue(upstreamSource.contains("detection?.kind != ScreenKind.DECK_SELECTION"),
            "startup invalidation must preserve the independent mode permit until the exact deck/Start proof is evaluated")

        val pid = 29_428
        val hwnd = "live-wild-unknown-ocr-window"
        StartupInputReadiness.resetForTest()
        MulliganRankDispatchBarrier.resetForTest()
        try {
            StartupInputReadiness.recordSelectedTournamentMode(pid, hwnd, "WILD", now = 60_000L)
            assertTrue(StartupInputReadiness.recordStartupOnlineScreen(
                pid = pid,
                hwnd = hwnd,
                screen = "DECK_SELECTION",
                confidence = 96,
                evidence = "deck-selection-start-control-visual",
                expectedRunMode = "WILD",
                observedRunMode = "UNKNOWN", // Live OCR output was `二=`.
                enabledStartButtonVisible = true,
                modalVisible = false,
                now = 60_001L,
            ), "same-window selected mode plus fresh exact deck/Start capture should mint readiness")

            MulliganRankDispatchBarrier.beginCurrentGame()
            assertEquals(MulliganRankDispatchBarrier.State.PENDING, MulliganRankDispatchBarrier.currentState())
            var startDispatches = 0
            val accepted = TournamentModeStrategy.dispatchMatchmakingStartControlIfAuthorized(
                isAuthorized = {
                    ActionDispatchGate.allowForState(
                        action = "matchmaking.start",
                        paused = false,
                        working = true,
                        currentSessionReady = false,
                        freshOnlineMenuReady = false,
                        freshDeckSelectionStartReady = StartupInputReadiness.isDeckSelectionStartReady(
                            pid, hwnd, 60_002L,
                        ),
                        selectedModeStartPermitValid = StartupInputReadiness.hasSelectedTournamentModeStartPermit(
                            pid, hwnd, "WILD", 60_002L,
                        ),
                        rankBarrierState = MulliganRankDispatchBarrier.currentState(),
                    )
                },
                dispatchStartControl = { startDispatches++; true },
            )
            assertTrue(accepted, "the final ActionDispatchGate must authorize the live Start action")
            assertEquals(1, startDispatches, "the production pre-click seam must dispatch the Start control exactly once")
            assertEquals(
                MulliganRankDispatchBarrier.State.PENDING,
                MulliganRankDispatchBarrier.currentState(),
                "pre-match queue entry remains rank-independent; the surrender decision remains post-mulligan",
            )
        } finally {
            MulliganRankDispatchBarrier.resetForTest()
            StartupInputReadiness.resetForTest()
        }
    }

    @Test
    fun `exact live Standard deck screenshot with rank nine cannot dispatch configured Wild queue start`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/tournament-wild-deck-standard-mode-20261029.png",
        )))
        assertTrue(UpstreamScreenStateRecovery.classifyStartupDeckSelectionVisualForTest("二=", image) == "DECK_SELECTION")
        assertTrue(ScreenWatchdog.hasEnabledDeckSelectionStartButton(image))
        assertEquals("STANDARD", TournamentModeConfirmation.observeImage(image).observedMode.name)

        StartupInputReadiness.resetForTest()
        MulliganRankDispatchBarrier.resetForTest()
        try {
            // The live OCR was UNKNOWN, but the exact captured title template proves STANDARD.
            // A stale selected-Wild permit must be revoked by this fresh contradictory screen.
            StartupInputReadiness.recordSelectedTournamentMode(25_948, "live-standard-hwnd", "WILD", 50_000L)
            assertFalse(StartupInputReadiness.recordStartupOnlineScreen(
                pid = 25_948,
                hwnd = "live-standard-hwnd",
                screen = "DECK_SELECTION",
                confidence = 96,
                evidence = "deck-selection-start-control-visual",
                expectedRunMode = "WILD",
                observedRunMode = "STANDARD",
                enabledStartButtonVisible = true,
                modalVisible = false,
                now = 50_001L,
            ))

            MulliganRankDispatchBarrier.beginCurrentGame()
            assertEquals(MulliganRankDispatchBarrier.State.PENDING, MulliganRankDispatchBarrier.currentState())
            var queueStartDispatches = 0
            val startAllowed = ActionDispatchGate.allowForState(
                action = "matchmaking.start",
                paused = false,
                working = true,
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                freshDeckSelectionStartReady = StartupInputReadiness.isDeckSelectionStartReady(
                    25_948, "live-standard-hwnd", 50_002L,
                ),
                selectedModeStartPermitValid = StartupInputReadiness.hasSelectedTournamentModeStartPermit(
                    25_948, "live-standard-hwnd", "WILD", 50_002L,
                ),
                rankBarrierState = MulliganRankDispatchBarrier.currentState(),
            )
            assertFalse(startAllowed, "fresh Standard identity must block Wild even though pre-match rank does not")
            assertFalse(TournamentModeStrategy.dispatchMatchmakingStartControlIfAuthorized(
                isAuthorized = { startAllowed },
                dispatchStartControl = { queueStartDispatches++; true },
            ))
            assertEquals(0, queueStartDispatches, "a Start intent must not become a queue-control dispatch")
        } finally {
            MulliganRankDispatchBarrier.resetForTest()
            StartupInputReadiness.resetForTest()
        }
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
    fun `fresh Standard title frame authorizes only configured Wild selector correction and cannot dispatch Start`() {
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
        val now = 100_000L
        assertTrue(StartupInputReadiness.recordDeckSelectionModeNavigation(
            pid = 16992,
            hwnd = "live-standard-window",
            expectedRunMode = "WILD",
            observedRunMode = observed.observedMode.name,
            confidence = 100,
            evidence = "deck-selection-title",
            enabledStartButtonVisible = true,
            modalVisible = false,
            now = now,
        ))
        var selectorOpenDispatches = 0
        var modeReprobes = 0
        assertTrue(StartupDeckModeNavigationPolicy.dispatchOpenSelectorForVerifiedDeckMismatch(
            image = image,
            expectedMode = "WILD",
            decision = decision,
            dispatchOpenSelector = { selectorOpenDispatches++; true },
            requestFreshSelectorProbe = { modeReprobes++ },
        ), "fresh exact opposite-mode deck proof may open the format menu, but cannot select from this frame")
        assertEquals(1, selectorOpenDispatches)
        assertEquals(1, modeReprobes)
        assertTrue(StartupDeckModeNavigationPolicy.allowsDispatch(
            action = StartupDeckModeNavigationPolicy.OPEN_SELECTOR_ACTION,
            proofFresh = StartupInputReadiness.isDeckSelectionModeNavigationReady(
                16992, "live-standard-window", now + 1, expectedMode = "WILD",
            ),
            exactWindowVerified = true,
            currentSessionReady = false,
            activeGame = false,
            activeMatchmaking = false,
            terminal = false,
        ))
        assertFalse(StartupDeckModeNavigationPolicy.allowsDispatch(
            action = StartupDeckModeNavigationPolicy.SELECT_WILD_ACTION,
            proofFresh = StartupInputReadiness.isWildModeSelectorControlReady(
                16992, "live-standard-window", now + 1,
            ),
            exactWindowVerified = true,
            currentSessionReady = false,
            activeGame = false,
            activeMatchmaking = false,
            terminal = false,
        ), "deck mismatch proof alone cannot dispatch the Wild selector coordinate")
        assertTrue(StartupInputReadiness.recordWildModeSelectorControl(
            pid = 16992,
            hwnd = "live-standard-window",
            confidence = 96,
            modalVisible = true,
            wildAndStandardOptionsVisible = true,
            now = now + 2,
        ))
        assertTrue(StartupDeckModeNavigationPolicy.allowsDispatch(
            action = StartupDeckModeNavigationPolicy.SELECT_WILD_ACTION,
            proofFresh = StartupInputReadiness.isWildModeSelectorControlReady(
                16992, "live-standard-window", now + 3,
            ),
            exactWindowVerified = true,
            currentSessionReady = false,
            activeGame = false,
            activeMatchmaking = false,
            terminal = false,
        ), "only the fresh selector capture permits the Wild coordinate")
        listOf(
            StartupDeckModeNavigationPolicy.OPEN_SELECTOR_ACTION,
            StartupDeckModeNavigationPolicy.SELECT_WILD_ACTION,
        ).forEach { action ->
            assertTrue(StartupInputAuthorizationPolicy.allows(
                action = action,
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                startupDeckSelectionModeNavigationReady = true,
            ), "only the named configured-mode correction action uses the exact mismatch proof: $action")
        }
        assertFalse(StartupDeckModeNavigationPolicy.allowsDispatch(
            action = "matchmaking.start",
            proofFresh = StartupInputReadiness.isDeckSelectionModeNavigationReady(
                16992, "live-standard-window", now + 1, expectedMode = "WILD",
            ),
            exactWindowVerified = true,
            currentSessionReady = false,
            activeGame = false,
            activeMatchmaking = false,
            terminal = false,
        ), "the mode-correction proof never authorizes Start")
        assertFalse(StartupInputReadiness.isDeckSelectionModeNavigationReady(
            16992, "live-standard-window", now + 1, expectedMode = "STANDARD",
        ), "the mismatch proof is bound to the configured Wild target")
        assertFalse(StartupDeckModeNavigationPolicy.allowsDispatch(
            action = StartupDeckModeNavigationPolicy.SELECT_WILD_ACTION,
            proofFresh = true, exactWindowVerified = true, currentSessionReady = false,
            activeGame = false, activeMatchmaking = true, terminal = false,
        ), "a current queue observation fences format correction")
        assertFalse(StartupDeckModeNavigationPolicy.allowsDispatch(
            action = StartupDeckModeNavigationPolicy.SELECT_WILD_ACTION,
            proofFresh = true, exactWindowVerified = true, currentSessionReady = false,
            activeGame = true, activeMatchmaking = false, terminal = false,
        ), "an active game fences format correction")
        assertFalse(StartupDeckModeNavigationPolicy.dispatchOpenSelectorForVerifiedDeckMismatch(
            image = image,
            expectedMode = "WILD",
            decision = decision.copy(allowed = false),
            dispatchOpenSelector = { error("blocked correction must not dispatch") },
            requestFreshSelectorProbe = {},
        ))
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
    fun `stale exact Standard frame reaches bounded top left ladder without Start`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/wild-config-standard-live-20261029-103217.png",
        )))
        val screen = UpstreamScreenStateRecovery.classifyStartupDeckSelectionVisualForTest("", image)
        val expected = "WILD"
        val observed = TournamentModeConfirmation.observeImage(image).observedMode.name
        assertEquals("DECK_SELECTION", screen)
        assertEquals("STANDARD", observed)
        val startProof = StartupInputReadiness.recordStartupOnlineScreen(
            pid = 20352,
            hwnd = "v713-standard-selector",
            screen = "DECK_SELECTION",
            confidence = 94,
            evidence = "deck-selection-start-control-visual",
            expectedRunMode = expected,
            observedRunMode = observed,
            enabledStartButtonVisible = true,
            modalVisible = false,
            now = 100_000L,
        )
        assertFalse(startProof, "the v713 Standard frame must not produce Wild Start proof")
        assertFalse(StartupInputReadiness.isDeckSelectionStartReady(20352, "v713-standard-selector", 100_001L))

        val stableFingerprint = StuckScreenUnblockFrameIdentity.create(
            processId = 20352,
            windowId = "v713-standard-selector",
            perceptualFingerprint = "exact-standard-frame",
            exactReconnectDialog = false,
        ).fingerprint
        val policy = StuckScreenUnblockPolicy()
        val decision = policy.observe(StuckScreenUnblockPolicy.Evidence(
            nowMs = 151_210L,
            stableForMs = 151_210L,
            fingerprint = stableFingerprint,
            captureFresh = true,
            exactWindowVerified = true,
            eligibleIdleProbe = true,
            recognizedSafeBlocker = true,
            currentSessionReady = false,
            activeGame = false,
            activeQueue = false,
            terminal = false,
            paused = false,
            modeMismatchDeck = true,
        ))
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT, decision.action)
        val points = mutableListOf<Point>()
        assertTrue(StuckScreenUnblockDispatch.dispatchTopLeftBatch(
            batchInFlight = AtomicBoolean(false),
            action = decision.action,
            firstClick = decision.clickNumber,
            freshCaptureAuthorized = true,
            point = Point(0, 0),
            expectedFingerprint = stableFingerprint,
            completionTimeMs = { 151_300L },
            dispatchClick = { point, _ -> points += point; true },
            completeBatch = policy::completeTopLeftClickBatch,
            requestFreshRecheck = {},
        ))
        assertEquals(listOf(Point(0, 0), Point(0, 0), Point(0, 0)), points)
        assertEquals(StuckScreenUnblockPolicy.Action.REPROBE,
            policy.observe(StuckScreenUnblockPolicy.Evidence(
                nowMs = 211_299L, stableForMs = 211_299L, fingerprint = stableFingerprint,
                captureFresh = true, exactWindowVerified = true, eligibleIdleProbe = true,
                recognizedSafeBlocker = true, currentSessionReady = false, activeGame = false,
                activeQueue = false, terminal = false, paused = false, modeMismatchDeck = true,
            )).action)
        assertEquals(StuckScreenUnblockPolicy.Action.RESTART_CLIENT,
            policy.observe(StuckScreenUnblockPolicy.Evidence(
                nowMs = 211_300L, stableForMs = 211_300L, fingerprint = stableFingerprint,
                captureFresh = true, exactWindowVerified = true, eligibleIdleProbe = true,
                recognizedSafeBlocker = true, currentSessionReady = false, activeGame = false,
                activeQueue = false, terminal = false, paused = false, modeMismatchDeck = true,
            )).action)
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
    fun `recovery source opens selector then requires fresh selector proof before Wild and Start`() {
        val upstream = File("src/main/java/club/xiaojiawei/hsscript/status/UpstreamScreenStateRecovery.kt").readText()
        assertTrue(upstream.contains("dispatchOpenSelectorForVerifiedDeckMismatch("))
        assertTrue(upstream.contains("openModeSelectorAfterVerifiedDeckMismatch()"))
        assertTrue(upstream.contains("next=FRESH_SELECTOR_CAPTURE"))
        assertTrue(upstream.contains("hasFreshWildSelectorEvidence("))
        assertTrue(upstream.contains("recordWildModeSelectorControl("))
        assertTrue(upstream.contains("selectWildFromVerifiedModeSelector()"))
        assertTrue(upstream.contains("FRESH_MODE_REPROBE_NO_START"))
        val strategy = File("src/main/java/club/xiaojiawei/hsscript/strategy/mode/TournamentModeStrategy.kt").readText()
        assertTrue(strategy.contains("CHANGE_MODE_RECT.lClick(isCancel = false"))
        assertTrue(strategy.contains("WILD_MODE_RECT.lClick("))
        assertTrue(strategy.contains("dispatchAction = StartupDeckModeNavigationPolicy.SELECT_WILD_ACTION"))
        val wildSelection = strategy.substringAfter("internal fun selectWildFromVerifiedModeSelector()")
            .substringBefore("fun startMatching()")
        assertTrue(wildSelection.contains("selectedModeProofRecorded") &&
            wildSelection.contains("recordSelectedTournamentMode(") &&
            wildSelection.contains("RunModeEnum.WILD.name"),
            "only a dispatched selection from the exact verified selector may mint the short-lived mode identity " +
                "used when the later deck title OCR is UNKNOWN")
        assertTrue(strategy.indexOf("selectWildFromVerifiedModeSelector()") <
            strategy.indexOf("fun startMatching()"))
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
