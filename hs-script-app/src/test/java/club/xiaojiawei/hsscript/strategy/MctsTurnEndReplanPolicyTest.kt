package club.xiaojiawei.hsscript.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MctsTurnEndReplanPolicyTest {

    @Test
    fun `initial planning is pass zero and three replans are allowed`() {
        val first = MctsTurnEndReplanPolicy.decide(completedReplans = 0, liveActionable = true)
        val second = MctsTurnEndReplanPolicy.decide(completedReplans = 1, liveActionable = true)
        val third = MctsTurnEndReplanPolicy.decide(completedReplans = 2, liveActionable = true)

        assertEquals(0, MctsTurnEndReplanPolicy.INITIAL_PLANNING_PASS)
        assertEquals(3, MctsTurnEndReplanPolicy.MAX_REPLANS)
        assertEquals(1, MctsTurnEndReplanPolicy.totalPlanningPasses(0))
        assertEquals(4, MctsTurnEndReplanPolicy.totalPlanningPasses(3))
        assertEquals(1, first.attempt)
        assertEquals(1, first.planningPass)
        assertEquals(2, second.attempt)
        assertEquals(2, second.planningPass)
        assertEquals(3, third.attempt)
        assertEquals(3, third.planningPass)
        assertTrue(first.shouldReplan)
        assertTrue(second.shouldReplan)
        assertTrue(third.shouldReplan)
    }

    @Test
    fun `fourth replan is rejected and holds when live action remains`() {
        val exhausted = MctsTurnEndReplanPolicy.decide(completedReplans = 3, liveActionable = true)

        assertFalse(exhausted.shouldReplan)
        assertEquals(4, exhausted.planningPass)
        assertEquals(4, exhausted.attempt)
        assertTrue(exhausted.freshLiveRescanRequired)
        assertFalse(exhausted.reusePreviousPlan)
        assertFalse(exhausted.allowEndTurnWhenExhausted)
        assertEquals("live-state-actionable-after-replan-budget-exhausted", exhausted.reason)
    }

    @Test
    fun `no live action permits the normal end-turn path after a fresh scan`() {
        val noAction = MctsTurnEndReplanPolicy.decide(completedReplans = 3, liveActionable = false)

        assertFalse(noAction.shouldReplan)
        assertTrue(noAction.freshLiveRescanRequired)
        assertFalse(noAction.reusePreviousPlan)
        assertTrue(noAction.allowEndTurnWhenExhausted)
        assertEquals("no-live-actionable-creator", noAction.reason)
    }

    @Test
    fun `negative replan count is rejected`() {
        kotlin.runCatching {
            MctsTurnEndReplanPolicy.decide(completedReplans = -1, liveActionable = true)
        }.onSuccess { error("negative replan count should be rejected") }
    }
}
