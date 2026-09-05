package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.bean.WorkTimeRule
import club.xiaojiawei.hsscript.bean.WorkTime
import kotlin.test.Test
import kotlin.test.assertEquals

class DeckPositionSelectorTest {
    @Test
    fun `active schedule deck position wins even when high priority is off`() {
        val rule = WorkTimeRule(
            workTime = WorkTime("00:00", "23:59"),
            operates = emptySet(),
            runMode = club.xiaojiawei.hsscriptbase.enums.RunModeEnum.WILD,
            strategyId = "pirate-warrior-mcts",
            deckPos = setOf(2),
            enable = true,
        )

        assertEquals(listOf(2), DeckPositionSelector.resolve(rule, listOf(1)))
    }

    @Test
    fun `global deck positions remain the fallback without an active rule`() {
        assertEquals(listOf(1), DeckPositionSelector.resolve(null, listOf(1)))
    }

    @Test
    fun `empty schedule deck position falls back to global positions`() {
        val rule = WorkTimeRule(
            workTime = WorkTime("00:00", "23:59"),
            operates = emptySet(),
            runMode = club.xiaojiawei.hsscriptbase.enums.RunModeEnum.WILD,
            strategyId = "pirate-warrior-mcts",
            deckPos = emptySet(),
            enable = true,
        )

        assertEquals(listOf(1), DeckPositionSelector.resolve(rule, listOf(1)))
    }
}
