package club.xiaojiawei.hsscript.starter

import club.xiaojiawei.hsscriptbase.enums.ModeEnum

/**
 * Keeps the fast upstream startup path while filtering a one-sample Windows
 * process/window race at the handoff boundary.
 */
internal object GameStartupHandoffPolicy {
    const val REQUIRED_STABLE_OBSERVATIONS = 2
    const val PROCESS_LOSS_GRACE_MS = 5_000L
    const val POWER_LOG_STALL_RETRY_MS = 30_000L
    const val SCREEN_PROBE_COMPLETION_GRACE_MS = 15_000L

    data class State(
        val stableObservations: Int = 0,
        val lastObservedAtMs: Long? = null,
    )

    enum class Decision { WAIT, HANDOFF, RETRY }

    enum class HandshakeTimeoutDecision {
        NO_PAUSE_NEEDED,
        WAIT_FOR_SCREEN_PROBE,
        RETRY_VISIBLE_CLIENT,
        AUTOMATIC_PAUSE,
    }

    data class Evaluation(val state: State, val decision: Decision)

    /** A confirmed visible menu can finish startup before Power.log becomes non-empty. */
    fun startupHandshakeConfirmed(inWar: Boolean, mode: ModeEnum?): Boolean =
        inWar || (mode != null && mode != ModeEnum.STARTUP && mode != ModeEnum.LOGIN)

    fun onHandshakeTimeout(
        startupConfirmed: Boolean,
        screenProbeInProgress: Boolean = false,
        probeGraceElapsedMs: Long = SCREEN_PROBE_COMPLETION_GRACE_MS,
        gameAlive: Boolean = false,
        visibleGameWindow: Boolean = false,
    ): HandshakeTimeoutDecision = when {
        startupConfirmed -> HandshakeTimeoutDecision.NO_PAUSE_NEEDED
        screenProbeInProgress && probeGraceElapsedMs < SCREEN_PROBE_COMPLETION_GRACE_MS ->
            HandshakeTimeoutDecision.WAIT_FOR_SCREEN_PROBE
        gameAlive && visibleGameWindow -> HandshakeTimeoutDecision.RETRY_VISIBLE_CLIENT
        else -> HandshakeTimeoutDecision.AUTOMATIC_PAUSE
    }

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
