package club.xiaojiawei.hsscript.bean

import club.xiaojiawei.hsscript.enums.DEFAULT_WORK_TIME
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class WorkTimeRuleTest {

    @Test
    fun `schedule rows do not share mutable default time`() {
        val rules = (1..6).map {
            WorkTimeRule(DEFAULT_WORK_TIME, emptySet(), RunModeEnum.STANDARD, "", emptySet(), true)
        }

        rules[0].workTime.startTime = "02:02"
        rules[1].workTime.startTime = "04:41"
        rules[2].workTime.startTime = "07:13"
        rules[3].workTime.startTime = "09:51"
        rules[4].workTime.startTime = "12:16"
        rules[5].workTime.startTime = "14:49"

        assertEquals(listOf("02:02", "04:41", "07:13", "09:51", "12:16", "14:49"), rules.map { it.workTime.startTime })
        assertEquals("00:00", DEFAULT_WORK_TIME.startTime)
        assertEquals(6, rules.map { it.workTime }.distinct().size)
        assertNotSame(rules[0].workTime, rules[1].workTime)
    }
}
