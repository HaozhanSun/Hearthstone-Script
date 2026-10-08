package club.xiaojiawei.hsscript.status

/** Keeps the rank-reward Continue step distinct from both the game result and its destination. */
internal object PostResultRankProgressPolicy {
    const val MAX_CONTINUE_INPUTS = 2
    enum class Action { NOT_APPLICABLE, CONTINUE, WAIT_FOR_AUTHORIZED_CAPTURE, INPUT_BUDGET_EXHAUSTED }
    enum class Input { CENTER_CLICK, KEYBOARD_ENTER }

    /** Retry the visible Continue target; the prior live Enter dispatch left this screen unchanged. */
    fun inputForAttempt(attempt: Int): Input? =
        Input.CENTER_CLICK.takeIf { attempt in 1..MAX_CONTINUE_INPUTS }

    fun decide(
        rankProgressVisible: Boolean,
        terminalCleanupAuthorized: Boolean,
        captureAuthorized: Boolean,
        rankProgressInputAttempts: Int,
    ): Action {
        if (!rankProgressVisible) return Action.NOT_APPLICABLE
        if (!terminalCleanupAuthorized || !captureAuthorized) return Action.WAIT_FOR_AUTHORIZED_CAPTURE
        if (rankProgressInputAttempts >= MAX_CONTINUE_INPUTS) return Action.INPUT_BUDGET_EXHAUSTED
        return Action.CONTINUE
    }
}
