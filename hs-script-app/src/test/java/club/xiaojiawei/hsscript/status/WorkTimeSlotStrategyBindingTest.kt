package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.WorkTime
import club.xiaojiawei.hsscript.bean.WorkTimeRule
import club.xiaojiawei.hsscript.bean.WorkTimeRuleSet
import club.xiaojiawei.hsscript.enums.OperateEnum
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkTimeSlotStrategyBindingTest {
    @Test
    fun `ten rows assign one deck slot with scattered warrior rows`() {
        val preset = WorkTimeRuleSet("预设1", (0 until 10).map { rule(setOf(1, 2)) }, "presets-one")
        WorkTimeRuleSlotStrategyNormalizer.normalize(listOf(preset))

        val slots = preset.getTimeRules().map { it.deckPos.single() }
        assertEquals(3, slots.count { it == 2 })
        assertEquals(7, slots.count { it == 1 })
        assertTrue(slots.windowed(3).any { 1 in it && 2 in it })
        preset.getTimeRules().forEach { row ->
            val slot = row.deckPos.single()
            assertEquals(mapOf(slot to WorkTimeRuleSlotStrategyNormalizer.pairedStrategyId(slot)), mapOf(slot to row.pairedStrategyIds[slot]))
        }
    }

    @Test
    fun `legacy multi-slot row is reduced to one slot and paired`() {
        val row = rule(setOf(1, 2), enable = false)
        val preset = WorkTimeRuleSet("legacy", listOf(row), "legacy")
        val result = WorkTimeRuleSlotStrategyNormalizer.normalize(listOf(preset))

        assertEquals(1, row.deckPos.size)
        assertEquals(false, row.enable)
        assertEquals(1, result.events.size)
        assertEquals("legacy-multi-slot-normalized:work-time-slot-strategy-binding-v1", result.events.single().assignmentReason)
    }

    @Test
    fun `slot strategy binding selects matching strategy`() {
        val row = rule(setOf(2), paired = mapOf(2 to DEFAULT_PIRATE_WARRIOR_STRATEGY_ID))
        val decision = WorkTimeSlotStrategyBinding.resolve(row, 2, 9) { it == DEFAULT_PIRATE_WARRIOR_STRATEGY_ID }

        assertTrue(decision.accepted)
        assertEquals(DEFAULT_PIRATE_WARRIOR_STRATEGY_ID, decision.strategyId)
        assertEquals("paired-slot-strategy", decision.selectionReason)
    }

    @Test
    fun `missing paired strategy refuses unsafe fallback`() {
        val row = rule(setOf(2), paired = mapOf(2 to "deleted-strategy"), strategy = "old-strategy")
        val decision = WorkTimeSlotStrategyBinding.resolve(row, 2, 9) { false }

        assertFalse(decision.accepted)
        assertNull(decision.strategyId)
        assertEquals("paired-strategy-missing", decision.fallbackReason)
    }

    @Test
    fun `paired bindings survive save and reload`() {
        val original = WorkTimeRuleSet(
            "bindings",
            listOf(rule(setOf(1), paired = mapOf(1 to DEFAULT_PIRATE_DEMON_HUNTER_STRATEGY_ID))),
        )
        val json = jacksonObjectMapper().writeValueAsString(original)
        val restored = jacksonObjectMapper().readValue(json, WorkTimeRuleSet::class.java)

        assertEquals(mapOf(1 to DEFAULT_PIRATE_DEMON_HUNTER_STRATEGY_ID), restored.getTimeRules().single().pairedStrategyIds)
    }

    @Test
    fun `normalization is deterministic for the same ten-row schedule`() {
        fun slots(): List<Int> {
            val preset = WorkTimeRuleSet("预设1", (0 until 10).map { rule(setOf(1, 2)) }, "presets-one")
            WorkTimeRuleSlotStrategyNormalizer.normalize(listOf(preset))
            return preset.getTimeRules().map { it.deckPos.single() }
        }
        assertEquals(slots(), slots())
    }

    private fun rule(
        deckPos: Set<Int>,
        paired: Map<Int, String> = emptyMap(),
        strategy: String = "fallback",
        enable: Boolean = true,
    ) = WorkTimeRule(
        WorkTime("08:00", "08:30"),
        setOf(OperateEnum.CLOSE_GAME),
        RunModeEnum.STANDARD,
        strategy,
        deckPos,
        enable,
        paired,
    )
}
