package club.xiaojiawei.hsscript.status

/** Trusted-screen boundary for the tournament startup action chain. */
object TournamentStartupActionPolicy {
    const val MIN_CONFIDENCE = 85
    private val actionScreens = setOf("TOURNAMENT", "DECK_SELECTION")

    fun mayStartModeSelection(
        screen: String?,
        confidence: Int,
        currentPid: Long?,
        observedPid: Long?,
        working: Boolean,
        paused: Boolean,
    ): Boolean = screen in actionScreens && confidence >= MIN_CONFIDENCE &&
        currentPid != null && currentPid > 0L && currentPid == observedPid && working && !paused
}
