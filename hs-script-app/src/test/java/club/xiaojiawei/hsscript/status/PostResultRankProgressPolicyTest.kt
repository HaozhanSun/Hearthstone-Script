package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.utils.TerminalPageCleanupCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PostResultRankProgressPolicyTest {
    @Test
    fun `result to rank progression to destination is a three-state cleanup lifecycle`() {
        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = requireNotNull(coordinator.begin().ticket)
        val duplicateRecovery = coordinator.begin()
        assertEquals(TerminalPageCleanupCoordinator.BeginState.ALREADY_RUNNING, duplicateRecovery.state)
        assertEquals(ticket.generation, duplicateRecovery.ticket?.generation)
        val resultProbe = requireNotNull(coordinator.nextProbe(ticket))

        // The first result-page input is admitted by the existing result policy.
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = resultProbe,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = coordinator.snapshot().inputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
        )
        assertEquals(1, coordinator.reserveInput(ticket))
        val dispatchedResultContinue = true // SendInput accepted; not proof of a UI transition.
        assertTrue(dispatchedResultContinue)

        val rankProbe = requireNotNull(coordinator.nextProbe(ticket))
        val rankAction = PostResultRankProgressPolicy.decide(
            rankProgressVisible = true,
            terminalCleanupAuthorized = true,
            captureAuthorized = true,
            rankProgressInputAttempts = coordinator.snapshot().rankProgressInputs,
        )
        assertEquals(PostResultRankProgressPolicy.Action.CONTINUE, rankAction)
        assertEquals(2, coordinator.reserveRankProgressInput(ticket, PostResultRankProgressPolicy.MAX_CONTINUE_INPUTS))
        assertEquals(
            TerminalPageCleanupCoordinator.State.RUNNING,
            coordinator.snapshot().state,
            "the rank progression page is intermediate, not a destination",
        )
        assertTrue(rankProbe > resultProbe)

        val destinationProbe = requireNotNull(coordinator.nextProbe(ticket))
        val destinationDecision = ResultPageDismissalPolicy.decide(
            inWar = false,
            resultPageVisible = ScreenStateRecovery.resultVisibilityForTest("HOME", 90),
            attempt = destinationProbe,
            maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
            clickAttempts = coordinator.snapshot().inputs,
            terminalCleanupAuthorized = true,
            captureAuthorized = true,
        )
        assertEquals(ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED, destinationDecision)
        assertTrue(coordinator.confirmDestination(ticket), "only the fresh Home/queue destination completes the episode")
        assertEquals(TerminalPageCleanupCoordinator.State.COMPLETED, coordinator.snapshot().state)
        assertEquals(false, ScreenStateRecovery.resultVisibilityForTest("MATCHMAKING", 90))
    }

    @Test
    fun `repeated unchanged rank page is retried only twice then held as a failed bounded episode`() {
        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = requireNotNull(coordinator.begin().ticket)
        requireNotNull(coordinator.nextProbe(ticket))
        repeat(PostResultRankProgressPolicy.MAX_CONTINUE_INPUTS) { attempt ->
            assertEquals(
                PostResultRankProgressPolicy.Action.CONTINUE,
                PostResultRankProgressPolicy.decide(
                    rankProgressVisible = true,
                    terminalCleanupAuthorized = true,
                    captureAuthorized = true,
                    rankProgressInputAttempts = coordinator.snapshot().rankProgressInputs,
                ),
            )
            assertEquals(
                if (attempt == 0) PostResultRankProgressPolicy.Input.CENTER_CLICK
                else PostResultRankProgressPolicy.Input.KEYBOARD_ENTER,
                PostResultRankProgressPolicy.inputForAttempt(attempt + 1),
            )
            assertNotNull(coordinator.reserveRankProgressInput(ticket, PostResultRankProgressPolicy.MAX_CONTINUE_INPUTS))
            assertEquals(attempt + 1, coordinator.snapshot().rankProgressInputs)
        }
        assertEquals(
            PostResultRankProgressPolicy.Action.INPUT_BUDGET_EXHAUSTED,
            PostResultRankProgressPolicy.decide(
                rankProgressVisible = true,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                rankProgressInputAttempts = coordinator.snapshot().rankProgressInputs,
            ),
        )
        assertEquals(null, PostResultRankProgressPolicy.inputForAttempt(3))
        assertTrue(coordinator.fail(ticket, "rank-progress-input-budget-exhausted"))
        assertEquals(TerminalPageCleanupCoordinator.State.FAILED, coordinator.snapshot().state)
    }

    @Test
    fun `rank continue requires live terminal proof and authorized current capture`() {
        assertEquals(
            PostResultRankProgressPolicy.Action.WAIT_FOR_AUTHORIZED_CAPTURE,
            PostResultRankProgressPolicy.decide(true, false, true, 1),
        )
        assertEquals(
            PostResultRankProgressPolicy.Action.WAIT_FOR_AUTHORIZED_CAPTURE,
            PostResultRankProgressPolicy.decide(true, true, false, 1),
        )
        assertEquals(
            PostResultRankProgressPolicy.Action.INPUT_BUDGET_EXHAUSTED,
            PostResultRankProgressPolicy.decide(true, true, true, PostResultRankProgressPolicy.MAX_CONTINUE_INPUTS),
        )
        assertEquals(
            PostResultRankProgressPolicy.Action.NOT_APPLICABLE,
            PostResultRankProgressPolicy.decide(false, true, true, 1),
        )
    }
}
