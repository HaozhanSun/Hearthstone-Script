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

    @Test
    fun `pirate entries expose the current shared strategy revision`() {
        val revision = "V${PirateMctsStrategyVersion.REVISION} · build "
        assertEquals(
            true,
            HsPirateWarriorMctsDeckStrategy().name().contains(revision),
        )
        assertEquals(
            true,
            HsPirateDemonHunterMctsGlobalPlanDeckStrategy().name().contains(revision),
        )
        assertEquals("2.9", PirateMctsStrategyVersion.REVISION)
        assertEquals(
            true,
            HsPirateDemonHunterMctsGlobalPlanDeckStrategy().name().startsWith("海盗瞎 V2.9 · build "),
        )
        assertEquals(
            true,
            HsPirateWarriorMctsDeckStrategy().name().startsWith("海盗战 V2.9 · build "),
        )
    }
}
