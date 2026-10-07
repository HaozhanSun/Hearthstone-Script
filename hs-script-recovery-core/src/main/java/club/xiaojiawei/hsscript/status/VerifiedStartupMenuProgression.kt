package club.xiaojiawei.hsscript.status

/** Narrow pre-session authorization; deliberately grants no queue/gameplay capability. */
object VerifiedStartupMenuProgression {
    enum class Screen { HOME, HOME_TASK_OVERLAY, OTHER }
    enum class Action { ENTER_HUB, DISMISS_OVERLAY, WAIT, BLOCK }
    data class Evidence(
        val screen: Screen, val confidence: Int, val pid: Long?, val currentPid: Long?, val windowPid: Long?,
        val foregroundAndPixelsVerified: Boolean, val configuredTournament: Boolean, val working: Boolean,
        val manuallyPaused: Boolean, val currentSessionPowerLogReady: Boolean,
        val activeMatch: PowerLogActiveMatchProbe.State, val priorAuthoritativeLineage: Boolean,
        val overlayDismissals: Int = 0,
        val processLineageVerified: Boolean = true,
    )

    fun decide(e: Evidence): Action {
        if (e.currentSessionPowerLogReady) return Action.BLOCK
        if (e.activeMatch != PowerLogActiveMatchProbe.State.NO_MATCH || e.priorAuthoritativeLineage) return Action.WAIT
        if (e.screen == Screen.OTHER || e.confidence < 85 || e.pid == null || e.pid <= 0L ||
            e.pid != e.currentPid || e.pid != e.windowPid || !e.processLineageVerified ||
            !e.foregroundAndPixelsVerified ||
            !e.configuredTournament || !e.working || e.manuallyPaused
        ) return Action.BLOCK
        return when (e.screen) {
            Screen.HOME -> Action.ENTER_HUB
            Screen.HOME_TASK_OVERLAY -> if (e.overlayDismissals == 0) Action.DISMISS_OVERLAY else Action.WAIT
            Screen.OTHER -> Action.BLOCK
        }
    }
}
