package club.xiaojiawei.hsscript.status

/**
 * Keeps the RESTART decision tied to both state-machine recovery and the
 * configured starter chain. The chain reattaches to a verified live client or
 * launches through the normal platform/game starters when no client exists.
 */
internal object NoProgressRecoveryDispatch {
    fun restartToStartup(
        recoverModeToStartup: () -> Unit,
        startConfiguredStarterChain: () -> Unit,
    ) {
        recoverModeToStartup()
        startConfiguredStarterChain()
    }
}
