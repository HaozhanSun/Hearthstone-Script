package club.xiaojiawei.hsscript.status

/** Keeps the rank-reward Continue step distinct from both the game result and its destination. */
internal object PostResultRankProgressPolicy {
    const val MAX_CONTINUE_INPUTS = 2
    enum class Action { NOT_APPLICABLE, CONTINUE, WAIT_FOR_AUTHORIZED_CAPTURE, INPUT_BUDGET_EXHAUSTED }
    enum class Input { CENTER_CLICK, KEYBOARD_ENTER }

    /** Match the observed, bounded Unity dismissal sequence: center click, then Enter. */
    fun inputForAttempt(attempt: Int): Input? = when (attempt) {
        1 -> Input.CENTER_CLICK
        2 -> Input.KEYBOARD_ENTER
        else -> null
    }

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
