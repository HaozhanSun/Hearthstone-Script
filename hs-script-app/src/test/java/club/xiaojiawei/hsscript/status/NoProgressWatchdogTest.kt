package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoProgressWatchdogTest {

    @Test
    fun `zero byte startup times out into bounded recovery`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 120_000L)
        val first = watchdog.observe(snapshot(now = 0L, screen = NoProgressWatchdog.ScreenExpectation.STARTUP))
        val second = watchdog.observe(snapshot(now = 120_000L, screen = NoProgressWatchdog.ScreenExpectation.STARTUP))
        val third = watchdog.observe(snapshot(now = 240_000L, screen = NoProgressWatchdog.ScreenExpectation.STARTUP))
        val fourth = watchdog.observe(snapshot(now = 360_000L, screen = NoProgressWatchdog.ScreenExpectation.STARTUP))

        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT, first.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.REBIND, second.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.RESTART, third.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.ESCALATE_PAUSE, fourth.action)
        assertTrue(fourth.reason.endsWith("retry-exhausted"))
    }

    @Test
    fun `replaced process or log lineage requests rebind`() {
        val watchdog = NoProgressWatchdog()
        watchdog.observe(snapshot(now = 0L))
        val replaced = watchdog.observe(snapshot(now = 10_000L, currentPid = 222L))
        assertEquals(NoProgressWatchdog.RecoveryAction.REBIND, replaced.action)
        assertEquals("process-replaced", replaced.reason)

        val rebound = watchdog.observe(
            snapshot(
                now = 11_000L,
                currentPid = 222L,
                boundPid = 222L,
                powerLogPath = "new/Power.log",
                boundPowerLogPath = "new/Power.log",
            ),
        )
        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT, rebound.action)
        assertEquals("authoritative-progress", rebound.reason)
    }

    @Test
    fun `missing current process is not mislabeled as process replacement`() {
        val watchdog = NoProgressWatchdog()
        watchdog.observe(snapshot(now = 0L, currentPid = 111L, boundPid = 111L))

        val missing = watchdog.observe(snapshot(now = 10_000L, currentPid = null, boundPid = 111L))

        assertEquals(NoProgressWatchdog.RecoveryAction.REBIND, missing.action)
        assertEquals("process-missing", missing.reason)
    }

    @Test
    fun `external reconnect or update modal is dismissed then bounded`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 10_000L)
        watchdog.observe(snapshot(now = 0L, screen = NoProgressWatchdog.ScreenExpectation.EXTERNAL_MODAL))
        val dismiss = watchdog.observe(snapshot(now = 10_000L, screen = NoProgressWatchdog.ScreenExpectation.EXTERNAL_MODAL))
        val restart = watchdog.observe(snapshot(now = 20_000L, screen = NoProgressWatchdog.ScreenExpectation.EXTERNAL_MODAL))
        val pause = watchdog.observe(snapshot(now = 30_000L, screen = NoProgressWatchdog.ScreenExpectation.EXTERNAL_MODAL))

        assertEquals(NoProgressWatchdog.RecoveryAction.DISMISS_EXTERNAL_MODAL, dismiss.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.RESTART, restart.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.ESCALATE_PAUSE, pause.action)
    }

    @Test
    fun `result screen has priority over no progress`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 1L)
        watchdog.observe(snapshot(now = 0L, screen = NoProgressWatchdog.ScreenExpectation.RESULT))
        val decision = watchdog.observe(snapshot(now = 100_000L, screen = NoProgressWatchdog.ScreenExpectation.RESULT))
        assertEquals(NoProgressWatchdog.RecoveryAction.NOOP_RESULT, decision.action)
        assertEquals("result-screen-priority", decision.reason)
    }

    @Test
    fun `opponent turn and animation wait do not restart a normal long wait`() {
        val opponentWatchdog = NoProgressWatchdog(noProgressTimeoutMs = 10_000L)
        opponentWatchdog.observe(snapshot(now = 0L, screen = NoProgressWatchdog.ScreenExpectation.OPPONENT_TURN))
        val opponent = opponentWatchdog.observe(
            snapshot(now = 90_000L, screen = NoProgressWatchdog.ScreenExpectation.OPPONENT_TURN),
        )
        val animationWatchdog = NoProgressWatchdog(noProgressTimeoutMs = 10_000L)
        animationWatchdog.observe(snapshot(now = 0L, screen = NoProgressWatchdog.ScreenExpectation.ANIMATION))
        val animation = animationWatchdog.observe(
            snapshot(now = 180_000L, screen = NoProgressWatchdog.ScreenExpectation.ANIMATION),
        )
        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, opponent.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, animation.action)
    }

    @Test
    fun `persistent foreground mismatch becomes rebind restart then pause`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 120_000L)
        watchdog.observe(snapshot(now = 0L))
        val rebind = watchdog.observe(
            snapshot(now = 1_000L, foregroundMatches = false, foregroundFailureCount = 3),
        )
        val restart = watchdog.observe(
            snapshot(now = 2_000L, foregroundMatches = false, foregroundFailureCount = 4),
        )
        val pause = watchdog.observe(
            snapshot(now = 3_000L, foregroundMatches = false, foregroundFailureCount = 5),
        )
        assertEquals(NoProgressWatchdog.RecoveryAction.REBIND, rebind.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.RESTART, restart.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.ESCALATE_PAUSE, pause.action)
        assertEquals("foreground-mismatch-persistent-retry-exhausted", pause.reason)
    }

    @Test
    fun `active gameplay timeout does not create an unbounded restart loop`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 1_000L, maxRecoveryAttempts = 2)
        watchdog.observe(snapshot(now = 0L, screen = NoProgressWatchdog.ScreenExpectation.ACTIVE_GAMEPLAY))
        assertEquals(
            NoProgressWatchdog.RecoveryAction.REBIND,
            watchdog.observe(snapshot(now = 1_000L, screen = NoProgressWatchdog.ScreenExpectation.ACTIVE_GAMEPLAY)).action,
        )
        assertEquals(
            NoProgressWatchdog.RecoveryAction.RESTART,
            watchdog.observe(snapshot(now = 2_000L, screen = NoProgressWatchdog.ScreenExpectation.ACTIVE_GAMEPLAY)).action,
        )
        assertEquals(
            NoProgressWatchdog.RecoveryAction.ESCALATE_PAUSE,
            watchdog.observe(snapshot(now = 3_000L, screen = NoProgressWatchdog.ScreenExpectation.ACTIVE_GAMEPLAY)).action,
        )
        assertEquals(
            NoProgressWatchdog.RecoveryAction.ESCALATE_PAUSE,
            watchdog.observe(snapshot(now = 4_000L, screen = NoProgressWatchdog.ScreenExpectation.ACTIVE_GAMEPLAY)).action,
        )
    }

    private fun snapshot(
        now: Long,
        screen: NoProgressWatchdog.ScreenExpectation = NoProgressWatchdog.ScreenExpectation.ACTIVE_GAMEPLAY,
        currentPid: Long? = 111L,
        boundPid: Long? = 111L,
        powerLogPath: String? = "run/Power.log",
        boundPowerLogPath: String? = "run/Power.log",
        foregroundMatches: Boolean = true,
        foregroundFailureCount: Int = 0,
    ) = NoProgressWatchdog.Snapshot(
        nowMs = now,
        mode = "GAMEPLAY",
        expectedMode = "GAMEPLAY",
        screen = screen,
        processAlive = true,
        currentPid = currentPid,
        boundPid = boundPid,
        windowPresent = true,
        foregroundMatches = foregroundMatches,
        foregroundFailureCount = foregroundFailureCount,
        powerLogPath = powerLogPath,
        boundPowerLogPath = boundPowerLogPath,
        powerLogPosition = 0L,
        powerLogLength = 0L,
        powerLogAgeMs = now,
    )
}

