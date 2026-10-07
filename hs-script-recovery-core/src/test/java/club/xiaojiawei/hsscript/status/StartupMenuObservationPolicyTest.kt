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
    fun `home quest overlay is an observable menu but never an action authorization`() {
        assertTrue(StartupMenuObservationPolicy.isObservedMenu("HOME_TASK_OVERLAY", 85))
        assertFalse(StartupMenuObservationPolicy.isObservedMenu("HOME_TASK_OVERLAY", 84))
        assertFalse(StartupMenuObservationPolicy.isObservedMenu("MATCHMAKING", 100))
        assertFalse(StartupMenuObservationPolicy.isObservedMenu("UNKNOWN", 100))
    }
}
