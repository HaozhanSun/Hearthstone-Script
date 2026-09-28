package club.xiaojiawei.hsscript.strategy

import club.xiaojiawei.hsscript.status.PauseStatus
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
    fun `fourth replan is rejected and falls back to end turn when live action remains`() {
        val exhausted = MctsTurnEndReplanPolicy.decide(completedReplans = 3, liveActionable = true)

        assertFalse(exhausted.shouldReplan)
        assertEquals(4, exhausted.planningPass)
        assertEquals(4, exhausted.attempt)
        assertTrue(exhausted.freshLiveRescanRequired)
        assertFalse(exhausted.reusePreviousPlan)
        assertTrue(exhausted.allowEndTurnWhenExhausted)
        assertEquals("live-state-actionable-after-replan-budget-exhausted", exhausted.reason)
    }

    @Test
    fun `exhausted live-action fallback dispatches once without creating an automatic pause`() {
        val exhausted = MctsTurnEndReplanPolicy.decide(completedReplans = 3, liveActionable = true)
        var dispatches = 0

        val result = MctsTurnEndReplanPolicy.dispatchExhaustionFallback(
            decision = exhausted,
            paused = false,
            turnActive = true,
        ) { dispatches++ }

        assertEquals(MctsTurnEndReplanPolicy.ExhaustionFallbackResult.DISPATCHED, result)
        assertEquals(1, dispatches)
        assertTrue(exhausted.allowEndTurnWhenExhausted)
    }

    @Test
    fun `manual pause or ended turn blocks exhausted fallback without dispatch`() {
        val exhausted = MctsTurnEndReplanPolicy.decide(completedReplans = 3, liveActionable = true)
        var dispatches = 0
        val previousPause = PauseStatus.isPause
        val previousOrigin = PauseStatus.pauseOrigin

        try {
            PauseStatus.setManualPause(true)
            val paused = MctsTurnEndReplanPolicy.dispatchExhaustionFallback(
                decision = exhausted,
                paused = PauseStatus.isPause,
                turnActive = true,
            ) { dispatches++ }
            val turnEnded = MctsTurnEndReplanPolicy.dispatchExhaustionFallback(
                decision = exhausted,
                paused = false,
                turnActive = false,
            ) { dispatches++ }

            assertEquals(MctsTurnEndReplanPolicy.ExhaustionFallbackResult.PAUSED, paused)
            assertEquals(MctsTurnEndReplanPolicy.ExhaustionFallbackResult.TURN_NOT_ACTIVE, turnEnded)
            assertEquals(0, dispatches)
            assertTrue(PauseStatus.isPause)
            assertEquals(PauseStatus.Origin.MANUAL, PauseStatus.pauseOrigin)
        } finally {
            when (previousOrigin) {
                PauseStatus.Origin.MANUAL -> PauseStatus.setManualPause(previousPause)
                PauseStatus.Origin.AUTOMATIC -> PauseStatus.setAutomaticPause(previousPause)
                PauseStatus.Origin.NONE -> PauseStatus.setManualPause(previousPause)
            }
        }
    }

    @Test
    fun `end-turn retry loop stops on pause or turn transition and never exceeds its bound`() {
        var pause = false
        var turnActive = true
        var attempts = 0
        val untilPause = MctsTurnEndReplanPolicy.runBoundedEndTurnAttempts(
            shouldContinue = { turnActive && !pause },
        ) { attempt ->
            attempts = attempt
            if (attempt == 2) pause = true
        }
        assertEquals(2, untilPause)
        assertEquals(2, attempts)

        pause = false
        attempts = 0
        val untilTurnTransition = MctsTurnEndReplanPolicy.runBoundedEndTurnAttempts(
            shouldContinue = { turnActive && !pause },
        ) { attempt ->
            attempts = attempt
            if (attempt == 5) turnActive = false
        }
        assertEquals(5, untilTurnTransition)

        turnActive = true
        attempts = 0
        val bounded = MctsTurnEndReplanPolicy.runBoundedEndTurnAttempts(
            shouldContinue = { turnActive && !pause },
        ) { attempt -> attempts = attempt }
        assertEquals(MctsTurnEndReplanPolicy.MAX_END_TURN_DISPATCH_ATTEMPTS, bounded)
        assertEquals(MctsTurnEndReplanPolicy.MAX_END_TURN_DISPATCH_ATTEMPTS, attempts)
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
