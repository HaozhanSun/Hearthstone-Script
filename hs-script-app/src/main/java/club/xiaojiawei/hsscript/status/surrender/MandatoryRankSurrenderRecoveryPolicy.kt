package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.status.ScreenWatchdogKind

/** Controls when a mandatory rank surrender may leave its recovery-only state. */
internal object MandatoryRankSurrenderRecoveryPolicy {
    const val MAX_RETRY_ATTEMPTS = 30

    enum class ConfirmationTarget { ACCEPT_NOW }

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

    data class Decision(
        val action: Action,
        val reason: String,
        val confirmationTarget: ConfirmationTarget? = null,
    )

    internal fun shouldWaitForMoreEvidence(mandatoryRank: Boolean, screenConfirmed: Boolean): Boolean =
        mandatoryRank && !screenConfirmed

    internal fun shouldEmitUnknownObservationDiagnostic(consecutiveUnknownScreens: Int): Boolean =
        consecutiveUnknownScreens == 1 || consecutiveUnknownScreens > 1 && consecutiveUnknownScreens % 3 == 0

    internal fun hasRetryBudget(attemptsAlreadyStarted: Int): Boolean =
        attemptsAlreadyStarted < MAX_RETRY_ATTEMPTS

    /**
     * A fresh active-game rank denial is already authoritative evidence that
     * ordinary input is forbidden. Its first recovery pass must capture and
     * classify the current game immediately; waiting for the generic
     * no-progress watchdog threshold can leave the Mulligan blocked for
     * minutes without ever attempting the Settings path.
     */
    internal fun shouldInspectImmediatelyAfterRankResolution(attemptsAlreadyStarted: Int): Boolean =
        attemptsAlreadyStarted == 0

    /** Cooldown/in-flight scheduler ticks do not consume an inspection retry. */
    internal fun attemptsAfterInspectionStart(attemptsAlreadyStarted: Int, inspectionStarted: Boolean): Int =
        if (inspectionStarted && hasRetryBudget(attemptsAlreadyStarted)) attemptsAlreadyStarted + 1
        else attemptsAlreadyStarted

    fun decide(screen: ScreenWatchdogKind): Decision = when (screen) {
        ScreenWatchdogKind.GAMEPLAY -> Decision(Action.CLICK_SETTINGS, "confirmed-gameplay")
        ScreenWatchdogKind.MULLIGAN -> Decision(Action.CLICK_SETTINGS, "confirmed-mulligan-with-rank-deny")
        ScreenWatchdogKind.SETTINGS -> Decision(Action.CLICK_SURRENDER, "confirmed-settings-with-surrender")
        ScreenWatchdogKind.SURRENDER_CONFIRMATION ->
            Decision(
                Action.CLICK_CONFIRMATION,
                "confirmed-surrender-dialog-accept-now",
                ConfirmationTarget.ACCEPT_NOW,
            )
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
