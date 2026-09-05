package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.WorkTimeRuleSet
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkTimeScheduleSelectionTest {

    @Test
    fun `persisted weekday mapping resolves the saved preset id rather than the first preset`() {
        val presetOne = WorkTimeRuleSet("预设1", id = "preset-one")
        val presetTwo = WorkTimeRuleSet("预设2", id = "preset-two")
        val presetThree = WorkTimeRuleSet("预设3", id = "preset-three")
        val ruleSets = listOf(presetOne, presetTwo, presetThree)
        val weekdaySetting = listOf(
            "preset-two",
            "preset-three",
            "preset-one",
            "preset-two",
            "preset-three",
            "preset-one",
            "preset-two",
        )
        val mapper = jacksonObjectMapper()
        val restoredRuleSets = mapper.readValue(
            mapper.writeValueAsString(ruleSets),
            Array<WorkTimeRuleSet>::class.java,
        ).toList()
        val restoredWeekdaySetting = mapper.readValue(
            mapper.writeValueAsString(weekdaySetting),
            Array<String>::class.java,
        ).toList()

        assertEquals("preset-two", restoredWeekdaySetting[0])
        assertEquals("preset-three", restoredWeekdaySetting[1])
        assertEquals("preset-two", WorkTimeStatus.resolveWorkTimeRuleSet(restoredRuleSets, restoredWeekdaySetting, 0)?.id)
        assertEquals("preset-three", WorkTimeStatus.resolveWorkTimeRuleSet(restoredRuleSets, restoredWeekdaySetting, 1)?.id)
        assertEquals("preset-one", WorkTimeStatus.resolveWorkTimeRuleSet(restoredRuleSets, restoredWeekdaySetting, 2)?.id)
    }

    @Test
    fun `missing or empty weekday mapping does not fall back to preset one`() {
        val ruleSets = listOf(
            WorkTimeRuleSet("预设1", id = "preset-one"),
            WorkTimeRuleSet("预设2", id = "preset-two"),
        )

        assertNull(WorkTimeStatus.resolveWorkTimeRuleSet(ruleSets, listOf(""), 0))
        assertNull(WorkTimeStatus.resolveWorkTimeRuleSet(ruleSets, emptyList(), 0))
        assertNull(WorkTimeStatus.resolveWorkTimeRuleSet(ruleSets, listOf("unknown"), 0))
    }
}
