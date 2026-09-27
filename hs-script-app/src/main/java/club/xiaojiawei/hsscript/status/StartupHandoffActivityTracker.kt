package club.xiaojiawei.hsscript.status

import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded signal that the normal GameStarter startup handoff is still being
 * attempted. This is initiation activity only; it is not evidence that the
 * platform or game accepted an input.
 */
internal class StartupHandoffActivityTracker(
    private val activeWindowMs: Long = DEFAULT_ACTIVE_WINDOW_MS,
    private val maxContinuousActivityMs: Long = DEFAULT_MAX_CONTINUOUS_ACTIVITY_MS,
) {
    private data class ActivityWindow(val firstAttemptAtMs: Long, val lastAttemptAtMs: Long)

    private val activityWindow = AtomicReference<ActivityWindow?>(null)

    fun recordAttempt(nowMs: Long = System.currentTimeMillis()) {
        while (true) {
            val previous = activityWindow.get()
            if (previous != null && nowMs < previous.lastAttemptAtMs) return
            val next = if (
                previous == null ||
                nowMs - previous.lastAttemptAtMs >= activeWindowMs
            ) {
                ActivityWindow(firstAttemptAtMs = nowMs, lastAttemptAtMs = nowMs)
            } else {
                previous.copy(lastAttemptAtMs = nowMs)
            }
            if (activityWindow.compareAndSet(previous, next)) return
        }
    }

    fun shouldDeferNoProgress(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val window = activityWindow.get() ?: return false
        if (nowMs < window.lastAttemptAtMs || nowMs < window.firstAttemptAtMs ||
            nowMs - window.lastAttemptAtMs >= activeWindowMs ||
            nowMs - window.firstAttemptAtMs >= maxContinuousActivityMs
        ) {
            return false
        }
        return StartupHandoffActivityPolicy.isEligible(context)
    }

    data class Context(
        val working: Boolean,
        val paused: Boolean,
        val automaticPause: Boolean,
        val recoveryPending: Boolean,
        val replaying: Boolean,
        val inWar: Boolean,
        val terminalState: Boolean,
        val screen: NoProgressWatchdog.ScreenExpectation,
        val mode: String,
        val expectedMode: String,
        val gameProcessAlive: Boolean,
        val powerLogPath: String?,
    )

    companion object {
        const val DEFAULT_ACTIVE_WINDOW_MS = 90_000L
        const val DEFAULT_MAX_CONTINUOUS_ACTIVITY_MS = 300_000L
    }
}

internal object StartupHandoffActivityPolicy {
    private val startupModes = setOf("NONE", "STARTUP")

    fun isEligible(context: StartupHandoffActivityTracker.Context): Boolean =
        context.working &&
            !context.paused &&
            !context.automaticPause &&
            !context.recoveryPending &&
            !context.replaying &&
            !context.inWar &&
            !context.terminalState &&
            context.screen == NoProgressWatchdog.ScreenExpectation.STARTUP &&
            context.mode in startupModes &&
            context.expectedMode in startupModes &&
            !context.gameProcessAlive &&
            context.powerLogPath.isNullOrBlank()
}
