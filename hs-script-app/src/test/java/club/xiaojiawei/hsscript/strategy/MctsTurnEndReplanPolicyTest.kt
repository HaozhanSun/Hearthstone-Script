package club.xiaojiawei.hsscript.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MctsTurnEndReplanPolicyTest {

    @Test
    fun `initial planning is pass zero and five replans are allowed`() {
        val first = MctsTurnEndReplanPolicy.decide(completedReplans = 0, liveActionable = true)
        val second = MctsTurnEndReplanPolicy.decide(completedReplans = 1, liveActionable = true)
        val third = MctsTurnEndReplanPolicy.decide(completedReplans = 2, liveActionable = true)
        val fifth = MctsTurnEndReplanPolicy.decide(completedReplans = 4, liveActionable = true)

        assertEquals(0, MctsTurnEndReplanPolicy.INITIAL_PLANNING_PASS)
        assertEquals(5, MctsTurnEndReplanPolicy.MAX_REPLANS)
        assertEquals(1, MctsTurnEndReplanPolicy.totalPlanningPasses(0))
        assertEquals(6, MctsTurnEndReplanPolicy.totalPlanningPasses(5))
        assertEquals(1, first.attempt)
        assertEquals(1, first.planningPass)
        assertEquals(2, second.attempt)
        assertEquals(2, second.planningPass)
        assertEquals(3, third.attempt)
        assertEquals(3, third.planningPass)
        assertEquals(5, fifth.attempt)
        assertEquals(5, fifth.planningPass)
        assertTrue(first.shouldReplan)
        assertTrue(second.shouldReplan)
        assertTrue(third.shouldReplan)
    }

    @Test
    fun `sixth replan is rejected without authorizing end turn when live action remains`() {
        val exhausted = MctsTurnEndReplanPolicy.decide(completedReplans = 5, liveActionable = true)

        assertFalse(exhausted.shouldReplan)
        assertEquals(6, exhausted.planningPass)
        assertEquals(6, exhausted.attempt)
        assertTrue(exhausted.freshLiveRescanRequired)
        assertFalse(exhausted.reusePreviousPlan)
        assertFalse(exhausted.allowEndTurnWhenExhausted)
        assertEquals("live-state-actionable-after-replan-budget-exhausted", exhausted.reason)
    }

    @Test
    fun `actionable fresh scan gets one bounded retry then recovery watch`() {
        val retry = MctsTurnEndReplanPolicy.decideActionRecovery(0, true, true)
        assertTrue(retry.retryFreshPlan)
        assertFalse(retry.enterRecoveryWatch)
        assertFalse(retry.allowEndTurn)

        val exhausted = MctsTurnEndReplanPolicy.decideActionRecovery(1, true, true)
        assertFalse(exhausted.retryFreshPlan)
        assertTrue(exhausted.enterRecoveryWatch)
        assertFalse(exhausted.allowEndTurn)
    }

    @Test
    fun `stale scan retries before either end turn or recovery`() {
        val decision = MctsTurnEndReplanPolicy.decideActionRecovery(1, false, false)
        assertTrue(decision.retryFreshPlan)
        assertFalse(decision.enterRecoveryWatch)
        assertFalse(decision.allowEndTurn)
    }

    @Test
    fun `fresh non-actionable scan permits normal end turn`() {
        val decision = MctsTurnEndReplanPolicy.decideActionRecovery(1, false, true)
        assertFalse(decision.retryFreshPlan)
        assertFalse(decision.enterRecoveryWatch)
        assertTrue(decision.allowEndTurn)
    }

    @Test
    fun `false positive candidate disappears on fresh rescan before end turn`() {
        val firstScan = MctsTurnEndReplanPolicy.decide(5, liveActionable = true)
        assertFalse(firstScan.allowEndTurnWhenExhausted)

        val freshRescan = MctsTurnEndReplanPolicy.decideActionRecovery(
            completedRecoveryRetries = 1,
            liveActionable = false,
            freshLiveScan = true,
        )
        assertTrue(freshRescan.allowEndTurn)
        assertEquals("fresh-live-non-actionable-normal-end-turn", freshRescan.reason)
    }

    @Test
    fun `empty or disappearing action set is not treated as actionable work`() {
        val empty = MctsTurnEndReplanPolicy.decideActionRecovery(0, false, true)
        val disappeared = MctsTurnEndReplanPolicy.decideActionRecovery(1, false, true)

        assertTrue(empty.allowEndTurn)
        assertTrue(disappeared.allowEndTurn)
        assertFalse(empty.enterRecoveryWatch)
        assertFalse(disappeared.retryFreshPlan)
    }

    @Test
    fun `timeout or planner exception boundary requires a fresh scan`() {
        val timedOutScan = MctsTurnEndReplanPolicy.decideActionRecovery(
            completedRecoveryRetries = 1,
            liveActionable = false,
            freshLiveScan = false,
        )

        assertTrue(timedOutScan.retryFreshPlan)
        assertFalse(timedOutScan.allowEndTurn)
        assertEquals("live-scan-stale-retry-before-recovery", timedOutScan.reason)
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
