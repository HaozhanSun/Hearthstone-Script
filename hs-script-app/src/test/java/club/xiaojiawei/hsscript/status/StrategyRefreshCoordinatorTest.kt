package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StrategyRefreshCoordinatorTest {

    @Test
    fun `refresh requested during a turn is deferred and applied at the next boundary`() {
        val events = mutableListOf<String>()
        val coordinator = StrategyRefreshCoordinator(events::add)
        coordinator.markTurnStarted()
        coordinator.request("beta-deployed")

        val duringTurn = coordinator.applyAtTurnBoundary("old") { "new" }

        assertEquals(StrategyRefreshCoordinator.Status.DEFERRED_ACTIVE_TURN, duringTurn.status)
        assertEquals("old", duringTurn.previous)
        assertFalse(duringTurn.replacement == "new")
        assertTrue(coordinator.hasPendingRequest())

        coordinator.markTurnEnded()
        val nextBoundary = coordinator.applyAtTurnBoundary("old") { "new" }

        assertEquals(StrategyRefreshCoordinator.Status.APPLIED, nextBoundary.status)
        assertEquals("old", nextBoundary.previous)
        assertEquals("new", nextBoundary.replacement)
        assertFalse(coordinator.hasPendingRequest())
        assertTrue(events.any { it.startsWith("STRATEGY_REFRESH_DEFERRED") })
        assertTrue(events.any { it.startsWith("STRATEGY_REFRESH_APPLIED") })
    }

    @Test
    fun `a failed refresh retains the old strategy and reports rollback`() {
        val events = mutableListOf<String>()
        val coordinator = StrategyRefreshCoordinator(events::add)
        coordinator.request("broken-plugin")

        val outcome = coordinator.applyAtTurnBoundary("old") {
            error("service loader failed")
        }

        assertEquals(StrategyRefreshCoordinator.Status.FAILED, outcome.status)
        assertEquals("old", outcome.previous)
        assertEquals("old", outcome.replacement)
        assertNotNull(outcome.error)
        assertFalse(coordinator.hasPendingRequest())
        assertTrue(events.any { it.startsWith("STRATEGY_REFRESH_FAILED") })
    }

    @Test
    fun `duplicate requests coalesce until the boundary`() {
        val coordinator = StrategyRefreshCoordinator()

        val first = coordinator.request("first")
        val second = coordinator.request("second")

        assertEquals(first, second)
        assertEquals("new", coordinator.applyAtTurnBoundary("old") { "new" }.replacement)
    }
}
