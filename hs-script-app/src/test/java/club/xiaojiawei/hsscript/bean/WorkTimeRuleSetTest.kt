package club.xiaojiawei.hsscript.bean

import club.xiaojiawei.hsscript.utils.WorkTimeJitter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
