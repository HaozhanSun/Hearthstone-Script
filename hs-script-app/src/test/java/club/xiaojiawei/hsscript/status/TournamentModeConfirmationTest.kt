package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscript.status.surrender.CurrentRankDetector
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
import club.xiaojiawei.hsscript.strategy.mode.PreMatchRankGate
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.HashSet
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TournamentModeConfirmationTest {
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
                "strategy=${result.strategyName} deckSlot=${result.deckSlot} action=PAUSE_BEFORE_DECK_SELECTION",
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
        assertTrue(tournament.contains("observeFreshTournamentStartupScreen"))
        assertTrue(tournament.indexOf("observeFreshTournamentStartupScreen") < tournament.indexOf("clickModeChangeButton()"))
        assertTrue(tournament.contains("TOURNAMENT_STARTUP_ACTION_DEFERRED"))
        assertTrue(tournament.contains("TOURNAMENT_STARTUP_HANDOFF screen=DECK_SELECTION"))
        assertTrue(tournament.contains("RECOVER_DECK_SELECTION_WITHOUT_MODE_SWITCH"))
        assertTrue(tournament.contains("TOURNAMENT_STARTUP_ACTION_BLOCKED"))
        assertTrue(tournament.indexOf("confirmBeforeDeckSelection") < tournament.indexOf("selectDeck(deckStrategy, expectedDeckSlot)"))
        assertTrue(tournament.contains("DECK_SLOT_RESOLVED"))
        assertTrue(tournament.contains("recoverDeckSelectionAndStart"))
        assertTrue(tournament.contains("GameUtil.lClickDeckSlot(deckSlot)"))
        assertFalse(tournament.contains("GameUtil.lClickDeckPos(deckSlot)"))
        assertTrue(tournament.contains("MATCHMAKING_INPUT_DISPATCH mode=normal"))
        assertTrue(tournament.contains("MouseUtil.leftButtonClick(pos, ScriptStatus.gameHWND)"))
        assertFalse(tournament.contains("leftButtonClickForRecovery(rect.getCenterClickPos())"))
        assertTrue(tournament.contains("MATCHMAKING_ERROR_DIALOG_PROBE_DEFERRED"))
        assertTrue(tournament.contains("input=none"), "an unverified error-modal probe must not click")
        assertTrue(tournament.contains("scheduleMatchmakingDialogRecovery(traceId)"))
        assertTrue(tournament.contains("MatchmakingDialogRecoveryPolicy.dispatchConfirm(decision)"))
        assertTrue(confirmation.contains("title-roi-ocr"))
        assertTrue(confirmation.contains("ScreenRecoveryCaptureAuthority.isAuthorized(frame.evidence)"))
        assertTrue(confirmation.contains("GameUtil.isVerifiedCurrentGameWindow(hwnd, pid)"))
        assertTrue(confirmation.contains("WildModeTitleVisualMatcher.confidence(screen)"))
        assertTrue(confirmation.contains("containsModeCue(titleText)"))
        assertTrue(confirmation.contains("capturedPid=\${capture.capturedPid}"))
        assertFalse(confirmation.contains("Robot().createScreenCapture"), "mode proof cannot use unverified desktop pixels")
        assertTrue(confirmation.contains("TOURNAMENT_MODE_CONFIRMATION_OCR provider=LEGACY_FAST"))
        assertFalse(confirmation.contains("CONTINUE_DETERMINISTIC_MODE"))
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
    fun `three unknown mode observations preserve running state before deck selection`() {
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
            assertFalse(PauseStatus.isPause, "unknown mode must remain a no-input retry, not an automatic pause")
        } finally {
            PauseStatus.setAutomaticPause(false)
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
