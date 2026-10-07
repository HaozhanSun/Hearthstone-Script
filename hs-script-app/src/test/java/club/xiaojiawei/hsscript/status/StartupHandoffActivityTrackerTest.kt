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
            NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED,
            watchdog.observe(startupSnapshot(now = 90_000L)).action,
        )
        assertEquals(
            NoProgressWatchdog.RecoveryAction.REBIND,
            watchdog.observe(startupSnapshot(now = 210_000L)).action,
        )
        assertEquals(
            NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED,
            watchdog.observe(startupSnapshot(now = 329_999L)).action,
        )
        assertEquals(
            NoProgressWatchdog.RecoveryAction.RESTART,
            watchdog.observe(startupSnapshot(now = 330_000L)).action,
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

    @Test
    fun `unbound startup handoff yields to verified window passive menu observation without input`() {
        val tracker = StartupHandoffActivityTracker(activeWindowMs = 90_000L)
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 1_000L)
        tracker.recordAttempt(10_000L)

        val handoffContext = startupContext()
        assertTrue(tracker.shouldDeferNoProgress(handoffContext, 11_000L))
        val missingSessionLog = watchdog.observe(startupSnapshot(now = 11_000L))
        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, missingSessionLog.action)

        // Once the actual game process/window is verified, the handoff deferral
        // no longer applies. An unbound/empty session log remains non-actionable,
        // while the independent screen path is permitted to observe only.
        val liveWindowContext = handoffContext.copy(
            gameProcessAlive = true,
            powerLogPath = "run/Power.log",
        )
        assertFalse(tracker.shouldDeferNoProgress(liveWindowContext, 11_001L))
        val unboundLiveLog = watchdog.observe(
            startupSnapshot(now = 11_001L).copy(
                processAlive = true,
                currentPid = 97212L,
                windowPresent = true,
                powerLogPath = "run/Power.log",
                powerLogPosition = Long.MIN_VALUE,
                powerLogLength = 0L,
                powerLogAgeMs = Long.MAX_VALUE,
            ),
        )
        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, unboundLiveLog.action)
        assertEquals("power-log-unbound-or-unusable", unboundLiveLog.reason)

        val max = StartupMenuObservationPolicy.MAX_PASSIVE_REOBSERVATIONS
        val overlayDismissDispatches = java.util.concurrent.atomic.AtomicInteger()
        val queueDispatches = java.util.concurrent.atomic.AtomicInteger()
        val gameplayDispatches = java.util.concurrent.atomic.AtomicInteger()
        val unknownFrame = StartupMenuObservationPolicy.decide(
            currentSessionReady = false,
            startupObservationAuthorized = StartupMenuObservationPolicy.isAuthorized(
                gameWindowVerified = true,
                currentPid = 97212L,
                windowPid = 97212L,
                mode = "STARTUP",
                working = true,
                paused = false,
                activeMatch = false,
                terminal = false,
            ),
            observedMenu = StartupMenuObservationPolicy.isObservedMenu("UNKNOWN", 0),
            activeMatch = false,
            terminal = false,
            completedObservations = 0,
            maxObservations = max,
        )
        assertEquals(StartupMenuObservationPolicy.Decision.OBSERVE_STARTUP_MENU, unknownFrame)
        assertEquals(0, overlayDismissDispatches.get(), "UNKNOWN/loading cannot authorize overlay input")

        val questOverlay = StartupMenuObservationPolicy.decide(
            currentSessionReady = false,
            startupObservationAuthorized = true,
            observedMenu = StartupMenuObservationPolicy.isObservedMenu("HOME_TASK_OVERLAY", 95),
            activeMatch = false,
            terminal = false,
            completedObservations = 1,
            maxObservations = max,
        )
        assertEquals(StartupMenuObservationPolicy.Decision.WAIT_EXPECTED_MENU, questOverlay)

        fun progression(screen: VerifiedStartupMenuProgression.Screen, dismissals: Int = 0) =
            VerifiedStartupMenuProgression.decide(
                VerifiedStartupMenuProgression.Evidence(
                    screen = screen,
                    confidence = 95,
                    pid = 97212L,
                    currentPid = 97212L,
                    windowPid = 97212L,
                    foregroundAndPixelsVerified = true,
                    configuredTournament = true,
                    working = true,
                    manuallyPaused = false,
                    currentSessionPowerLogReady = false,
                    activeMatch = PowerLogActiveMatchProbe.State.NO_MATCH,
                    priorAuthoritativeLineage = false,
                    overlayDismissals = dismissals,
                    processLineageVerified = true,
                ),
            )
        val dismissal = progression(VerifiedStartupMenuProgression.Screen.HOME_TASK_OVERLAY)
        assertEquals(VerifiedStartupMenuProgression.Action.DISMISS_OVERLAY, dismissal)
        if (dismissal == VerifiedStartupMenuProgression.Action.DISMISS_OVERLAY) {
            overlayDismissDispatches.incrementAndGet()
        }
        assertEquals(1, overlayDismissDispatches.get(), "trusted overlay permits exactly one dismissal attempt")
        assertEquals(
            VerifiedStartupMenuProgression.Action.WAIT,
            progression(VerifiedStartupMenuProgression.Screen.HOME_TASK_OVERLAY, overlayDismissDispatches.get()),
            "a fresh overlay frame after the attempt cannot trigger another click",
        )
        assertEquals(
            VerifiedStartupMenuProgression.Action.ENTER_HUB,
            progression(VerifiedStartupMenuProgression.Screen.HOME, overlayDismissDispatches.get()),
            "fresh HOME evidence can hand off to HUB after dismissal",
        )
        assertEquals(0, queueDispatches.get(), "startup menu proof is not queue authority")
        assertEquals(0, gameplayDispatches.get(), "startup menu proof is not gameplay authority")

        assertEquals(
            StartupMenuObservationPolicy.Decision.BOUNDED_SAFE_PAUSE,
            StartupMenuObservationPolicy.decide(false, true, false, false, false, max, max),
        )
        assertEquals(1, overlayDismissDispatches.get(), "bounded timeout cannot add another overlay dispatch")
        assertEquals(0, queueDispatches.get())
        assertEquals(0, gameplayDispatches.get())
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
