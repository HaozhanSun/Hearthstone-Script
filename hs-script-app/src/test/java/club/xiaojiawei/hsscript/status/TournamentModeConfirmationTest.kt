package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.HashSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TournamentModeConfirmationTest {
    @Test
    fun `configured format selector clicks do not send a cancel right click first`() {
        val source = Files.readString(moduleRoot().resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "strategy", "mode", "TournamentModeStrategy.kt",
        )))
        listOf(
            "CHANGE_MODE_RECT.lClick(isCancel = false)",
            "CLASSIC_MODE_RECT.lClick(isCancel = false)",
            "STANDARD_MODE_RECT.lClick(isCancel = false)",
            "WILD_MODE_RECT.lClick(isCancel = false)",
            "CASUAL_MODE_RECT.lClick(isCancel = false)",
        ).forEach { requiredCall ->
            assertTrue(source.contains(requiredCall), "mode selection must preserve the open selector: $requiredCall")
        }
    }

    @Test
    fun `wild target pauses before deck selection when observed title is standard`() {
        val result = TournamentModeConfirmation.evaluate(
            expectedMode = RunModeEnum.WILD,
            observation = observation("标准对战 选择套牌"),
            strategyId = PIRATE_WARRIOR,
            strategyName = "海盗战 V2.5",
            deckSlot = 2,
        )

        assertEquals(TournamentModeConfirmationState.MISMATCH, result.state)
        assertFalse(result.confirmed)
        assertEquals(ObservedTournamentMode.STANDARD, result.observedMode)
        assertEquals("observed-different-mode", result.reason)
        appendEvidence(
            "TOURNAMENT_MODE_CONFIRMATION expectedMode=WILD observedMode=${result.observedMode} " +
                "strategy=${result.strategyName} deckSlot=${result.deckSlot} action=BLOCK_DECK_SELECTION_NO_PAUSE",
        )
    }

    @Test
    fun `wild target continues when observed title is wild`() {
        val result = TournamentModeConfirmation.evaluate(
            expectedMode = RunModeEnum.WILD,
            observation = observation("选择套牌 狂野对战"),
            strategyId = PIRATE_WARRIOR,
            strategyName = "海盗战 V2.5",
            deckSlot = 2,
        )

        assertEquals(TournamentModeConfirmationState.CONFIRMED, result.state)
        assertTrue(result.confirmed)
        assertEquals(ObservedTournamentMode.WILD, result.observedMode)
    }

    @Test
    fun `mode selector still open is treated as switching not as standard or wild`() {
        val result = TournamentModeConfirmation.evaluate(
            expectedMode = RunModeEnum.WILD,
            observation = observation("选择模式 标准 狂野 休闲"),
            strategyId = PIRATE_WARRIOR,
            strategyName = "海盗战 V2.5",
            deckSlot = 2,
        )

        assertEquals(TournamentModeConfirmationState.SWITCHING, result.state)
        assertFalse(result.confirmed)
        assertEquals(ObservedTournamentMode.SWITCHING, result.observedMode)
    }

    @Test
    fun `unrecognized or conflicting text is not accepted as a correct mode`() {
        val unknown = TournamentModeConfirmation.evaluate(
            expectedMode = RunModeEnum.WILD,
            observation = observation("选择套牌 对战"),
            strategyId = PIRATE_WARRIOR,
            strategyName = "海盗战 V2.5",
            deckSlot = 2,
        )
        val conflicting = TournamentModeConfirmation.evaluate(
            expectedMode = RunModeEnum.WILD,
            observation = observation("标准对战 狂野对战"),
            strategyId = PIRATE_WARRIOR,
            strategyName = "海盗战 V2.5",
            deckSlot = 2,
        )

        assertEquals(TournamentModeConfirmationState.UNRECOGNIZED, unknown.state)
        assertEquals(ObservedTournamentMode.UNKNOWN, conflicting.observedMode)
        assertFalse(unknown.confirmed)
        assertFalse(conflicting.confirmed)
    }

    @Test
    fun `strategy default deck slot is carried into unsafe mode evidence`() {
        val choice = StrategyDefaultDeckSlotBindings.chooseDeckSlots(
            rule = null,
            strategyId = PIRATE_WARRIOR,
            globalDeckSlots = listOf(1),
            bindings = StrategyDefaultDeckSlotBindings.builtInDefaults,
        )
        val result = TournamentModeConfirmation.evaluate(
            expectedMode = RunModeEnum.WILD,
            observation = observation("标准对战"),
            strategyId = PIRATE_WARRIOR,
            strategyName = "海盗战 V2.5",
            deckSlot = choice.deckSlots.single(),
        )

        assertEquals("strategy-default-deck-slot", choice.assignmentReason)
        assertEquals(2, result.deckSlot)
        assertEquals(TournamentModeConfirmationState.MISMATCH, result.state)
    }

    @Test
    fun `tournament entry code gates deck selection behind mode confirmation`() {
        val moduleRoot = moduleRoot()
        val tournament = Files.readString(moduleRoot.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "strategy", "mode", "TournamentModeStrategy.kt",
        )))
        val confirmation = Files.readString(moduleRoot.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "status", "TournamentModeConfirmation.kt",
        )))

        assertTrue(tournament.contains("confirmBeforeDeckSelection"))
        assertTrue(tournament.indexOf("confirmBeforeDeckSelection") < tournament.indexOf("selectDeck(deckStrategy, expectedDeckSlot)"))
        assertTrue(tournament.contains("DECK_SLOT_RESOLVED"))
        assertTrue(tournament.contains("recoverDeckSelectionAndStart"))
        assertTrue(tournament.contains("GameUtil.lClickDeckSlot(deckSlot)"))
        assertFalse(tournament.contains("GameUtil.lClickDeckPos(deckSlot)"))
        assertTrue(tournament.contains("MATCHMAKING_INPUT_REQUEST mode=normal"))
        assertTrue(tournament.contains("MATCHMAKING_INPUT_RESULT accepted=\$accepted"))
        assertTrue(tournament.contains("MouseUtil.leftButtonClick("))
        assertTrue(tournament.contains("dispatchActionOverride = \"matchmaking.start\""))
        assertFalse(tournament.contains("leftButtonClickForRecovery(rect.getCenterClickPos())"))
        assertTrue(tournament.contains("MATCHMAKING_TIMEOUT_ERROR_DIALOG_CLICK_SKIPPED reason=exact-dialog-watchdog-only"))
        assertTrue(tournament.contains("scheduleMatchmakingDialogRecovery(traceId)"))
        assertTrue(confirmation.contains("title-roi-ocr"))
        assertTrue(confirmation.contains("TOURNAMENT_MODE_CONFIRMATION_OCR provider=LEGACY_FAST"))
        assertFalse(confirmation.contains("tournament-mode-fullscreen"))
        assertTrue(confirmation.contains("image.width * 0.30"))
        assertTrue(confirmation.contains("image.width * 0.40"))
        assertTrue(confirmation.contains("image.height * 0.09"))
        assertTrue(confirmation.contains("setPageSegMode(7)"))
        assertTrue(confirmation.contains("TOURNAMENT_MODE_CONFIRMATION_FAILED"))
        assertTrue(confirmation.contains("expectedMode="))
        assertTrue(confirmation.contains("observedMode="))
        assertTrue(confirmation.contains("TOURNAMENT_MODE_CONFIRMATION_ABORTED"))
    }

    @Test
    fun `slow mode observation cannot pause after gameplay starts`() {
        var inWar = false
        var observations = 0
        val result = TournamentModeConfirmation.confirmBeforeDeckSelection(
            expectedMode = RunModeEnum.WILD,
            deckStrategy = object : DeckStrategy() {
                override fun id(): String = PIRATE_WARRIOR
                override fun name(): String = "海盗战 V2.5"
                override fun deckCode(): String = ""
                override fun getRunMode(): Array<RunModeEnum> = arrayOf(RunModeEnum.WILD)
                override fun executeChangeCard(cards: HashSet<Card>) = Unit
                override fun executeOutCard() = Unit
                override fun executeDiscoverChooseCard(vararg cards: Card): Int = 0
            },
            deckSlot = 2,
            attempts = 3,
            observer = {
                observations++
                inWar = true
                observation("未知游戏画面")
            },
            sleeper = {},
            shouldContinue = { !inWar },
        )

        assertFalse(result)
        assertEquals(1, observations)
    }

    @Test
    fun `three unknown mode observations block deck selection and leave pause to the operator`() {
        var observations = 0
        try {
            PauseStatus.setAutomaticPause(false)
            val result = TournamentModeConfirmation.confirmBeforeDeckSelection(
                expectedMode = RunModeEnum.WILD,
                deckStrategy = object : DeckStrategy() {
                    override fun id(): String = PIRATE_WARRIOR
                    override fun name(): String = "海盗战 V2.5"
                    override fun deckCode(): String = ""
                    override fun getRunMode(): Array<RunModeEnum> = arrayOf(RunModeEnum.WILD)
                    override fun executeChangeCard(cards: HashSet<Card>) = Unit
                    override fun executeOutCard() = Unit
                    override fun executeDiscoverChooseCard(vararg cards: Card): Int = 0
                },
                deckSlot = 3,
                attempts = 3,
                observer = {
                    observations++
                    observation("RBEH")
                },
                sleeper = {},
                shouldContinue = { true },
            )

            assertFalse(result)
            assertEquals(3, observations)
            assertFalse(PauseStatus.isAutomaticPause)
            val source = Files.readString(moduleRoot().resolve(Path.of(
                "src", "main", "java", "club", "xiaojiawei", "hsscript", "status", "TournamentModeConfirmation.kt",
            )))
            assertFalse(source.contains("CONTINUE_DETERMINISTIC_MODE"),
                "the Sep 19 UNKNOWN fallback must not return to the startup mode path")
        } finally {
            PauseStatus.setAutomaticPause(false)
        }
    }

    @Test
    fun `live standard deck screen with unknown expected Wild mode cannot authorize matchmaking`() {
        val image = javax.imageio.ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/tournament-standard-deck-selection-20261029.png",
        )))
        assertTrue(ScreenWatchdog.hasEnabledDeckSelectionStartButton(image), "saved frame contains an enabled Start control")
        val observed = TournamentModeConfirmation.observeImage(image)
        val result = TournamentModeConfirmation.evaluate(
            expectedMode = RunModeEnum.WILD,
            observation = observed,
            strategyId = "test-wild-deck",
            strategyName = "test Wild deck",
            deckSlot = 1,
        )
        assertTrue(
            result.state == TournamentModeConfirmationState.MISMATCH ||
                result.state == TournamentModeConfirmationState.UNRECOGNIZED,
            "the saved live frame must never confirm WILD; observed=${result.observedMode} ocr=${result.ocrText}",
        )
        try {
            assertFalse(StartupInputReadiness.recordDeckSelectionStart(
                pid = 25948,
                hwnd = "captured-game-window",
                confidence = 100,
                evidence = "deck-selection-title-roi",
                expectedRunMode = RunModeEnum.WILD.name,
                observedRunMode = result.observedMode.name,
                enabledStartButtonVisible = true,
                modalVisible = false,
                now = 10_000L,
            ))
            assertFalse(
                ActionDispatchGate.allowForState(
                    action = "matchmaking.start",
                    paused = false,
                    working = true,
                    currentSessionReady = false,
                    freshDeckSelectionStartReady = StartupInputReadiness.isDeckSelectionStartReady(
                        25948, "captured-game-window", 10_001L,
                    ),
                ),
                "the final action gate must block the saved Start control without target-mode proof",
            )
        } finally {
            StartupInputReadiness.resetForTest()
        }
    }

    @Test
    fun `visible Wild title template confirms mode even when title OCR is unavailable`() {
        val image = javax.imageio.ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/deck-selection-screen.png",
        )))
        val observation = TournamentModeConfirmation.observeImage(image)
        assertEquals(ObservedTournamentMode.WILD, observation.observedMode)
        assertEquals("title-visual-template-wild", observation.evidence)

        val standard = javax.imageio.ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/standard-ranked-deck-selection-user-20261029.png",
        )))
        val standardObservation = TournamentModeConfirmation.observeImage(standard)
        assertEquals(ObservedTournamentMode.STANDARD, standardObservation.observedMode)
    }

    @Test
    fun `latest live Standard deck frame cannot authorize Wild and startMatching preflights readiness`() {
        val image = javax.imageio.ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/standard-deck-selection-live-20261029-040056.png",
        )))
        assertTrue(ScreenWatchdog.hasEnabledDeckSelectionStartButton(image))
        val observation = TournamentModeConfirmation.observeImage(image)
        val result = TournamentModeConfirmation.evaluate(
            expectedMode = RunModeEnum.WILD,
            observation = observation,
            strategyId = "test-wild-deck",
            strategyName = "test Wild deck",
            deckSlot = 1,
        )
        assertTrue(
            result.state == TournamentModeConfirmationState.MISMATCH ||
                result.state == TournamentModeConfirmationState.UNRECOGNIZED,
            "the live Standard screenshot may be read as Standard or unknown, but never as confirmed Wild: ${result.observedMode}",
        )
        assertFalse(StartupInputReadiness.recordDeckSelectionStart(
            pid = 25948,
            hwnd = "captured-game-window",
            confidence = 100,
            evidence = "deck-selection-title-roi",
            expectedRunMode = RunModeEnum.WILD.name,
            observedRunMode = result.observedMode.name,
            enabledStartButtonVisible = true,
            modalVisible = false,
            now = 30_000L,
        ))
        assertFalse(StartupInputReadiness.isDeckSelectionStartReady(25948, "captured-game-window", 30_001L))

        val root = moduleRoot()
        val source = Files.readString(root.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "strategy", "mode", "TournamentModeStrategy.kt",
        )))
        val startMatching = source.substring(source.indexOf("fun startMatching()"))
        assertTrue(startMatching.indexOf("isStartupScreenConfirmedForAction(\"matchmaking.start\")") >= 0)
        assertTrue(startMatching.indexOf("isStartupScreenConfirmedForAction(\"matchmaking.start\")") <
            startMatching.indexOf("clickMatchmakingControl(START_RECT)"),
            "readiness rejection must stop before Start and automatic deck completion inputs")
        StartupInputReadiness.resetForTest()
    }

    @Test
    fun `wrong mode deck frame authorizes neither selector click nor Start without mode-selector proof`() {
        val image = javax.imageio.ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/tournament-wild-deck-standard-mode-20261029.png",
        )))
        assertTrue(ScreenWatchdog.hasEnabledDeckSelectionStartButton(image))
        val observed = TournamentModeConfirmation.observeImage(image)
        assertEquals(ObservedTournamentMode.STANDARD, observed.observedMode)
        assertEquals("title-visual-template-standard", observed.evidence)

        try {
            assertFalse(StartupInputReadiness.recordDeckSelectionStart(
                pid = 27692,
                hwnd = "captured-game-window",
                confidence = 100,
                evidence = "deck-selection-title-roi",
                expectedRunMode = RunModeEnum.WILD.name,
                observedRunMode = observed.observedMode.name,
                enabledStartButtonVisible = true,
                modalVisible = false,
                now = 20_000L,
            ))
            val freshDeckProof = StartupInputReadiness.isDeckSelectionStartReady(
                27692,
                "captured-game-window",
                20_001L,
            )
            listOf("mode.open-selector", "mode.select-wild", "matchmaking.start").forEach { action ->
                assertFalse(
                    ActionDispatchGate.allowForState(
                        action = action,
                        paused = false,
                        working = true,
                        currentSessionReady = false,
                        freshDeckSelectionStartReady = freshDeckProof,
                    ),
                    "unknown/mismatched mode evidence must not authorize $action",
                )
            }
        } finally {
            StartupInputReadiness.resetForTest()
        }
    }

    private fun observation(text: String): TournamentModeObservation =
        TournamentModeObservation(
            observedMode = TournamentModeConfirmation.classifyModeTitle(text),
            ocrText = text,
            evidence = "offline-test",
        )

    private fun moduleRoot(): Path =
        listOf(Path.of("."), Path.of("hs-script-app"))
            .first { Files.isRegularFile(it.resolve(Path.of("src", "main", "java", "club", "xiaojiawei", "hsscript", "status", "TournamentModeConfirmation.kt"))) }

    private fun appendEvidence(text: String) {
        val evidence = Path.of("target", "offline-evidence", "tournament-mode-confirmation.log")
        Files.createDirectories(evidence.parent)
        Files.writeString(evidence, text.trimEnd() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    companion object {
        private const val PIRATE_WARRIOR = DEFAULT_PIRATE_WARRIOR_STRATEGY_ID
    }
}
