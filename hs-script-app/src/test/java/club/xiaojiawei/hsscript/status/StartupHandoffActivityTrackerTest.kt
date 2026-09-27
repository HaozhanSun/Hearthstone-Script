package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupHandoffActivityTrackerTest {

    @Test
    fun `fresh retries defer only the overdue processless startup watchdog`() {
        val tracker = StartupHandoffActivityTracker(activeWindowMs = 90_000L)
        val context = startupContext()

        tracker.recordAttempt(0L)
        assertTrue(tracker.shouldDeferNoProgress(context, 80_000L))
        assertFalse(tracker.shouldDeferNoProgress(context, 90_000L))

        tracker.recordAttempt(80_000L)
        assertTrue(tracker.shouldDeferNoProgress(context, 169_999L))
        assertFalse(tracker.shouldDeferNoProgress(context, 170_000L))
        assertFalse(tracker.shouldDeferNoProgress(context, 79_999L))
    }

    @Test
    fun `stopped startup retries return to the existing bounded watchdog`() {
        val tracker = StartupHandoffActivityTracker(activeWindowMs = 90_000L)
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 120_000L)
        val context = startupContext()
        tracker.recordAttempt(0L)

        assertTrue(tracker.shouldDeferNoProgress(context, 89_999L))
        assertFalse(tracker.shouldDeferNoProgress(context, 90_000L))
        assertEquals(
            NoProgressWatchdog.RecoveryAction.WAIT,
            watchdog.observe(startupSnapshot(now = 90_000L)).action,
        )
        assertEquals(
            NoProgressWatchdog.RecoveryAction.REBIND,
            watchdog.observe(startupSnapshot(now = 210_000L)).action,
        )
    }

    @Test
    fun `repeated retries may cross the watchdog threshold but have a total deferral cap`() {
        val tracker = StartupHandoffActivityTracker(
            activeWindowMs = 90_000L,
            maxContinuousActivityMs = 300_000L,
        )
        val context = startupContext()

        listOf(0L, 60_000L, 120_000L).forEach(tracker::recordAttempt)
        assertTrue(tracker.shouldDeferNoProgress(context, 150_000L))
        listOf(180_000L, 240_000L).forEach(tracker::recordAttempt)
        assertTrue(tracker.shouldDeferNoProgress(context, 299_999L))
        assertFalse(tracker.shouldDeferNoProgress(context, 300_000L))
        tracker.recordAttempt(300_000L)
        assertFalse(tracker.shouldDeferNoProgress(context, 300_001L))
    }

    @Test
    fun `heartbeat is rejected outside normal unpaused startup with no game evidence`() {
        val tracker = StartupHandoffActivityTracker()
        val eligible = startupContext()
        tracker.recordAttempt(1_000L)
        val rejected = listOf(
            eligible.copy(working = false),
            eligible.copy(paused = true),
            eligible.copy(automaticPause = true),
            eligible.copy(recoveryPending = true),
            eligible.copy(replaying = true),
            eligible.copy(inWar = true),
            eligible.copy(terminalState = true),
            eligible.copy(screen = NoProgressWatchdog.ScreenExpectation.ACTIVE_GAMEPLAY),
            eligible.copy(mode = "HUB"),
            eligible.copy(expectedMode = "HUB"),
            eligible.copy(gameProcessAlive = true),
            eligible.copy(powerLogPath = "run/Power.log"),
        )

        assertTrue(tracker.shouldDeferNoProgress(eligible, 2_000L))
        rejected.forEach { context ->
            assertFalse(tracker.shouldDeferNoProgress(context, 2_000L), "Unexpected deferral for $context")
        }
    }

    @Test
    fun `attempt signal alone does not mean game input was accepted`() {
        val tracker = StartupHandoffActivityTracker()
        tracker.recordAttempt(10L)

        assertTrue(tracker.shouldDeferNoProgress(startupContext(), 11L))
        assertFalse(
            tracker.shouldDeferNoProgress(startupContext().copy(gameProcessAlive = true), 11L),
        )
    }

    private fun startupContext() = StartupHandoffActivityTracker.Context(
        working = true,
        paused = false,
        automaticPause = false,
        recoveryPending = false,
        replaying = false,
        inWar = false,
        terminalState = false,
        screen = NoProgressWatchdog.ScreenExpectation.STARTUP,
        mode = "NONE",
        expectedMode = "STARTUP",
        gameProcessAlive = false,
        powerLogPath = null,
    )

    private fun startupSnapshot(now: Long) = NoProgressWatchdog.Snapshot(
        nowMs = now,
        mode = "NONE",
        expectedMode = "STARTUP",
        screen = NoProgressWatchdog.ScreenExpectation.STARTUP,
        processAlive = false,
        currentPid = null,
        boundPid = null,
        windowPresent = false,
        powerLogPath = null,
        boundPowerLogPath = null,
        powerLogPosition = Long.MIN_VALUE,
        powerLogLength = 0L,
        powerLogAgeMs = Long.MAX_VALUE,
    )
}
