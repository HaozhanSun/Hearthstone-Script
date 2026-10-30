package club.xiaojiawei.hsscript.status

/** Propagate only fresh, pixel-authorized active-search evidence to lifecycle recovery. */
internal object MatchmakingQueueLifecycleEvidencePolicy {
    fun propagateClassifiedSearchScreenIfAuthorized(
        screenKind: String,
        captureAuthorized: Boolean,
        observedAt: Long,
    ): Boolean {
        if (!captureAuthorized || screenKind != "MATCHMAKING") return false
        LifecycleTrace.markActiveMatchmakingScreenObserved(observedAt)
        return true
    }

    fun isAuthoritativeActiveSearch(
        probe: ScreenStateRecovery.StartGameErrorDialogProbe,
        captureAuthorized: Boolean,
        powerLogLength: Long,
    ): Boolean = captureAuthorized && powerLogLength >= 0L &&
        probe.state == club.xiaojiawei.hsscript.strategy.mode.MatchmakingDialogRecoveryPolicy.Probe.NO_ERROR_DIALOG &&
        probe.provider == "VISUAL_SIGNATURE" &&
        probe.reason == "matchmaking-search-screen-visible"

    fun propagateIfAuthoritative(
        probe: ScreenStateRecovery.StartGameErrorDialogProbe,
        captureAuthorized: Boolean,
        powerLogLength: Long,
        observedAt: Long,
    ): Boolean {
        if (!isAuthoritativeActiveSearch(probe, captureAuthorized, powerLogLength)) return false
        LifecycleTrace.markActiveMatchmakingScreenObserved(observedAt)
        return true
    }
}
