package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.WorkTime
import club.xiaojiawei.hsscript.bean.WorkTimeRule
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.enums.OperateEnum
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrategyDefaultDeckSlotBindingsTest {
    @Test
    fun `built-in defaults bind wild strategies to their configured slots`() {
        val defaults = StrategyDefaultDeckSlotBindings.deserialize(StrategyDefaultDeckSlotBindings.defaultBindingsJson())

        assertEquals(1, defaults[PIRATE_DEMON_HUNTER])
        assertEquals(2, defaults[PIRATE_WARRIOR])
        assertEquals(3, defaults[ELEMENTAL_MAGE])
        assertEquals(1, StrategyDefaultDeckSlotBindings.deckSlotForStrategy(PIRATE_DEMON_HUNTER, bindings = defaults))
        assertEquals(2, StrategyDefaultDeckSlotBindings.deckSlotForStrategy(PIRATE_WARRIOR, bindings = defaults))
        assertEquals(3, StrategyDefaultDeckSlotBindings.deckSlotForStrategy(ELEMENTAL_MAGE, bindings = defaults))
    }

    @Test
    fun `updated default binding persists by strategy id and survives rename or duplicate id`() {
        val updated = StrategyDefaultDeckSlotBindings.withBinding(
            bindings = StrategyDefaultDeckSlotBindings.builtInDefaults,
            strategyId = PIRATE_WARRIOR,
            deckSlot = 5,
        )
        val reloaded = StrategyDefaultDeckSlotBindings.deserialize(StrategyDefaultDeckSlotBindings.serialize(updated))

        assertEquals(5, reloaded[PIRATE_WARRIOR])
        assertEquals(5, StrategyDefaultDeckSlotBindings.deckSlotForStrategy(PIRATE_WARRIOR, bindings = reloaded))
        assertEquals(5, StrategyDefaultDeckSlotBindings.deckSlotForStrategy(PIRATE_WARRIOR, bindings = reloaded))
    }

    @Test
    fun `time rule explicit deck and strategy pairing wins over strategy default slot`() {
        val rule = rule(
            strategyId = PIRATE_DEMON_HUNTER,
            deckPos = setOf(2),
            pairedStrategyIds = mapOf(2 to PIRATE_WARRIOR),
        )
        val choice = StrategyDefaultDeckSlotBindings.chooseDeckSlots(
            rule = rule,
            strategyId = null,
            globalDeckSlots = listOf(1),
            bindings = StrategyDefaultDeckSlotBindings.builtInDefaults,
        )
        val decision = WorkTimeSlotStrategyBinding.resolve(rule, choice.deckSlots.single(), 9, ::strategyExists)

        assertEquals(listOf(2), choice.deckSlots)
        assertEquals("schedule-explicit-deck-slot", choice.assignmentReason)
        assertEquals(PIRATE_WARRIOR, decision.strategyId)
        assertEquals("paired-slot-strategy", decision.selectionReason)
    }

    @Test
    fun `strategy default slot wins before global slot when time rule is not explicit`() {
        val rule = rule(strategyId = PIRATE_WARRIOR, deckPos = emptySet(), pairedStrategyIds = emptyMap())

        val choice = StrategyDefaultDeckSlotBindings.chooseDeckSlots(
            rule = rule,
            strategyId = null,
            globalDeckSlots = listOf(1),
            bindings = StrategyDefaultDeckSlotBindings.builtInDefaults,
        )
        val decision = WorkTimeSlotStrategyBinding.resolve(rule, choice.deckSlots.single(), 9, ::strategyExists)

        assertEquals(listOf(2), choice.deckSlots)
        assertEquals("strategy-default-deck-slot", choice.assignmentReason)
        assertEquals(PIRATE_WARRIOR, decision.strategyId)
        assertEquals("rule-strategy-fallback", decision.selectionReason)
    }

    @Test
    fun `missing binding and invalid slots fall back to existing global config then safe slot`() {
        val sanitized = StrategyDefaultDeckSlotBindings.sanitize(
            mapOf(
                "" to 1,
                CANNON_WARRIOR to 10,
                "deleted-strategy" to 3,
                "duplicate-id" to 4,
                "duplicate-id" to 5,
            ),
            knownStrategyIds = setOf(CANNON_WARRIOR, "duplicate-id"),
        )

        assertFalse(CANNON_WARRIOR in sanitized)
        assertFalse("deleted-strategy" in sanitized)
        assertEquals(5, sanitized["duplicate-id"])

        val globalFallback = StrategyDefaultDeckSlotBindings.chooseDeckSlots(
            rule = rule(strategyId = CANNON_WARRIOR, deckPos = emptySet()),
            strategyId = null,
            globalDeckSlots = listOf(4),
            bindings = sanitized,
        )
        val safeFallback = StrategyDefaultDeckSlotBindings.chooseDeckSlots(
            rule = rule(strategyId = CANNON_WARRIOR, deckPos = emptySet()),
            strategyId = null,
            globalDeckSlots = listOf(99),
            bindings = sanitized,
        )

        assertEquals(listOf(4), globalFallback.deckSlots)
        assertEquals("single-configured-deck-slot", globalFallback.assignmentReason)
        assertEquals(listOf(1), safeFallback.deckSlots)
        assertEquals("safe-default-deck-slot", safeFallback.assignmentReason)
        assertNull(StrategyDefaultDeckSlotBindings.deckSlotForStrategy("deleted-strategy", bindings = sanitized))
    }

    @Test
    fun `main ui and config expose editable strategy default deck slot binding`() {
        val moduleRoot = moduleRoot()
        val fxml = Files.readString(moduleRoot.resolve(Path.of("src", "main", "resources", "fxml", "main.fxml")))
        val controller = Files.readString(moduleRoot.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "controller", "javafx", "MainController.kt",
        )))
        val gameUtil = Files.readString(moduleRoot.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "utils", "GameUtil.kt",
        )))

        assertTrue(ConfigEnum.STRATEGY_DEFAULT_DECK_SLOTS.defaultValue.contains(PIRATE_DEMON_HUNTER))
        assertTrue(ConfigEnum.STRATEGY_DEFAULT_DECK_SLOTS.defaultValue.contains(ELEMENTAL_MAGE))
        assertTrue(fxml.contains("fx:id=\"defaultDeckSlotBox\""))
        assertTrue(fxml.contains("保存为默认策略-卡组槽位绑定"))
        assertTrue(controller.contains("saveDefaultDeckSlotBinding"))
        assertTrue(controller.contains("StrategyDefaultDeckSlotBindings.storeBinding"))
        assertTrue(gameUtil.contains("StrategyDefaultDeckSlotBindings.chooseDeckSlots"))

        appendEvidence(
            "STRATEGY_DEFAULT_DECK_SLOT_BINDING default[$PIRATE_DEMON_HUNTER]=1 " +
                "default[$PIRATE_WARRIOR]=2 priority=explicit-time-rule>strategy-default>global-config>safe-default",
        )
    }

    private fun rule(
        strategyId: String,
        deckPos: Set<Int> = emptySet(),
        pairedStrategyIds: Map<Int, String> = emptyMap(),
    ): WorkTimeRule =
        WorkTimeRule(
            WorkTime("08:11", "08:39"),
            setOf(OperateEnum.CLOSE_GAME),
            RunModeEnum.WILD,
            strategyId,
            deckPos,
            true,
            pairedStrategyIds,
        )

    private fun strategyExists(strategyId: String): Boolean =
        strategyId in setOf(PIRATE_DEMON_HUNTER, PIRATE_WARRIOR, CANNON_WARRIOR)

    private fun moduleRoot(): Path =
        listOf(Path.of("."), Path.of("hs-script-app"))
            .first { Files.isRegularFile(it.resolve(Path.of("src", "main", "resources", "fxml", "main.fxml"))) }

    private fun appendEvidence(text: String) {
        val evidence = Path.of("target", "offline-evidence", "strategy-default-deck-slot-binding.log")
        Files.createDirectories(evidence.parent)
        Files.writeString(evidence, text.trimEnd() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    companion object {
        private const val PIRATE_DEMON_HUNTER = DEFAULT_PIRATE_DEMON_HUNTER_STRATEGY_ID
        private const val PIRATE_WARRIOR = DEFAULT_PIRATE_WARRIOR_STRATEGY_ID
        private const val ELEMENTAL_MAGE = DEFAULT_ELEMENTAL_MAGE_STRATEGY_ID
        private const val CANNON_WARRIOR = "e71234fa-9-standard-cannon-warrior-v1-0-9b1f-4d29-8f4f"
    }
}
