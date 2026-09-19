package club.xiaojiawei.hsscript.bean

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract test for the persisted schedule shape used by ConfigExUtil.
 * This deliberately reads the same JSON fields that WORK_TIME_RULE_SET
 * stores, without touching the user's live config directory.
 */
class StandardCannonWarriorWorkTimeContractTest {
    @Test
    fun `standard cannon warrior is routed to standard deck slot three`() {
        val resource = javaClass.classLoader
            .getResourceAsStream("offline/standard-cannon-warrior-work-time-schedule.json")
            ?: error("missing standard cannon warrior schedule fixture")
        val mapper = jacksonObjectMapper()
        val json = resource.bufferedReader().use { it.readText() }
        val original = mapper.readValue(json, Array<WorkTimeRuleSet>::class.java).single()
        val rule = original.getTimeRules().single()

        assertEquals(RunModeEnum.STANDARD, rule.runMode)
        assertEquals(setOf(3), rule.deckPos)
        assertEquals(
            "e71234fa-9-standard-cannon-warrior-v1-0-9b1f-4d29-8f4f",
            rule.strategyId,
        )
        assertTrue(rule.enable)
        assertEquals("00:00", rule.workTime.startTime)
        assertEquals("23:59", rule.workTime.endTime)

        val persisted = mapper.writeValueAsString(listOf(original))
        val restored = mapper.readValue(persisted, Array<WorkTimeRuleSet>::class.java).single()
        val restoredRule = restored.getTimeRules().single()
        assertEquals(RunModeEnum.STANDARD, restoredRule.runMode)
        assertEquals(setOf(3), restoredRule.deckPos)
        assertEquals(rule.strategyId, restoredRule.strategyId)
        assertTrue(persisted.contains("\"runMode\":\"STANDARD\""))
        assertTrue(persisted.contains("\"deckPos\":[3]"))
    }
}


