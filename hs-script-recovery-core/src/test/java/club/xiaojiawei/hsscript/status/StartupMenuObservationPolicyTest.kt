package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupMenuObservationPolicyTest {
    @Test
    fun `pre-log menu observation requires live matching startup authority and never grants actions`() {
        val authorized = StartupMenuObservationPolicy.isAuthorized(
            gameWindowVerified = true,
            currentPid = 97212L,
            windowPid = 97212L,
            mode = "NONE",
            working = true,
            paused = false,
            activeMatch = false,
            terminal = false,
        )
        assertTrue(authorized)
        assertFalse(StartupMenuObservationPolicy.isAuthorized(
            gameWindowVerified = true,
            currentPid = 97212L,
            windowPid = 777L,
            mode = "NONE",
            working = true,
            paused = false,
            activeMatch = false,
            terminal = false,
        ))
        assertFalse(StartupMenuObservationPolicy.isAuthorized(
            gameWindowVerified = true,
            currentPid = 97212L,
            windowPid = 97212L,
            mode = "GAMEPLAY",
            working = true,
            paused = false,
            activeMatch = false,
            terminal = false,
        ))
        assertFalse(StartupMenuObservationPolicy.isAuthorized(
            gameWindowVerified = true,
            currentPid = 97212L,
            windowPid = 97212L,
            mode = "TOURNAMENT",
            working = true,
            paused = false,
            activeMatch = false,
            terminal = false,
        ), "tournament keeps its pre-existing screen-classification route")

        assertEquals(
            StartupMenuObservationPolicy.Decision.OBSERVE_STARTUP_MENU,
            StartupMenuObservationPolicy.decide(false, authorized, false, false, false, 0, 2),
        )
        assertEquals(
            StartupMenuObservationPolicy.Decision.WAIT_EXPECTED_MENU,
            StartupMenuObservationPolicy.decide(false, authorized, true, false, false, 1, 2),
        )
        assertEquals(
            StartupMenuObservationPolicy.Decision.BOUNDED_SAFE_PAUSE,
            StartupMenuObservationPolicy.decide(false, authorized, false, false, false, 2, 2),
        )
        assertEquals(
            StartupMenuObservationPolicy.Decision.WAIT_FOR_TERMINAL_AUTHORITY,
            StartupMenuObservationPolicy.decide(false, authorized, false, true, false, 0, 2),
        )
        assertEquals(
            StartupMenuObservationPolicy.Decision.AUTHORITATIVE_SESSION_READY,
            StartupMenuObservationPolicy.decide(true, false, false, false, false, 2, 2),
            "a fresh current-session log restores the ordinary authoritative route",
        )
    }

    @Test
    fun `startup observation cooldown bounds recapture frequency`() {
        val nextAllowedAt = StartupMenuObservationPolicy.nextCaptureAt(1_000L)
        assertEquals(StartupMenuObservationPolicy.CAPTURE_COOLDOWN_MS + 1_000L, nextAllowedAt)
        assertFalse(StartupMenuObservationPolicy.isCaptureDue(nextAllowedAt - 1L, nextAllowedAt))
        assertTrue(StartupMenuObservationPolicy.isCaptureDue(nextAllowedAt, nextAllowedAt))
    }

    @Test
    fun `slow OCR extends observation deadline instead of consuming retry window`() {
        val initialDeadline = 60_000L
        val afterFirstSlowRead = StartupMenuObservationPolicy.extendDeadlineForObservation(
            deadlineMs = initialDeadline,
            startedAtMs = 10_000L,
            completedAtMs = 60_000L,
        )
        assertEquals(110_000L, afterFirstSlowRead)
        assertTrue(60_000L < afterFirstSlowRead, "one 50s OCR must not exhaust the 60s observation window")
        assertEquals(
            110_000L,
            StartupMenuObservationPolicy.extendDeadlineForObservation(afterFirstSlowRead, 60_000L, 60_000L),
            "zero-latency observations leave the deadline unchanged",
        )
    }

    @Test
    fun `home quest overlay is an observable menu but never an action authorization`() {
        assertTrue(StartupMenuObservationPolicy.isObservedMenu("HOME_TASK_OVERLAY", 85))
        assertFalse(StartupMenuObservationPolicy.isObservedMenu("HOME_TASK_OVERLAY", 84))
        assertFalse(StartupMenuObservationPolicy.isObservedMenu("MATCHMAKING", 100))
        assertFalse(StartupMenuObservationPolicy.isObservedMenu("UNKNOWN", 100))
    }

    @Test
    fun `unknown startup frames are passively retried then fail closed`() {
        val max = StartupMenuObservationPolicy.MAX_PASSIVE_REOBSERVATIONS
        repeat(max) { completed ->
            assertEquals(
                StartupMenuObservationPolicy.Decision.OBSERVE_STARTUP_MENU,
                StartupMenuObservationPolicy.decide(
                    currentSessionReady = false,
                    startupObservationAuthorized = true,
                    observedMenu = false,
                    activeMatch = false,
                    terminal = false,
                    completedObservations = completed,
                    maxObservations = max,
                ),
            )
        }
        assertEquals(
            StartupMenuObservationPolicy.Decision.BOUNDED_SAFE_PAUSE,
            StartupMenuObservationPolicy.decide(false, true, false, false, false, max, max),
        )
        assertEquals(
            StartupMenuObservationPolicy.Decision.WAIT_FOR_TERMINAL_AUTHORITY,
            StartupMenuObservationPolicy.decide(false, true, false, true, false, max, max),
        )
    }
}
