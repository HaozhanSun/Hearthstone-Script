package club.xiaojiawei.hsscript.starter

/**
 * Timing policy for the first screen-recovery probe after Start/F1.
 *
 * The first check makes a stalled launch visible quickly. Later checks only
 * run after the Power.log cursor has been quiet for the stale-screen limit.
 */
internal object StartupScreenRecoveryPolicy {
    const val INITIAL_PROBE_DELAY_MS = 3_000L
    const val NO_LOG_PROGRESS_TIMEOUT_MS = 30_000L

    enum class Decision {
        WAIT,
        PROBE,
        DEFER_NORMAL_FLOW,
    }

    fun decide(
        elapsedMs: Long,
        noLogProgressMs: Long,
        normalFlowActive: Boolean,
        initialProbeAttempted: Boolean,
    ): Decision {
        if (normalFlowActive) return Decision.DEFER_NORMAL_FLOW
        if (!initialProbeAttempted && elapsedMs >= INITIAL_PROBE_DELAY_MS) return Decision.PROBE
        if (initialProbeAttempted && noLogProgressMs >= NO_LOG_PROGRESS_TIMEOUT_MS) return Decision.PROBE
        return Decision.WAIT
    }
}
