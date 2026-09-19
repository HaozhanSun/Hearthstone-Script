package club.xiaojiawei.hsscript.starter

/**
 * Keeps the fast upstream startup path while filtering a one-sample Windows
 * process/window race at the handoff boundary.
 */
internal object GameStartupHandoffPolicy {
    const val REQUIRED_STABLE_OBSERVATIONS = 2
    const val PROCESS_LOSS_GRACE_MS = 5_000L
    const val POWER_LOG_STALL_RETRY_MS = 30_000L

    data class State(
        val stableObservations: Int = 0,
        val lastObservedAtMs: Long? = null,
    )

    enum class Decision { WAIT, HANDOFF, RETRY }

    data class Evaluation(val state: State, val decision: Decision)

    /**
     * A newly discovered client can have a visible window before Power.log
     * contains any current-session data. Do not run the expensive visual
     * recovery path during that short startup interval.
     */
    fun startupScreenProbeReady(powerLogAttached: Boolean, powerLogLength: Long): Boolean =
        powerLogAttached && powerLogLength > 0L

    /**
     * Never run the expensive startup visual recovery while the current
     * Power.log is absent or empty.  A due probe is not evidence that the
     * client is stale: the client can still be loading normally.  The normal
     * listener/handoff path owns this interval; later lifecycle recovery has
     * its own bounded stale-state policy once the log is usable.
     */
    fun shouldDeferStartupScreenProbe(
        powerLogAttached: Boolean,
        powerLogLength: Long,
        decision: StartupScreenRecoveryPolicy.Decision,
    ): Boolean =
        !startupScreenProbeReady(powerLogAttached, powerLogLength) &&
            decision != StartupScreenRecoveryPolicy.Decision.DEFER_NORMAL_FLOW

    fun observe(
        state: State,
        processAlive: Boolean,
        windowFound: Boolean,
        nowMs: Long,
    ): Evaluation {
        if (processAlive && windowFound) {
            val nextState = State(state.stableObservations + 1, nowMs)
            return Evaluation(
                nextState,
                if (nextState.stableObservations >= REQUIRED_STABLE_OBSERVATIONS) {
                    Decision.HANDOFF
                } else {
                    Decision.WAIT
                },
            )
        }

        val lastObservedAt = state.lastObservedAtMs
        if (lastObservedAt != null && nowMs - lastObservedAt < PROCESS_LOSS_GRACE_MS) {
            return Evaluation(state.copy(stableObservations = 0), Decision.WAIT)
        }
        return Evaluation(State(), if (lastObservedAt == null) Decision.WAIT else Decision.RETRY)
    }

    fun powerLogStallRetryDue(lastProgressAtMs: Long?, nowMs: Long): Boolean =
        lastProgressAtMs != null && nowMs - lastProgressAtMs >= POWER_LOG_STALL_RETRY_MS
}
