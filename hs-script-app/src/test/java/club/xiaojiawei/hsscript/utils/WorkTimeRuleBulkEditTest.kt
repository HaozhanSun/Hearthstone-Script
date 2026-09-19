package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.bean.WorkTime
import club.xiaojiawei.hsscript.bean.WorkTimeRule
import club.xiaojiawei.hsscript.bean.WorkTimeRuleSet
import club.xiaojiawei.hsscript.enums.OperateEnum
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkTimeRuleBulkEditTest {
    @Test
    fun `bulk update applies selected fields to at least twenty rows`() {
        val rules = buildRules(20)

        val result =
            WorkTimeRuleBulkEdit.apply(
                rules,
                WorkTimeRuleBulkEditRequest(
                    runMode = RunModeEnum.WILD,
                    strategyId = "strategy-wild",
                    strategyAllowedRunModes = setOf(RunModeEnum.WILD),
                    deckPos = setOf(2, 5, 8),
                    enable = false,
                ),
            )

        assertEquals(20, result.updatedCount)
        assertTrue(result.validation.canApply)
        rules.forEach {
            assertEquals(RunModeEnum.WILD, it.runMode)
            assertEquals("strategy-wild", it.strategyId)
            assertEquals(setOf(2, 5, 8), it.deckPos)
            assertFalse(it.enable)
        }
    }

    @Test
    fun `bulk update changes only selected fields`() {
        val rules = buildRules(20)
        val before =
            rules.map {
                Snapshot(
                    it.workTime.startTime,
                    it.workTime.endTime,
                    it.operates,
                    it.strategyId,
                    it.deckPos,
                )
            }

        WorkTimeRuleBulkEdit.apply(
            rules,
            WorkTimeRuleBulkEditRequest(
                runMode = RunModeEnum.CASUAL,
                enable = false,
            ),
        )

        rules.forEachIndexed { index, rule ->
            assertEquals(RunModeEnum.CASUAL, rule.runMode)
            assertFalse(rule.enable)
            assertEquals(before[index].startTime, rule.workTime.startTime)
            assertEquals(before[index].endTime, rule.workTime.endTime)
            assertEquals(before[index].operates, rule.operates)
            assertEquals(before[index].strategyId, rule.strategyId)
            assertEquals(before[index].deckPos, rule.deckPos)
        }
    }

    @Test
    fun `bulk update does not change completion actions or jitter value`() {
        val ruleSet = WorkTimeRuleSet("批量测试", buildRules(20))
        ruleSet.jitterSeconds = 321

        WorkTimeRuleBulkEdit.apply(
            ruleSet.getTimeRules(),
            WorkTimeRuleBulkEditRequest(
                deckPos = setOf(3, 6),
                enable = true,
            ),
        )

        assertEquals(321, ruleSet.jitterSeconds)
        ruleSet.getTimeRules().forEach {
            assertEquals(setOf(OperateEnum.CLOSE_GAME, OperateEnum.CLOSE_PLATFORM), it.operates)
            assertEquals(setOf(3, 6), it.deckPos)
            assertTrue(it.enable)
        }
    }

    @Test
    fun `bulk update survives persistence reread`() {
        val ruleSet = WorkTimeRuleSet("批量测试", buildRules(20))
        ruleSet.jitterSeconds = 111
        WorkTimeRuleBulkEdit.apply(
            ruleSet.getTimeRules(),
            WorkTimeRuleBulkEditRequest(
                runMode = RunModeEnum.PRACTICE,
                strategyId = "strategy-practice",
                strategyAllowedRunModes = setOf(RunModeEnum.PRACTICE),
                deckPos = setOf(4),
                enable = false,
            ),
        )

        val json = jacksonObjectMapper().writeValueAsString(ruleSet)
        val restored = jacksonObjectMapper().readValue(json, WorkTimeRuleSet::class.java)

        assertEquals(111, restored.jitterSeconds)
        assertEquals(20, restored.getTimeRules().size)
        restored.getTimeRules().forEach {
            assertEquals(RunModeEnum.PRACTICE, it.runMode)
            assertEquals("strategy-practice", it.strategyId)
            assertEquals(setOf(4), it.deckPos)
            assertFalse(it.enable)
        }
    }

    @Test
    fun `bulk update rejects unconfigured rows instead of overwriting unknown data`() {
        val rules = buildRules(3).toMutableList()
        rules +=
            WorkTimeRule(
                WorkTime(null, "00:18"),
                setOf(OperateEnum.CLOSE_GAME),
                RunModeEnum.STANDARD,
                "unchanged",
                setOf(1),
                true,
            )

        val request = WorkTimeRuleBulkEditRequest(runMode = RunModeEnum.WILD)
        val validation = WorkTimeRuleBulkEdit.validate(rules, request)

        assertFalse(validation.canApply)
        assertEquals(listOf(4), validation.unconfiguredRows)
        assertFailsWith<IllegalArgumentException> {
            WorkTimeRuleBulkEdit.apply(rules, request)
        }
        assertEquals(RunModeEnum.STANDARD, rules.last().runMode)
        assertEquals("unchanged", rules.last().strategyId)
    }

    @Test
    fun `bulk strategy update validates against retained or selected run mode`() {
        val rules = buildRules(2)
        val incompatible =
            WorkTimeRuleBulkEdit.validate(
                rules,
                WorkTimeRuleBulkEditRequest(
                    strategyId = "wild-only",
                    strategyAllowedRunModes = setOf(RunModeEnum.WILD),
                ),
            )
        val compatible =
            WorkTimeRuleBulkEdit.validate(
                rules,
                WorkTimeRuleBulkEditRequest(
                    runMode = RunModeEnum.WILD,
                    strategyId = "wild-only",
                    strategyAllowedRunModes = setOf(RunModeEnum.WILD),
                ),
            )

        assertEquals(listOf(1, 2), incompatible.incompatibleStrategyRows)
        assertFalse(incompatible.canApply)
        assertTrue(compatible.canApply)
    }

    private fun buildRules(count: Int): List<WorkTimeRule> =
        (0 until count).map { index ->
            val startHour = index % 20
            val endHour = (startHour + 1) % 24
            WorkTimeRule(
                WorkTime("%02d:11".format(startHour), "%02d:39".format(endHour)),
                setOf(OperateEnum.CLOSE_GAME, OperateEnum.CLOSE_PLATFORM),
                RunModeEnum.STANDARD,
                "strategy-standard-$index",
                setOf(1, 7),
                true,
            )
        }

    private data class Snapshot(
        val startTime: String?,
        val endTime: String?,
        val operates: Set<OperateEnum>,
        val strategyId: String,
        val deckPos: Set<Int>,
    )
}
