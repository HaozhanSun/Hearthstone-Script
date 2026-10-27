package club.xiaojiawei.hsscript.starter

import club.xiaojiawei.hsscript.status.PauseStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `unknown screens alert while recognized reconnect screens restart only after bounded probes`() {
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
        val finalReconnect = IdleRestartGuardPolicy.evaluate(
            idleForMs = 13 * 60_000L,
            idleLimitMs = 10 * 60_000L,
            observedScreen = "RECONNECT_FAILURE",
            uncertainProbeAttempts = IdleRestartGuardPolicy.MAX_UNCERTAIN_PROBES,
        )
        val finalUnknown = IdleRestartGuardPolicy.evaluate(
            idleForMs = 13 * 60_000L,
            idleLimitMs = 10 * 60_000L,
            observedScreen = null,
            uncertainProbeAttempts = IdleRestartGuardPolicy.MAX_UNCERTAIN_PROBES,
        )
        assertEquals(IdleRestartGuardPolicy.Decision.DEFER_UNCERTAIN_SCREEN, first)
        assertEquals(IdleRestartGuardPolicy.Decision.DEFER_UNCERTAIN_SCREEN, second)
        assertEquals(IdleRestartGuardPolicy.Decision.RESTART_UNCERTAIN_SCREEN, finalReconnect)
        assertEquals(IdleRestartGuardPolicy.Decision.ALERT_UNCERTAIN_SCREEN, finalUnknown)
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
    fun `unknown observation below the exhausted probe threshold remains non-destructive`() {
        assertEquals(
            IdleRestartGuardPolicy.Decision.DEFER_UNCERTAIN_SCREEN,
            IdleRestartGuardPolicy.evaluate(
                idleForMs = 12 * 60_000L,
                idleLimitMs = 10 * 60_000L,
                observedScreen = "UNKNOWN",
                uncertainProbeAttempts = IdleRestartGuardPolicy.MAX_UNCERTAIN_PROBES - 1,
            ),
        )
    }

    @Test
    fun `uncertain screen restart is fenced by active game and terminal state`() {
        assertTrue(IdleRestartGuardPolicy.mayRestartUncertainScreen(activeGame = false, terminal = false))
        assertFalse(IdleRestartGuardPolicy.mayRestartUncertainScreen(activeGame = true, terminal = false))
        assertFalse(IdleRestartGuardPolicy.mayRestartUncertainScreen(activeGame = false, terminal = true))
    }

    @Test
    fun `stable server unavailable page restarts only after same hwnd and pid with no session`() {
        val exactPage = IdleRestartGuardPolicy.ScreenIdentity("SERVER_UNAVAILABLE", 42, "hwnd-7")

        assertEquals(
            IdleRestartGuardPolicy.ServerUnavailableDecision.WAIT_FOR_STABILITY,
            IdleRestartGuardPolicy.evaluateServerUnavailable(
                first = exactPage,
                current = exactPage,
                stableForMs = IdleRestartGuardPolicy.SERVER_UNAVAILABLE_STABILITY_MS - 1,
                currentSessionReady = false,
            ),
        )
        assertEquals(
            IdleRestartGuardPolicy.ServerUnavailableDecision.RESTART_CLIENT,
            IdleRestartGuardPolicy.evaluateServerUnavailable(
                first = exactPage,
                current = exactPage,
                stableForMs = IdleRestartGuardPolicy.SERVER_UNAVAILABLE_STABILITY_MS,
                currentSessionReady = false,
            ),
        )
        assertEquals(
            IdleRestartGuardPolicy.ServerUnavailableDecision.WAIT_FOR_STABILITY,
            IdleRestartGuardPolicy.evaluateServerUnavailable(
                first = exactPage,
                current = exactPage,
                stableForMs = 120_000L,
                currentSessionReady = false,
                nowMs = 200_000L,
                lastRestartAtMs = 1L,
            ),
        )
    }

    @Test
    fun `server unavailable restart defers on changed client identity session or matchmaking`() {
        val original = IdleRestartGuardPolicy.ScreenIdentity("SERVER_UNAVAILABLE", 42, "hwnd-7")
        assertEquals(
            IdleRestartGuardPolicy.ServerUnavailableDecision.WAIT_FOR_STABILITY,
            IdleRestartGuardPolicy.evaluateServerUnavailable(
                original,
                IdleRestartGuardPolicy.ScreenIdentity("SERVER_UNAVAILABLE", 43, "hwnd-7"),
                120_000L,
                currentSessionReady = false,
            ),
        )
        assertEquals(
            IdleRestartGuardPolicy.ServerUnavailableDecision.DEFER_ACTIVE_SESSION,
            IdleRestartGuardPolicy.evaluateServerUnavailable(original, original, 120_000L, currentSessionReady = true),
        )
        assertEquals(
            IdleRestartGuardPolicy.ServerUnavailableDecision.DEFER_ACTIVE_SESSION,
            IdleRestartGuardPolicy.evaluateServerUnavailable(
                original,
                original,
                120_000L,
                currentSessionReady = false,
                activeGame = true,
            ),
        )
        assertEquals(
            IdleRestartGuardPolicy.ServerUnavailableDecision.DEFER_ACTIVE_MATCHMAKING,
            IdleRestartGuardPolicy.evaluateServerUnavailable(
                original,
                IdleRestartGuardPolicy.ScreenIdentity("MATCHMAKING", 42, "hwnd-7"),
                120_000L,
                currentSessionReady = false,
            ),
        )
        assertEquals(
            IdleRestartGuardPolicy.ServerUnavailableDecision.DEFER_ACTIVE_MATCHMAKING,
            IdleRestartGuardPolicy.evaluateServerUnavailable(
                original, original, 120_000L, currentSessionReady = false,
                noActiveMatchmakingEvidence = false,
            ),
        )
    }

    @Test
    fun `Yogg restart requires repeated exact page proof for sixty seconds and stops at bounded cap`() {
        val exactPage = IdleRestartGuardPolicy.ScreenIdentity("SERVER_UNAVAILABLE", 42, "hwnd-7")
        assertEquals(
            "RESTART_HEARTHSTONE_NO_INPUT_NO_AUTO_PAUSE",
            IdleRestartGuardPolicy.SERVER_UNAVAILABLE_RESTART_ACTION,
        )
        assertEquals(
            IdleRestartGuardPolicy.ServerUnavailableDecision.WAIT_FOR_STABILITY,
            IdleRestartGuardPolicy.evaluateServerUnavailable(
                exactPage, exactPage, 59_999L, currentSessionReady = false,
            ),
        )
        assertEquals(
            IdleRestartGuardPolicy.ServerUnavailableDecision.RESTART_CLIENT,
            IdleRestartGuardPolicy.evaluateServerUnavailable(
                exactPage, exactPage, 60_000L, currentSessionReady = false,
                nowMs = 70_000L,
            ),
        )
        assertEquals(
            IdleRestartGuardPolicy.ServerUnavailableDecision.ALERT_OPERATOR,
            IdleRestartGuardPolicy.evaluateServerUnavailable(
                exactPage, exactPage, 120_000L, currentSessionReady = false,
                restartCount = IdleRestartGuardPolicy.SERVER_UNAVAILABLE_MAX_RESTARTS,
            ),
            "exhaustion reports visibly while the caller keeps the monitor running",
        )
        assertEquals(
            IdleRestartGuardPolicy.ServerUnavailableDecision.DEFER_ACTIVE_SESSION,
            IdleRestartGuardPolicy.evaluateServerUnavailable(
                exactPage, exactPage, 120_000L, currentSessionReady = false,
                activeGame = true,
            ),
        )
    }

    @Test
    fun `unchanged unknown screen restarts only after two same-window observations at least sixty seconds apart`() {
        val first = IdleRestartGuardPolicy.UnknownScreenIdentity("UNKNOWN", 42, "hwnd-7", 0x1234L)
        assertEquals(
            IdleRestartGuardPolicy.UnknownScreenDecision.WAIT_FOR_STABILITY,
            IdleRestartGuardPolicy.evaluateUnknownScreen(first, first, 59_999L, false, false, false, true),
        )
        assertEquals(
            IdleRestartGuardPolicy.UnknownScreenDecision.RESTART_CLIENT,
            IdleRestartGuardPolicy.evaluateUnknownScreen(first, first, 60_000L, false, false, false, true),
        )
    }

    @Test
    fun `changed unknown screenshot or client resets the stability baseline`() {
        val first = IdleRestartGuardPolicy.UnknownScreenIdentity("UNKNOWN", 42, "hwnd-7", 0x1234L)
        assertEquals(
            IdleRestartGuardPolicy.UnknownScreenDecision.RESET_STABILITY,
            IdleRestartGuardPolicy.evaluateUnknownScreen(
                first, first.copy(perceptualFingerprint = 0x1234L xor 0x7L), 90_000L, false, false, false, true,
            ),
        )
        assertEquals(
            IdleRestartGuardPolicy.UnknownScreenDecision.RESET_STABILITY,
            IdleRestartGuardPolicy.evaluateUnknownScreen(
                first, first.copy(pid = 43), 90_000L, false, false, false, true,
            ),
        )
    }

    @Test
    fun `unknown screen requires absent session positive no queue evidence and no cooldown`() {
        val same = IdleRestartGuardPolicy.UnknownScreenIdentity("UNKNOWN", 42, "hwnd-7", 0x1234L)
        assertEquals(IdleRestartGuardPolicy.UnknownScreenDecision.DEFER_ACTIVE_SESSION,
            IdleRestartGuardPolicy.evaluateUnknownScreen(same, same, 90_000L, true, false, false, true))
        assertEquals(IdleRestartGuardPolicy.UnknownScreenDecision.DEFER_ACTIVE_SESSION,
            IdleRestartGuardPolicy.evaluateUnknownScreen(same, same, 90_000L, false, true, false, true))
        assertEquals(IdleRestartGuardPolicy.UnknownScreenDecision.DEFER_ACTIVE_SESSION,
            IdleRestartGuardPolicy.evaluateUnknownScreen(same, same, 90_000L, false, false, true, true))
        assertEquals(IdleRestartGuardPolicy.UnknownScreenDecision.DEFER_UNVERIFIED_MATCHMAKING,
            IdleRestartGuardPolicy.evaluateUnknownScreen(same, same, 90_000L, false, false, false, false))
        assertEquals(IdleRestartGuardPolicy.UnknownScreenDecision.DEFER_ACTIVE_MATCHMAKING,
            IdleRestartGuardPolicy.evaluateUnknownScreen(same.copy(screen = "MATCHMAKING"), same.copy(screen = "MATCHMAKING"), 90_000L, false, false, false, true))
        assertEquals(IdleRestartGuardPolicy.UnknownScreenDecision.WAIT_FOR_STABILITY,
            IdleRestartGuardPolicy.evaluateUnknownScreen(same, same, 90_000L, false, false, false, true, nowMs = 200_000L, lastRestartAtMs = 1L))
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
            val serverPage = IdleRestartGuardPolicy.ScreenIdentity("SERVER_UNAVAILABLE", 42, "hwnd-7")
            assertEquals(
                IdleRestartGuardPolicy.ServerUnavailableDecision.RESTART_CLIENT,
                IdleRestartGuardPolicy.evaluateServerUnavailable(
                    first = serverPage,
                    current = serverPage,
                    stableForMs = IdleRestartGuardPolicy.SERVER_UNAVAILABLE_STABILITY_MS,
                    currentSessionReady = false,
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
