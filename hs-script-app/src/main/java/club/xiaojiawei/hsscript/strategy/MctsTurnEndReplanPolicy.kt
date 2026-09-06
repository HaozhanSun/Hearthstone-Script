package club.xiaojiawei.hsscript.strategy

/**
 * Contract for the app-side MCTS turn-end recovery loop.
 *
 * The initial strategy invocation is planning pass zero and is not counted as
 * a re-plan. A re-plan always starts from a new live scan and a new strategy
 * invocation; the previous action/path is never reused. Exhaustion is
 * fail-closed while live actions remain: the caller must hold rather than
 * synthesize EndTurn.
 */
internal object MctsTurnEndReplanPolicy {
    const val INITIAL_PLANNING_PASS = 0
    const val MAX_REPLANS = 3

    fun totalPlanningPasses(completedReplans: Int): Int {
        require(completedReplans >= 0) { "completedReplans must not be negative" }
        return 1 + completedReplans
    }

    data class Decision(
        val shouldReplan: Boolean,
        val attempt: Int,
        val planningPass: Int,
        val freshLiveRescanRequired: Boolean,
        val reusePreviousPlan: Boolean,
        val allowEndTurnWhenExhausted: Boolean,
        val reason: String,
    )

    fun decide(completedReplans: Int, liveActionable: Boolean): Decision {
        require(completedReplans >= 0) { "completedReplans must not be negative" }
        val attempt = completedReplans + 1
        if (liveActionable && completedReplans < MAX_REPLANS) {
            return Decision(
                shouldReplan = true,
                attempt = attempt,
                planningPass = attempt,
                freshLiveRescanRequired = true,
                reusePreviousPlan = false,
                allowEndTurnWhenExhausted = false,
                reason = "live-state-still-actionable-after-strategy-return",
            )
        }
        return Decision(
            shouldReplan = false,
            attempt = attempt,
            planningPass = totalPlanningPasses(completedReplans),
            freshLiveRescanRequired = true,
            reusePreviousPlan = false,
            allowEndTurnWhenExhausted = !liveActionable,
            reason = if (liveActionable) {
                "live-state-actionable-after-replan-budget-exhausted"
            } else {
                "no-live-actionable-creator"
            },
        )
    }
}
