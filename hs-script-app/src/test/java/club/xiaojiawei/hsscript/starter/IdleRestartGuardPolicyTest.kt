package club.xiaojiawei.hsscript.starter

import club.xiaojiawei.hsscript.status.PauseStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class IdleRestartGuardPolicyTest {

    @Test
    fun `active matchmaking survives empty or stale Power log after idle limit`() {
        assertEquals(
            IdleRestartGuardPolicy.Decision.DEFER_ACTIVE_MATCHMAKING,
            IdleRestartGuardPolicy.evaluate(
                idleForMs = 11 * 60_000L,
                idleLimitMs = 10 * 60_000L,
                observedScreen = "MATCHMAKING",
                uncertainProbeAttempts = 0,
            ),
        )
    }

    @Test
    fun `genuinely idle confirmed menu still restarts after immediate revalidation`() {
        assertEquals(
            IdleRestartGuardPolicy.Decision.RESTART,
            IdleRestartGuardPolicy.evaluate(
                idleForMs = 11 * 60_000L,
                idleLimitMs = 10 * 60_000L,
                observedScreen = "HOME",
                uncertainProbeAttempts = 0,
                revalidatedScreen = "HOME",
            ),
        )
    }

    @Test
    fun `unknown or dialog screens never authorize restart after bounded probes`() {
        val first = IdleRestartGuardPolicy.evaluate(
            idleForMs = 11 * 60_000L,
            idleLimitMs = 10 * 60_000L,
            observedScreen = "UNKNOWN",
            uncertainProbeAttempts = 0,
        )
        val second = IdleRestartGuardPolicy.evaluate(
            idleForMs = 12 * 60_000L,
            idleLimitMs = 10 * 60_000L,
            observedScreen = "RECONNECT_FAILURE",
            uncertainProbeAttempts = 1,
        )
        val final = IdleRestartGuardPolicy.evaluate(
            idleForMs = 13 * 60_000L,
            idleLimitMs = 10 * 60_000L,
            observedScreen = null,
            uncertainProbeAttempts = 2,
        )
        assertEquals(IdleRestartGuardPolicy.Decision.DEFER_UNCERTAIN_SCREEN, first)
        assertEquals(IdleRestartGuardPolicy.Decision.DEFER_UNCERTAIN_SCREEN, second)
        assertEquals(IdleRestartGuardPolicy.Decision.DEFER_UNCERTAIN_SCREEN, final)
    }

    @Test
    fun `unknown followed by matchmaking remains deferred`() {
        assertEquals(
            IdleRestartGuardPolicy.Decision.DEFER_ACTIVE_MATCHMAKING,
            IdleRestartGuardPolicy.evaluate(
                idleForMs = 13 * 60_000L,
                idleLimitMs = 10 * 60_000L,
                observedScreen = "UNKNOWN",
                uncertainProbeAttempts = IdleRestartGuardPolicy.MAX_UNCERTAIN_PROBES,
                revalidatedScreen = "MATCHMAKING",
            ),
        )
    }

    @Test
    fun `stale or empty Power log does not matter when visual search is fresh`() {
        assertEquals(
            IdleRestartGuardPolicy.Decision.DEFER_ACTIVE_MATCHMAKING,
            IdleRestartGuardPolicy.evaluate(
                idleForMs = 60 * 60_000L,
                idleLimitMs = 10 * 60_000L,
                observedScreen = "MATCHMAKING",
                uncertainProbeAttempts = IdleRestartGuardPolicy.MAX_UNCERTAIN_PROBES,
                revalidatedScreen = "MATCHMAKING",
            ),
        )
    }

    @Test
    fun `home followed by unknown fails closed instead of restarting`() {
        assertEquals(
            IdleRestartGuardPolicy.Decision.DEFER_UNCERTAIN_SCREEN,
            IdleRestartGuardPolicy.evaluate(
                idleForMs = 11 * 60_000L,
                idleLimitMs = 10 * 60_000L,
                observedScreen = "HOME",
                uncertainProbeAttempts = 0,
                revalidatedScreen = "UNKNOWN",
            ),
        )
    }

    @Test
    fun `idle watchdog policy does not synthesize a pause event`() {
        val previousPause = PauseStatus.isPause
        val previousOrigin = PauseStatus.pauseOrigin
        try {
            PauseStatus.setManualPause(false)
            assertEquals(
                IdleRestartGuardPolicy.Decision.RESTART,
                IdleRestartGuardPolicy.evaluate(
                    idleForMs = 11 * 60_000L,
                    idleLimitMs = 10 * 60_000L,
                    observedScreen = "HOME",
                    uncertainProbeAttempts = 0,
                    revalidatedScreen = "HOME",
                ),
            )
            assertFalse(PauseStatus.isPause, "policy evaluation must not pause without F2")
            assertEquals(PauseStatus.Origin.NONE, PauseStatus.pauseOrigin)
        } finally {
            when (previousOrigin) {
                PauseStatus.Origin.MANUAL -> PauseStatus.setManualPause(previousPause)
                PauseStatus.Origin.AUTOMATIC -> PauseStatus.setAutomaticPause(previousPause)
                PauseStatus.Origin.NONE -> PauseStatus.setManualPause(false)
            }
        }
    }

    @Test
    fun `pause boundary and rank gate are not part of idle restart policy`() {
        assertEquals(
            IdleRestartGuardPolicy.Decision.NOT_IDLE,
            IdleRestartGuardPolicy.evaluate(
                idleForMs = 9 * 60_000L,
                idleLimitMs = 10 * 60_000L,
                observedScreen = "MATCHMAKING",
                uncertainProbeAttempts = 0,
            ),
        )
    }
}
