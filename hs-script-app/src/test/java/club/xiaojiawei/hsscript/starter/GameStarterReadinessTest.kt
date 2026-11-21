package club.xiaojiawei.hsscript.starter

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameStarterReadinessTest {
    @Test
    fun `startup probe retries beyond an initial unknown frame but remains bounded and pause aware`() {
        assertTrue(StartupScreenProbeRetryPolicy.shouldContinue(2_000L, paused = false))
        assertTrue(StartupScreenProbeRetryPolicy.shouldContinue(32_000L, paused = false))
        assertFalse(StartupScreenProbeRetryPolicy.shouldContinue(90_000L, paused = false))
        assertFalse(StartupScreenProbeRetryPolicy.shouldContinue(2_000L, paused = true))
    }

    @Test
    fun `startup closeout keeps an accepted queue alive while Power log is still empty`() {
        assertFalse(
            StartupScreenProbeCloseoutPolicy.shouldAlertOperator(
                currentSessionReady = false,
                recoveryApplied = false,
                attempts = 11,
                working = true,
                paused = false,
                activeMatchmakingScreen = true,
                activeMatchmakingInputIntent = true,
            ),
            "fresh visible search must not be reported as a missing-session failure",
        )
        assertFalse(
            StartupScreenProbeCloseoutPolicy.shouldAlertOperator(
                currentSessionReady = false,
                recoveryApplied = false,
                attempts = 11,
                working = true,
                paused = false,
                activeMatchmakingScreen = false,
                activeMatchmakingInputIntent = true,
            ),
            "an accepted Start remains a bounded pre-session handoff until CREATE_GAME arrives",
        )
    }

    @Test
    fun `startup closeout still alerts when no session and no queue evidence exist`() {
        assertTrue(
            StartupScreenProbeCloseoutPolicy.shouldAlertOperator(
                currentSessionReady = false,
                recoveryApplied = false,
                attempts = 11,
                working = true,
                paused = false,
                activeMatchmakingScreen = false,
                activeMatchmakingInputIntent = false,
            ),
        )
    }

    @Test
    fun `platform is not closed while the game is dead`() {
        assertFalse(PlatformCloseReadiness.shouldClose(true, false, true, 60_000L, 20_000L))
        assertFalse(PlatformCloseReadiness.shouldClose(false, true, true, 60_000L, 20_000L))
    }

    @Test
    fun `platform closes only after a visible window is stable`() {
        assertFalse(PlatformCloseReadiness.shouldClose(true, true, true, 19_999L, 20_000L))
        assertTrue(PlatformCloseReadiness.shouldClose(true, true, true, 20_000L, 20_000L))
    }

    @Test
    fun `platform stays alive until the startup handshake is confirmed`() {
        assertFalse(PlatformCloseReadiness.shouldClose(true, true, false, 60_000L, 20_000L))
    }

    @Test
    fun `platform close deadline spans the bounded startup visual probe`() {
        assertTrue(
            PlatformCloseReadiness.STARTUP_HANDOFF_MAX_WAIT_MS >=
                StartupScreenProbeRetryPolicy.MAX_DURATION_MS + 20_000L,
        )
        assertFalse(PlatformCloseReadiness.timeoutReached(45_929L),
            "the observed 45.9 second launcher-to-client handoff is still inside the startup window")
        assertFalse(PlatformCloseReadiness.timeoutReached(StartupScreenProbeRetryPolicy.MAX_DURATION_MS))
        assertTrue(PlatformCloseReadiness.timeoutReached(PlatformCloseReadiness.STARTUP_HANDOFF_MAX_WAIT_MS))
    }
}
