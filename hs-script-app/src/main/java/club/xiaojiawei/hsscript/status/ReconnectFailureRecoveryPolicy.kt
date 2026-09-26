package club.xiaojiawei.hsscript.status

/** Bounds client restarts when a non-reconnectable connection modal persists. */
internal class ReconnectFailureRecoveryPolicy(
    private val maxRestarts: Int = DEFAULT_MAX_RESTARTS,
    private val retryCooldownMs: Long = DEFAULT_RETRY_COOLDOWN_MS,
    private val attemptWindowMs: Long = DEFAULT_ATTEMPT_WINDOW_MS,
) {
    enum class Decision { RESTART_CLIENT, WAIT_FOR_RESTART, PAUSE_AUTOMATION }

    data class Evaluation(val decision: Decision, val attempt: Int)

    private var windowStartedAt = 0L
    private var lastRestartAt = 0L
    private var restartAttempts = 0

    @Synchronized
    fun observeFailure(nowMs: Long): Evaluation {
        if (windowStartedAt <= 0L || nowMs < windowStartedAt || nowMs - windowStartedAt >= attemptWindowMs) {
            windowStartedAt = nowMs
            lastRestartAt = 0L
            restartAttempts = 0
        }
        if (lastRestartAt > 0L && nowMs >= lastRestartAt && nowMs - lastRestartAt < retryCooldownMs) {
            return Evaluation(Decision.WAIT_FOR_RESTART, restartAttempts)
        }
        if (restartAttempts >= maxRestarts) {
            return Evaluation(Decision.PAUSE_AUTOMATION, restartAttempts)
        }
        restartAttempts++
        lastRestartAt = nowMs
        return Evaluation(Decision.RESTART_CLIENT, restartAttempts)
    }

    @Synchronized
    fun onStartupConfirmed() {
        windowStartedAt = 0L
        lastRestartAt = 0L
        restartAttempts = 0
    }

    companion object {
        const val DEFAULT_MAX_RESTARTS = 2
        const val DEFAULT_RETRY_COOLDOWN_MS = 30_000L
        const val DEFAULT_ATTEMPT_WINDOW_MS = 5 * 60_000L
    }
}
