package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WildOnlyMctsStrategyModeTest {
    @Test
    fun `released pirate mcts entry points are wild only`() {
        val expected = setOf(RunModeEnum.WILD)

        assertEquals(expected, HsPirateWarriorMctsDeckStrategy().runModes.toSet())
        assertEquals(expected, HsPirateDemonHunterMctsGlobalPlanDeckStrategy().runModes.toSet())
    }

    @Test
    fun `standard mode cannot expose released pirate mcts entry points`() {
        val standardStrategies = listOf(
            HsPirateWarriorMctsDeckStrategy(),
            HsPirateDemonHunterMctsGlobalPlanDeckStrategy(),
        ).filter { RunModeEnum.STANDARD in it.runModes }

        assertFalse(standardStrategies.isNotEmpty())
    }
}
