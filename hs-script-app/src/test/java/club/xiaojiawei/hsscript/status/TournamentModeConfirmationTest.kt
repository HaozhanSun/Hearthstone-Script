package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
        assertTrue(tournament.indexOf("confirmBeforeDeckSelection") < tournament.indexOf("selectDeck(deckStrategy)"))
        assertTrue(confirmation.contains("title-roi-ocr"))
        assertTrue(confirmation.contains("TOURNAMENT_MODE_CONFIRMATION_FAILED"))
        assertTrue(confirmation.contains("expectedMode="))
        assertTrue(confirmation.contains("observedMode="))
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
