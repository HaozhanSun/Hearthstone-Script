package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.status.ResultPageDismissalPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalPageCleanupCoordinatorTest {
    @Test
    fun `concurrent game over and recovery callbacks share one cleanup flight`() {
        val coordinator = TerminalPageCleanupCoordinator()
        val executor = Executors.newFixedThreadPool(16)
        val ready = CountDownLatch(16)
        val start = CountDownLatch(1)
        try {
            val callbacks = (0 until 16).map {
                executor.submit<TerminalPageCleanupCoordinator.BeginResult> {
                    ready.countDown()
                    start.await(2, TimeUnit.SECONDS)
                    coordinator.begin()
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()

            val results = callbacks.map { it.get(2, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.state == TerminalPageCleanupCoordinator.BeginState.STARTED })
            assertEquals(15, results.count { it.state == TerminalPageCleanupCoordinator.BeginState.ALREADY_RUNNING })
            assertEquals(1, results.mapNotNull { it.ticket }.distinct().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `dispatch acceptance does not reset the 16-input episode budget or confirm a still-visible result`() {
        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = requireNotNull(coordinator.begin().ticket)

        var resultVisible: Boolean? = true
        repeat(TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS) { index ->
            assertEquals(TerminalPageCleanupCoordinator.BeginState.ALREADY_RUNNING, coordinator.begin().state)
            val probe = coordinator.nextProbe(ticket)
            assertNotNull(probe)
            val before = coordinator.snapshot()
            assertEquals(
                ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
                ResultPageDismissalPolicy.decide(
                    inWar = false,
                    resultPageVisible = resultVisible,
                    attempt = probe!!,
                    maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                    clickAttempts = before.inputs,
                    terminalCleanupAuthorized = true,
                    captureAuthorized = true,
                ),
            )

            val reserved = coordinator.reserveInput(ticket)
            assertEquals(index + 1, reserved)
            // Simulate Robot/SENDINPUT returning success.
            // The independent UI observation deliberately remains positive.
            assertEquals(true, resultVisible)
            assertEquals(TerminalPageCleanupCoordinator.State.RUNNING, coordinator.snapshot().state)
        }

        val exhaustedProbe = coordinator.nextProbe(ticket)
        assertNotNull(exhaustedProbe)
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = resultVisible,
                attempt = exhaustedProbe!!,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = coordinator.snapshot().inputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
        )
        assertTrue(coordinator.fail(ticket, "result-visible-input-budget-exhausted"))
        assertEquals(TerminalPageCleanupCoordinator.State.FAILED, coordinator.snapshot().state)
        assertEquals("result-visible-input-budget-exhausted", coordinator.snapshot().failureReason)
        assertEquals(TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS, coordinator.snapshot().inputs)
        assertEquals(
            TerminalPageCleanupCoordinator.BeginState.FAILED,
            coordinator.begin().state,
            "later recovery callbacks must not silently restart an exhausted episode",
        )
        assertNull(coordinator.reserveInput(ticket))

        // Only a fresh authorized observation of the destination can still
        // reconcile the completed screen after an explicit safe failure.
        resultVisible = false
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = resultVisible,
                attempt = 4,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = coordinator.snapshot().inputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
        )
        assertTrue(coordinator.confirmDestination(ticket))
        assertEquals(TerminalPageCleanupCoordinator.State.COMPLETED, coordinator.snapshot().state)
    }

    @Test
    fun `new game invalidates old callbacks and starts a new bounded episode`() {
        val coordinator = TerminalPageCleanupCoordinator()
        val oldTicket = coordinator.begin().ticket!!
        assertEquals(1, coordinator.reserveInput(oldTicket))

        coordinator.resetForNewGame()
        assertNull(coordinator.reserveInput(oldTicket))
        assertEquals(0, coordinator.snapshot().inputs)

        val newTicket = coordinator.begin().ticket
        assertNotNull(newTicket)
        assertTrue(newTicket!!.generation > oldTicket.generation)
        assertEquals(1, coordinator.reserveInput(newTicket))
    }

    @Test
    fun `pause can release the worker but cannot renew the shared input budget`() {
        val coordinator = TerminalPageCleanupCoordinator()
        val firstTicket = coordinator.begin().ticket!!
        assertEquals(1, coordinator.reserveInput(firstTicket))
        assertTrue(coordinator.interrupt(firstTicket))
        assertEquals(false, coordinator.confirmDestination(firstTicket), "an interrupted stale observation cannot complete an idle episode")

        val resumedTicket = coordinator.begin().ticket!!
        assertEquals(1, coordinator.snapshot().inputs)
        assertEquals(false, coordinator.confirmDestination(firstTicket), "a stale worker cannot complete a resumed episode")
        assertEquals(TerminalPageCleanupCoordinator.State.RUNNING, coordinator.snapshot().state)
        assertEquals(2, coordinator.reserveInput(resumedTicket))
        repeat(TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS - 2) { index ->
            assertEquals(index + 3, coordinator.reserveInput(resumedTicket))
        }
        assertNull(coordinator.reserveInput(resumedTicket))
        assertEquals(TerminalPageCleanupCoordinator.State.FAILED, coordinator.snapshot().state)
        assertEquals(TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS, coordinator.snapshot().inputs)
    }

    @Test
    fun `unconfirmed observation loop ends in explicit failed state`() {
        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = coordinator.begin().ticket!!
        repeat(TerminalPageCleanupCoordinator.DEFAULT_MAX_PROBES) {
            assertNotNull(coordinator.nextProbe(ticket))
        }

        assertNull(coordinator.nextProbe(ticket))
        assertEquals(TerminalPageCleanupCoordinator.State.FAILED, coordinator.snapshot().state)
        assertEquals("probe-budget-exhausted", coordinator.snapshot().failureReason)
        assertEquals(TerminalPageCleanupCoordinator.BeginState.FAILED, coordinator.begin().state)
        assertEquals(0, coordinator.snapshot().inputs)
    }

    @Test
    fun `single flight owner expires by elapsed time even when probes are slow`() {
        var nowMillis = 10_000L
        val coordinator = TerminalPageCleanupCoordinator(
            maxDurationMillis = 120_000L,
            monotonicTimeMillis = { nowMillis },
        )
        val owner = coordinator.begin().ticket!!
        assertEquals(1, coordinator.nextProbe(owner))
        assertEquals(1, coordinator.reserveInput(owner))

        nowMillis += 119_999L
        assertEquals(TerminalPageCleanupCoordinator.BeginState.ALREADY_RUNNING, coordinator.begin().state)
        nowMillis += 1L

        val expired = coordinator.begin()
        assertEquals(TerminalPageCleanupCoordinator.BeginState.FAILED, expired.state)
        assertEquals(TerminalPageCleanupCoordinator.State.FAILED, coordinator.snapshot().state)
        assertEquals("episode-deadline-exceeded", coordinator.snapshot().failureReason)
        assertNull(coordinator.nextProbe(owner), "the expired owner cannot dispatch another UI action")
        assertEquals(1, coordinator.snapshot().inputs)
        assertNull(
            coordinator.rearmAfterNoInputDeadline(expired.ticket!!, freshCaptureAuthorized = true, resultPageVisible = true),
            "a deadline after an input must never renew the dispatch budget",
        )
    }

    @Test
    fun `zero-input deadline permits only one fresh authorized positive-result rearm and keeps budgets cumulative`() {
        var nowMillis = 50_000L
        val coordinator = TerminalPageCleanupCoordinator(
            maxDurationMillis = 100L,
            monotonicTimeMillis = { nowMillis },
        )
        val first = coordinator.begin().ticket!!
        repeat(3) { assertNotNull(coordinator.nextProbe(first)) }
        nowMillis += 100L
        val expired = coordinator.begin()
        assertEquals(TerminalPageCleanupCoordinator.BeginState.FAILED, expired.state)
        assertEquals("episode-deadline-exceeded", coordinator.snapshot().failureReason)
        assertEquals(0, coordinator.snapshot().inputs)

        assertNull(
            coordinator.rearmAfterNoInputDeadline(expired.ticket!!, freshCaptureAuthorized = false, resultPageVisible = true),
            "unverified pixels cannot reopen an expired episode",
        )
        assertNull(
            coordinator.rearmAfterNoInputDeadline(expired.ticket!!, freshCaptureAuthorized = true, resultPageVisible = null),
            "UNKNOWN is not a result-page authorization",
        )
        val rearmed = requireNotNull(
            coordinator.rearmAfterNoInputDeadline(expired.ticket!!, freshCaptureAuthorized = true, resultPageVisible = true),
        )
        assertEquals(TerminalPageCleanupCoordinator.State.RUNNING, coordinator.snapshot().state)
        assertEquals(3, coordinator.snapshot().probes, "probe budget is cumulative across the bounded rearm")
        assertEquals(0, coordinator.snapshot().inputs)
        assertEquals(1, coordinator.snapshot().failedEpisodeRearms)
        assertNull(coordinator.nextProbe(first), "the pre-expiry worker loses its single-flight lease")
        assertEquals(4, coordinator.nextProbe(rearmed))
        assertEquals(1, coordinator.reserveInput(rearmed), "dispatch remains explicitly budgeted after fresh result evidence")

        assertTrue(coordinator.fail(rearmed, "episode-deadline-exceeded"))
        val failedAgain = coordinator.begin()
        assertNull(
            coordinator.rearmAfterNoInputDeadline(failedAgain.ticket!!, freshCaptureAuthorized = true, resultPageVisible = true),
            "one terminal generation may be rearmed at most once",
        )
    }
}
