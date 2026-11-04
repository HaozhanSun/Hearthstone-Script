package club.xiaojiawei.hsscript.status

/**
 * The monthly season-reward sequence is user-owned.  It may contain a
 * Continue-looking control or closed chests, but neither authorizes scripted
 * input: notify once and hold automation until the user dismisses it.
 */
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
        val monthlyRewardScreen = screenKind in setOf(
            "SEASON_RANK_AWARD",
            "SEASON_RANK_AWARD_CHEST",
            "SEASON_RANK_AWARD_REWARDS",
        )
        if (!monthlyRewardScreen && screenKind == null && rewardPresentationVisible) {
            val notify = !modalActive
            modalActive = true
            if (notify) notifyUser()
            holdAutomation()
            return Observation(holdAutomation = true, notifyUser = notify)
        }
        // An exact recognized monthly-reward screen is the modal identity;
        // its enabled-looking button never changes the no-input policy.
        if (!monthlyRewardScreen) {
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
