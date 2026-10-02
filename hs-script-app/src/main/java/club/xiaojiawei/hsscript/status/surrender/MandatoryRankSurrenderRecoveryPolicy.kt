package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.status.ScreenWatchdogKind

/** Controls when a mandatory rank surrender may leave its recovery-only state. */
internal object MandatoryRankSurrenderRecoveryPolicy {
    enum class Action {
        CLICK_SETTINGS,
        CLICK_SURRENDER,
        CLICK_CONFIRMATION,
        OBSERVE_ONLY,
        COMPLETE_WIN,
        COMPLETE_LOSS,
        COMPLETE_RESULT,
        COMPLETE_MAIN_MENU,
        COMPLETE_MATCHMAKING,
    }

    data class Decision(val action: Action, val reason: String)

    internal fun shouldWaitForMoreEvidence(mandatoryRank: Boolean, screenConfirmed: Boolean): Boolean =
        mandatoryRank && !screenConfirmed

    fun decide(screen: ScreenWatchdogKind): Decision = when (screen) {
        ScreenWatchdogKind.GAMEPLAY -> Decision(Action.CLICK_SETTINGS, "confirmed-gameplay")
        ScreenWatchdogKind.SETTINGS -> Decision(Action.CLICK_SURRENDER, "confirmed-settings-with-surrender")
        ScreenWatchdogKind.SURRENDER_CONFIRMATION ->
            Decision(Action.CLICK_CONFIRMATION, "confirmed-surrender-dialog")
        ScreenWatchdogKind.WIN -> Decision(Action.COMPLETE_WIN, "terminal-win-priority")
        ScreenWatchdogKind.LOST -> Decision(Action.COMPLETE_LOSS, "terminal-loss-priority")
        ScreenWatchdogKind.RESULT -> Decision(Action.COMPLETE_RESULT, "terminal-result-priority")
        ScreenWatchdogKind.MAIN_MENU -> Decision(Action.COMPLETE_MAIN_MENU, "confirmed-main-menu")
        ScreenWatchdogKind.MATCHMAKING -> Decision(Action.COMPLETE_MATCHMAKING, "confirmed-matchmaking")
        ScreenWatchdogKind.UNKNOWN,
        ScreenWatchdogKind.CAPTURE_FAILED,
        -> Decision(Action.OBSERVE_ONLY, "screen-unconfirmed")
    }
}
