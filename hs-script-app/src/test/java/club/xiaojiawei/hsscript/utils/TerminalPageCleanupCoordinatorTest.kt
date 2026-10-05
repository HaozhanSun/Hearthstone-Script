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
    fun `dispatch acceptance does not reset global budget or confirm a still-visible result`() {
        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = requireNotNull(coordinator.begin().ticket)

        var resultVisible: Boolean? = true
        var dispatchAcceptedCount = 0
        repeat(2) { index ->
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
            dispatchAcceptedCount += 1 // Simulate Robot/SENDINPUT returning success.
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
        coordinator.hold(ticket)

        assertEquals(2, dispatchAcceptedCount)
        assertEquals(TerminalPageCleanupCoordinator.State.HELD, coordinator.snapshot().state)
        assertEquals(2, coordinator.snapshot().inputs)
        assertEquals(
            TerminalPageCleanupCoordinator.BeginState.HELD,
            coordinator.begin().state,
            "later recovery callbacks must not reset the episode budget",
        )
        assertNull(coordinator.reserveInput(ticket))

        // Only a fresh authorized observation of the destination releases the hold.
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
        assertNull(coordinator.reserveInput(resumedTicket))
        assertEquals(TerminalPageCleanupCoordinator.State.HELD, coordinator.snapshot().state)
        assertEquals(2, coordinator.snapshot().inputs)
    }

    @Test
    fun `unconfirmed observation loop exhausts bounded probes and stays held`() {
        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = coordinator.begin().ticket!!
        repeat(TerminalPageCleanupCoordinator.DEFAULT_MAX_PROBES) {
            assertNotNull(coordinator.nextProbe(ticket))
        }

        assertNull(coordinator.nextProbe(ticket))
        assertEquals(TerminalPageCleanupCoordinator.State.HELD, coordinator.snapshot().state)
        assertEquals(TerminalPageCleanupCoordinator.BeginState.HELD, coordinator.begin().state)
        assertEquals(0, coordinator.snapshot().inputs)
    }
}
