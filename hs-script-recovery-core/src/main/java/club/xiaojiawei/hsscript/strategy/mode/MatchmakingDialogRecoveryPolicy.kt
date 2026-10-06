package club.xiaojiawei.hsscript.strategy.mode

/** Pure production policy for bounded recovery of the exact startup matchmaking error dialog. */
object MatchmakingDialogRecoveryPolicy {
    const val MAX_ATTEMPTS = 20
    const val MIN_OCR_CONFIDENCE = 0.55

    enum class Probe { ERROR_DIALOG_VISIBLE, NO_ERROR_DIALOG, UNKNOWN }
    enum class Action { CLICK_CONFIRM, WAIT_AND_RETRY, CONFIRMED_DISMISSED, STOP_NOT_PRESENT, CANCEL, EXHAUSTED }

    data class Context(
        val paused: Boolean,
        val tournamentMode: Boolean,
        val gameStarted: Boolean,
    )

    data class Decision(val action: Action, val reason: String)

    /** Scheduler overlap is not a completed observation and must not exhaust real probe attempts. */
    fun countsTowardAttemptBudget(probeReason: String): Boolean = probeReason != "probe-in-flight"

    fun decide(context: Context, probe: Probe, completedAttempts: Int, priorClickSent: Boolean): Decision {
        if (context.paused) return Decision(Action.CANCEL, "paused")
        if (!context.tournamentMode) return Decision(Action.CANCEL, "mode-changed")
        if (context.gameStarted) return Decision(Action.CANCEL, "game-started")
        if (probe == Probe.NO_ERROR_DIALOG) {
            return if (priorClickSent) {
                Decision(Action.CONFIRMED_DISMISSED, "post-click-dialog-disappeared")
            } else {
                Decision(Action.STOP_NOT_PRESENT, "dialog-not-present")
            }
        }
        if (completedAttempts >= MAX_ATTEMPTS) return Decision(Action.EXHAUSTED, "attempt-limit")
        return when (probe) {
            Probe.ERROR_DIALOG_VISIBLE -> Decision(Action.CLICK_CONFIRM, "exact-error-dialog-visible")
            Probe.UNKNOWN -> Decision(Action.WAIT_AND_RETRY, "dialog-state-unverified")
            Probe.NO_ERROR_DIALOG -> error("handled above")
        }
    }

    /** Dispatch exactly the positive confirm decision and return the input adapter's acceptance. */
    fun dispatchConfirm(decision: Decision, clickConfirm: () -> Boolean): Boolean? =
        if (decision.action == Action.CLICK_CONFIRM) clickConfirm() else null
}
