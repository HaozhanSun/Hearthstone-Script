package club.xiaojiawei.hsscript.bean

import club.xiaojiawei.hsscript.utils.WorkTimeJitter
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkTimeRuleSetTest {

    @Test
    fun `copying a preset preserves its shared jitter setting`() {
        val original = WorkTimeRuleSet("预设1")
        original.jitterSeconds = 150

        val copy = original.clone()

        assertEquals(150, copy.jitterSeconds)
        copy.jitterSeconds = 30
        assertEquals(150, original.jitterSeconds)
    }

    @Test
    fun `jitter setting is bounded`() {
        val ruleSet = WorkTimeRuleSet("预设1")

        ruleSet.jitterSeconds = -5
        assertEquals(0, ruleSet.jitterSeconds)

        ruleSet.jitterSeconds = Int.MAX_VALUE
        assertEquals(WorkTimeJitter.MAX_JITTER_SECONDS, ruleSet.jitterSeconds)
    }

    @Test
    fun `jitter setting is persisted with a preset`() {
        val original = WorkTimeRuleSet("预设1")
        original.jitterSeconds = 150

        val json = jacksonObjectMapper().writeValueAsString(original)
        val restored = jacksonObjectMapper().readValue(json, WorkTimeRuleSet::class.java)

        assertTrue(json.contains("jitterSeconds"))
        assertEquals(150, restored.jitterSeconds)
    }

    @Test
    fun `cross-midnight work time survives preset save and load`() {
        val original = WorkTimeRuleSet(
            "跨午夜",
            listOf(
                WorkTimeRule(
                    WorkTime("23:43", "00:18"),
                    emptySet(),
                    RunModeEnum.STANDARD,
                    "",
                    emptySet(),
                    true,
                ),
            ),
        )

        val json = jacksonObjectMapper().writeValueAsString(original)
        val restored = jacksonObjectMapper().readValue(json, WorkTimeRuleSet::class.java)
        val restoredWorkTime = restored.getTimeRules().single().workTime

        assertTrue(json.contains("23:43"))
        assertTrue(json.contains("00:18"))
        assertEquals("23:43", restoredWorkTime.startTime)
        assertEquals("00:18", restoredWorkTime.endTime)
    }

    @Test
    fun `invalid work time text remains invalid instead of becoming midnight`() {
        val workTime = WorkTime("not-a-time", "00:18")

        assertNull(workTime.parseStartTime())
        assertEquals("00:18", workTime.endTime)
        assertEquals("00:18", WorkTime.pattern.format(workTime.parseEndTime()))
    }
}
