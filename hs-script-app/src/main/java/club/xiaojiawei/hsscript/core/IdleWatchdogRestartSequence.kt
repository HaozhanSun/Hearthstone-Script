package club.xiaojiawei.hsscript.core

/** Ordered force-close and manifest-backed starter-chain handoff for idle recovery. */
internal object IdleWatchdogRestartSequence {
    fun dispatch(forceCloseClient: () -> Unit, startStarterChain: () -> Unit) {
        forceCloseClient()
        startStarterChain()
    }
}
