package club.xiaojiawei.hsscript.enums

import club.xiaojiawei.hsscript.bean.WorkTimeRule
import club.xiaojiawei.hsscript.bean.WorkTimeRuleSet
import club.xiaojiawei.hsscript.utils.WorkTimeWindow
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkTimePresetDefaultsTest {

    private val forbiddenMinutes = setOf(0, 15, 30, 45)

    @Test
    fun `default presets use bounded all-day random windows`() {
        val ruleSets =
            jacksonObjectMapper()
                .readValue(ConfigEnum.WORK_TIME_RULE_SET.defaultValue, Array<WorkTimeRuleSet>::class.java)
                .toList()

        assertPreset(
            ruleSets.firstOrNull { it.getName() == "预设1" },
            expectedGaps = listOf(75L, 72L, 75L, 78L, 69L, 75L, 81L, 80L, 75L, 75L, 79L, 101L, 72L),
        )
        assertPreset(
            ruleSets.firstOrNull { it.getName() == "预设2" },
            expectedGaps = listOf(71L, 76L, 71L, 79L, 77L, 79L, 83L, 75L, 76L, 79L, 82L, 77L, 81L),
        )
    }

    private fun assertPreset(
        ruleSet: WorkTimeRuleSet?,
        expectedGaps: List<Long>,
    ) {
        val preset = assertNotNull(ruleSet)
        val rules = preset.getTimeRules()

        assertEquals(13, rules.size)
        assertTrue(rules.all { it.enable })
        assertTrue(rules.any { it.start() > it.end() })
        assertDefaultRuleFields(rules)

        val durations = rules.map { WorkTimeWindow.durationMinutes(it.start(), it.end()) }
        val gaps =
            rules.indices.map { index ->
                WorkTimeWindow.gapMinutes(
                    rules[index].end(),
                    rules[(index + 1) % rules.size].start(),
                )
            }

        assertTrue(durations.all { it in 1..40 })
        assertTrue(gaps.all { it >= 60 })
        assertEquals(expectedGaps, gaps)
        assertTrue(durations.distinct().size >= 6)
        assertTrue(gaps.distinct().size >= 6)

        rules.forEach {
            assertFalse(it.start().minute in forbiddenMinutes)
            assertFalse(it.end().minute in forbiddenMinutes)
        }

        val startHours = rules.map { it.start().hour }.toSet()
        assertTrue(startHours.any { it in 1..2 })
        assertTrue(startHours.any { it in 3..5 })
        assertTrue(startHours.any { it in 6..10 })
        assertTrue(startHours.any { it in 11..13 })
        assertTrue(startHours.any { it in 14..17 })
        assertTrue(startHours.any { it in 18..21 })
        assertTrue(startHours.any { it == 23 })
    }

    private fun assertDefaultRuleFields(rules: List<WorkTimeRule>) {
        rules.forEach {
            assertEquals(DEFAULT_OPERATIONS, it.operates)
            assertEquals(DEFAULT_RUN_MODE_ENUM, it.runMode)
            assertEquals(DEFAULT_DECK_STRATEGY_ID, it.strategyId)
            assertEquals(DEFAULT_DECK_POS.toSet(), it.deckPos)
        }
    }

    private fun WorkTimeRule.start(): LocalTime = assertNotNull(workTime.parseStartTime())

    private fun WorkTimeRule.end(): LocalTime = assertNotNull(workTime.parseEndTime())
}
