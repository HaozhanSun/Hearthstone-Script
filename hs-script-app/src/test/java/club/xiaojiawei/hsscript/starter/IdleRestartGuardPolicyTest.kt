package club.xiaojiawei.hsscript.starter

import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `genuinely idle confirmed menu still restarts`() {
        assertEquals(
            IdleRestartGuardPolicy.Decision.RESTART,
            IdleRestartGuardPolicy.evaluate(
                idleForMs = 11 * 60_000L,
                idleLimitMs = 10 * 60_000L,
                observedScreen = "HOME",
                uncertainProbeAttempts = 0,
            ),
        )
    }

    @Test
    fun `unknown or dialog screens have bounded recovery before restart`() {
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
        assertEquals(IdleRestartGuardPolicy.Decision.RESTART, final)
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
