package club.xiaojiawei.hsscript.starter

/**
 * Keeps a fresh matchmaking handoff alive when Power.log has not opened a
 * game segment yet. A queue click and a visible search screen are both
 * positive evidence; neither should be reported as a startup failure merely
 * because no opponent has been assigned.
 */
internal object StartupScreenProbeCloseoutPolicy {
    fun shouldAlertOperator(
        currentSessionReady: Boolean,
        recoveryApplied: Boolean,
        attempts: Int,
        working: Boolean,
        paused: Boolean,
        activeMatchmakingScreen: Boolean,
        activeMatchmakingInputIntent: Boolean,
    ): Boolean = !currentSessionReady && !recoveryApplied && attempts > 0 && working && !paused &&
        !activeMatchmakingScreen && !activeMatchmakingInputIntent
}
