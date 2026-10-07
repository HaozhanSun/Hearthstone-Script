package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoProgressWatchdogTest {

    @Test
    fun `verified Power log startup retries stay alive after bounded recovery exhaustion`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 120_000L)
        val startup = { now: Long ->
            snapshot(
                now = now,
                screen = NoProgressWatchdog.ScreenExpectation.STARTUP,
                powerLogLength = 1024L,
                powerLogPosition = 1024L,
                screenConfirmed = true,
            )
        }
        val first = watchdog.observe(startup(0L))
        val second = watchdog.observe(startup(120_000L))
        val third = watchdog.observe(startup(240_000L))
        val fourth = watchdog.observe(startup(360_000L))
        val nextWindow = watchdog.observe(startup(360_001L))
        val nextRetryCycle = watchdog.observe(startup(480_000L))

        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT, first.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.REBIND, second.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.RESTART, third.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.RECOVERY_RETRY_BACKOFF, fourth.action)
        assertEquals("startup-or-initialization-timeout-retry-exhausted-rearmed", fourth.reason)
        assertEquals(1L, nextWindow.elapsedNoProgressMs)
        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, nextWindow.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.REBIND, nextRetryCycle.action)
    }

    @Test
    fun `unbound or sentinel Power log never starts destructive recovery`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 1_000L)
        val first = watchdog.observe(
            snapshot(now = 0L, screen = NoProgressWatchdog.ScreenExpectation.STARTUP, powerLogUsable = false),
        )
        val late = watchdog.observe(
            snapshot(
                now = 900_000L,
                screen = NoProgressWatchdog.ScreenExpectation.STARTUP,
                powerLogUsable = false,
                powerLogPath = null,
                powerLogPosition = Long.MIN_VALUE,
                powerLogLength = 0L,
            ),
        )

        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, first.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, late.action)
        assertEquals("power-log-unbound-or-unusable", late.reason)
    }

    @Test
    fun `confirmed current pid matchmaking queue survives empty power log and no progress timeout`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 1_000L)
        val queue = { now: Long ->
            snapshot(
                now = now,
                screen = NoProgressWatchdog.ScreenExpectation.MENU_OR_MATCHING,
                currentPid = 97212L,
                boundPid = null,
                powerLogPath = "current-session/Power.log",
                boundPowerLogPath = null,
                powerLogUsable = false,
                powerLogLength = 0L,
                powerLogPosition = Long.MIN_VALUE,
                screenConfirmed = true,
                confirmedMatchmakingQueue = true,
            )
        }

        assertEquals(
            "confirmed-matchmaking-queue-awaiting-create-game",
            watchdog.observe(queue(0L)).reason,
        )
        val late = watchdog.observe(queue(5_000_000L))
        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, late.action)
        assertEquals("confirmed-matchmaking-queue-awaiting-create-game", late.reason)
        assertEquals(0, late.recoveryAttempt, "queue waiting must not enter rebind/restart recovery")
    }

    @Test
    fun `expired confirmed matchmaking queue fails closed without recovery dispatch`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 1_000L)
        val decision = watchdog.observe(
            NoProgressWatchdog.Snapshot(
                nowMs = 5_000L,
                mode = "STARTUP",
                expectedMode = "STARTUP",
                screen = NoProgressWatchdog.ScreenExpectation.STARTUP,
                processAlive = true,
                currentPid = 42L,
                boundPid = 42L,
                windowPresent = true,
                powerLogPath = null,
                boundPowerLogPath = null,
                powerLogPosition = -1L,
                powerLogLength = -1L,
                powerLogAgeMs = -1L,
                matchmakingQueueExpired = true,
            ),
        )
        assertEquals(NoProgressWatchdog.RecoveryAction.PAUSE_EXPIRED_MATCHMAKING_QUEUE, decision.action)
        assertEquals("confirmed-matchmaking-queue-deadline-expired-no-input", decision.reason)
    }

    @Test
    fun `missing game process with unbound log recovers only after a bounded grace and retry interval`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 1_000L)
        val missing = { now: Long ->
            snapshot(
                now = now,
                screen = NoProgressWatchdog.ScreenExpectation.UNKNOWN,
                processAlive = false,
                currentPid = null,
                powerLogUsable = false,
            )
        }

        assertEquals(
            "process-missing-unbound-log-grace",
            watchdog.observe(missing(0L)).reason,
        )
        assertEquals(
            NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED,
            watchdog.observe(missing(999L)).action,
        )
        assertEquals(
            NoProgressWatchdog.RecoveryAction.REBIND,
            watchdog.observe(missing(1_000L)).action,
        )
        assertEquals(
            NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED,
            watchdog.observe(missing(1_001L)).action,
        )
        assertEquals(
            NoProgressWatchdog.RecoveryAction.RESTART,
            watchdog.observe(missing(2_000L)).action,
        )
    }

    @Test
    fun `stale startup mode without fresh loading screenshot never restarts`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 1_000L)
        watchdog.observe(snapshot(now = 0L, screen = NoProgressWatchdog.ScreenExpectation.STARTUP))
        val late = watchdog.observe(snapshot(now = 900_000L, screen = NoProgressWatchdog.ScreenExpectation.STARTUP))

        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, late.action)
        assertEquals("screen-evidence-unconfirmed", late.reason)
    }

    @Test
    fun `visible menu and matchmaking are safe expected states regardless of inactivity`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 1L)
        watchdog.observe(snapshot(now = 0L, screen = NoProgressWatchdog.ScreenExpectation.MENU_OR_MATCHING))
        val decision = watchdog.observe(
            snapshot(now = 900_000L, screen = NoProgressWatchdog.ScreenExpectation.MENU_OR_MATCHING),
        )

        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, decision.action)
        assertEquals("visible-menu-or-matchmaking", decision.reason)
    }

    @Test
    fun `confirmed loading with readable stagnant log only recovers after three minutes`() {
        val watchdog = NoProgressWatchdog()
        val loading = { now: Long ->
            snapshot(
                now = now,
                screen = NoProgressWatchdog.ScreenExpectation.STARTUP,
                screenConfirmed = true,
                powerLogLength = 4096L,
                powerLogPosition = 4096L,
            )
        }
        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT, watchdog.observe(loading(0L)).action)
        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, watchdog.observe(loading(179_999L)).action)
        val stalled = watchdog.observe(loading(180_000L))
        assertEquals(NoProgressWatchdog.RecoveryAction.REBIND, stalled.action)
        assertEquals("startup-or-initialization-timeout", stalled.reason)
    }

    @Test
    fun `authoritative mulligan survives stale startup mode and no progress`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 1_000L)
        val mulligan = snapshot(
            now = 500_000L,
            screen = NoProgressWatchdog.ScreenExpectation.MULLIGAN,
            authoritativeLiveMatch = true,
        ).copy(mode = "STARTUP", expectedMode = "STARTUP")

        val decision = watchdog.observe(mulligan)

        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, decision.action)
        assertEquals("live-match-preserved", decision.reason)
    }

    @Test
    fun `live match with missing process fails closed instead of relaunching`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 1L)
        val decision = watchdog.observe(
            snapshot(
                now = 500_000L,
                screen = NoProgressWatchdog.ScreenExpectation.MULLIGAN,
                currentPid = null,
                authoritativeLiveMatch = true,
            ),
        )

        assertEquals(NoProgressWatchdog.RecoveryAction.REBIND, decision.action)
        assertEquals("live-match-process-missing", decision.reason)
    }

    @Test
    fun `live match with replaced process lineage fails closed instead of reattaching stale state`() {
        val watchdog = NoProgressWatchdog()
        val decision = watchdog.observe(
            snapshot(
                now = 50_000L,
                screen = NoProgressWatchdog.ScreenExpectation.MULLIGAN,
                currentPid = 222L,
                boundPid = 111L,
                authoritativeLiveMatch = true,
            ),
        )

        assertEquals(NoProgressWatchdog.RecoveryAction.REBIND, decision.action)
        assertEquals("live-match-process-lineage-changed", decision.reason)
    }

    @Test
    fun `missing live-match process gets bounded rebind restart then backoff cycle`() {
        val watchdog = NoProgressWatchdog()
        fun missing(now: Long) = snapshot(
            now = now,
            screen = NoProgressWatchdog.ScreenExpectation.MULLIGAN,
            currentPid = null,
            authoritativeLiveMatch = true,
        )

        val rebind = watchdog.observe(missing(1_000L))
        val restart = watchdog.observe(missing(2_000L))
        val backoff = watchdog.observe(missing(3_000L))
        val nextCycle = watchdog.observe(missing(4_000L))

        assertEquals(NoProgressWatchdog.RecoveryAction.REBIND, rebind.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.RESTART, restart.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.RECOVERY_RETRY_BACKOFF, backoff.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.REBIND, nextCycle.action)
    }

    @Test
    fun `late current session log binding resets startup baseline and growing log is progress`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 1_000L)
        watchdog.observe(snapshot(now = 0L, screen = NoProgressWatchdog.ScreenExpectation.STARTUP, powerLogUsable = false))
        val attached = watchdog.observe(
            snapshot(
                now = 20_000L,
                screen = NoProgressWatchdog.ScreenExpectation.STARTUP,
                powerLogUsable = true,
                powerLogLength = 512L,
                powerLogPosition = 512L,
                screenConfirmed = true,
            ),
        )
        val growing = watchdog.observe(
            snapshot(
                now = 20_500L,
                screen = NoProgressWatchdog.ScreenExpectation.STARTUP,
                powerLogUsable = true,
                powerLogLength = 768L,
                powerLogPosition = 768L,
                screenConfirmed = true,
            ),
        )

        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT, attached.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT, growing.action)
        assertEquals("authoritative-progress", growing.reason)
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
        val backoff = watchdog.observe(snapshot(now = 30_000L, screen = NoProgressWatchdog.ScreenExpectation.EXTERNAL_MODAL))

        assertEquals(NoProgressWatchdog.RecoveryAction.DISMISS_EXTERNAL_MODAL, dismiss.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.RESTART, restart.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.RECOVERY_RETRY_BACKOFF, backoff.action)
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
    fun `persistent foreground mismatch becomes rebind restart then bounded backoff without pausing`() {
        val watchdog = NoProgressWatchdog(noProgressTimeoutMs = 120_000L)
        watchdog.observe(snapshot(now = 0L))
        val rebind = watchdog.observe(
            snapshot(now = 1_000L, foregroundMatches = false, foregroundFailureCount = 3),
        )
        val restart = watchdog.observe(
            snapshot(now = 2_000L, foregroundMatches = false, foregroundFailureCount = 4),
        )
        val backoff = watchdog.observe(
            snapshot(now = 3_000L, foregroundMatches = false, foregroundFailureCount = 5),
        )
        val nextCycle = watchdog.observe(
            snapshot(now = 4_000L, foregroundMatches = false, foregroundFailureCount = 6),
        )
        assertEquals(NoProgressWatchdog.RecoveryAction.REBIND, rebind.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.RESTART, restart.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.RECOVERY_RETRY_BACKOFF, backoff.action)
        assertEquals("foreground-mismatch-persistent-retry-exhausted-rearmed", backoff.reason)
        assertEquals(NoProgressWatchdog.RecoveryAction.REBIND, nextCycle.action)
    }

    @Test
    fun `active gameplay timeout re-arms bounded retry cycle without terminal pause`() {
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
        val backoff = watchdog.observe(snapshot(now = 3_000L, screen = NoProgressWatchdog.ScreenExpectation.ACTIVE_GAMEPLAY))
        val retryCycle = watchdog.observe(snapshot(now = 4_000L, screen = NoProgressWatchdog.ScreenExpectation.ACTIVE_GAMEPLAY))
        assertEquals(NoProgressWatchdog.RecoveryAction.RECOVERY_RETRY_BACKOFF, backoff.action)
        assertEquals(NoProgressWatchdog.RecoveryAction.REBIND, retryCycle.action)
    }

    private fun snapshot(
        now: Long,
        screen: NoProgressWatchdog.ScreenExpectation = NoProgressWatchdog.ScreenExpectation.ACTIVE_GAMEPLAY,
        processAlive: Boolean = true,
        currentPid: Long? = 111L,
        boundPid: Long? = 111L,
        powerLogPath: String? = "run/Power.log",
        boundPowerLogPath: String? = "run/Power.log",
        foregroundMatches: Boolean = true,
        foregroundFailureCount: Int = 0,
        powerLogUsable: Boolean = true,
        authoritativeLiveMatch: Boolean = false,
        screenConfirmed: Boolean = false,
        confirmedMatchmakingQueue: Boolean = false,
        powerLogPosition: Long = 0L,
        powerLogLength: Long = 0L,
    ) = NoProgressWatchdog.Snapshot(
        nowMs = now,
        mode = "GAMEPLAY",
        expectedMode = "GAMEPLAY",
        screen = screen,
        processAlive = processAlive,
        currentPid = currentPid,
        boundPid = boundPid,
        windowPresent = true,
        foregroundMatches = foregroundMatches,
        foregroundFailureCount = foregroundFailureCount,
        powerLogPath = powerLogPath,
        boundPowerLogPath = boundPowerLogPath,
        powerLogPosition = powerLogPosition,
        powerLogLength = powerLogLength,
        powerLogAgeMs = now,
        powerLogUsable = powerLogUsable,
        authoritativeLiveMatch = authoritativeLiveMatch,
        screenConfirmed = screenConfirmed || screen == NoProgressWatchdog.ScreenExpectation.ACTIVE_GAMEPLAY ||
            screen == NoProgressWatchdog.ScreenExpectation.OPPONENT_TURN ||
            screen == NoProgressWatchdog.ScreenExpectation.ANIMATION ||
            screen == NoProgressWatchdog.ScreenExpectation.MULLIGAN ||
            screen == NoProgressWatchdog.ScreenExpectation.RESULT ||
            screen == NoProgressWatchdog.ScreenExpectation.MENU_OR_MATCHING ||
            screen == NoProgressWatchdog.ScreenExpectation.EXTERNAL_MODAL,
        confirmedMatchmakingQueue = confirmedMatchmakingQueue,
    )
}

