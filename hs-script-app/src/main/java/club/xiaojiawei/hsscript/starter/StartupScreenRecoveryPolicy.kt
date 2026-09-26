package club.xiaojiawei.hsscript.starter

/**
 * Timing policy for the first screen-recovery probe after Start/F1.
 *
 * The first check makes a stalled launch visible quickly. Later checks only
 * run after the Power.log cursor has been quiet for the stale-screen limit.
 */
internal object StartupScreenRecoveryPolicy {
    const val INITIAL_PROBE_DELAY_MS = 2_500L
    const val PROBE_RETRY_INTERVAL_MS = 2_000L
    const val MAX_PROBE_WINDOW_MS = 15_000L

    enum class Decision {
        WAIT,
        PROBE,
        DEFER_NORMAL_FLOW,
        FINISHED,
    }

    fun decide(
        elapsedMs: Long,
        noLogProgressMs: Long,
        normalFlowActive: Boolean,
        initialProbeAttempted: Boolean,
    ): Decision {
        if (normalFlowActive) return Decision.DEFER_NORMAL_FLOW
        if (initialProbeAttempted && elapsedMs >= MAX_PROBE_WINDOW_MS) return Decision.FINISHED
        if (!initialProbeAttempted && elapsedMs >= INITIAL_PROBE_DELAY_MS) return Decision.PROBE
        if (initialProbeAttempted && noLogProgressMs >= PROBE_RETRY_INTERVAL_MS) return Decision.PROBE
        return Decision.WAIT
    }
}
