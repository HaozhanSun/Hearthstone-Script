package club.xiaojiawei.hsscript.status

/** One notification per visible blank-Continue monthly reward modal occurrence. */
internal class SeasonRankAwardHumanInterventionPolicy {
    data class Observation(val holdAutomation: Boolean, val notifyUser: Boolean)

    private val clearlyDifferentScreens = setOf(
        "DECK_SELECTION", "HOME", "TOURNAMENT", "MATCHMAKING", "LOGIN", "GAME_MODE", "COLLECTION", "PACK_OPENING",
    )
    private var modalActive = false

    @Synchronized
    fun observe(
        screenKind: String?,
        rewardPresentationVisible: Boolean,
        continueVisible: Boolean,
        freshAuthorizedCapture: Boolean,
        stateStillCurrent: Boolean,
        notifyUser: () -> Unit = {},
        holdAutomation: () -> Unit = {},
    ): Observation {
        if (!freshAuthorizedCapture || !stateStillCurrent) {
            return Observation(holdAutomation = false, notifyUser = false)
        }
        // The five-chest structural visual is the modal identity; OCR labels
        // and the Continue enabled state are not needed to classify it.
        if (!rewardPresentationVisible || continueVisible) {
            // UNKNOWN or a stale capture does not prove that this occurrence
            // ended. Only a fresh, recognized other screen re-arms.
            if (!rewardPresentationVisible && screenKind in clearlyDifferentScreens) modalActive = false
            return Observation(holdAutomation = false, notifyUser = false)
        }

        val notify = !modalActive
        modalActive = true
        if (notify) notifyUser()
        holdAutomation()
        return Observation(holdAutomation = true, notifyUser = notify)
    }
}
