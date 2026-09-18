package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeckStrategyManagerSelectionTest {
    @Test
    fun `debug override outside normal schedule retains the user deck selection`() {
        assertEquals(
            "pirate-demon-hunter-mcts",
            DeckStrategyManager.effectiveSelection(
                highPrioritySchedule = true,
                ordinaryScheduleActive = false,
                scheduleSelection = null,
                userSelection = "pirate-demon-hunter-mcts",
            ),
        )
    }

    @Test
    fun `debug override outside normal schedule retains the user run mode`() {
        assertEquals(
            "WILD",
            DeckStrategyManager.effectiveSelection(
                highPrioritySchedule = true,
                ordinaryScheduleActive = false,
                scheduleSelection = null,
                userSelection = "WILD",
            ),
        )
    }

    @Test
    fun `active normal schedule still takes precedence when high priority is enabled`() {
        assertEquals(
            "scheduled-strategy",
            DeckStrategyManager.effectiveSelection(
                highPrioritySchedule = true,
                ordinaryScheduleActive = true,
                scheduleSelection = "scheduled-strategy",
                userSelection = "pirate-demon-hunter-mcts",
            ),
        )
    }

    @Test
    fun `refresh replacement cannot report applied when active strategy id disappeared`() {
        val failure = assertFailsWith<IllegalStateException> {
            DeckStrategyManager.resolveRefreshReplacement(
                previous = "active-strategy",
                loaded = listOf("different-strategy"),
                id = { it },
            )
        }

        assertEquals("matching-id-not-found strategy=active-strategy", failure.message)
    }
}
