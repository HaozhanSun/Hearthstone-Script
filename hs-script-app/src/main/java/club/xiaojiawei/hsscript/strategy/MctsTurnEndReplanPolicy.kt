package club.xiaojiawei.hsscript.strategy

/**
 * Contract for the app-side MCTS turn-end recovery loop.
 *
 * The initial strategy invocation is planning pass zero and is not counted as
 * a re-plan. A re-plan always starts from a new live scan and a new strategy
 * invocation; the previous action/path is never reused. If the re-plan budget
 * is exhausted, the caller may finish the turn only when a fresh live scan is
 * genuinely non-actionable. An actionable fresh scan must never become an
 * EndTurn fallback.
 */
internal object MctsTurnEndReplanPolicy {
    const val INITIAL_PLANNING_PASS = 0
    const val MAX_REPLANS = 5
    const val MAX_ACTION_RECOVERY_RETRIES = 1

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

    data class ActionRecoveryDecision(
        val retryFreshPlan: Boolean,
        val enterRecoveryWatch: Boolean,
        val allowEndTurn: Boolean,
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

    /** Decide the bounded safety action after the normal re-plan budget. */
    fun decideActionRecovery(
        completedRecoveryRetries: Int,
        liveActionable: Boolean,
        freshLiveScan: Boolean,
    ): ActionRecoveryDecision {
        require(completedRecoveryRetries >= 0) { "completedRecoveryRetries must not be negative" }
        if (!freshLiveScan) {
            return ActionRecoveryDecision(
                retryFreshPlan = true,
                enterRecoveryWatch = false,
                allowEndTurn = false,
                reason = "live-scan-stale-retry-before-recovery",
            )
        }
        if (liveActionable && completedRecoveryRetries < MAX_ACTION_RECOVERY_RETRIES) {
            return ActionRecoveryDecision(
                retryFreshPlan = true,
                enterRecoveryWatch = false,
                allowEndTurn = false,
                reason = "fresh-live-actionable-bounded-retry",
            )
        }
        if (liveActionable) {
            return ActionRecoveryDecision(
                retryFreshPlan = false,
                enterRecoveryWatch = true,
                allowEndTurn = false,
                reason = "fresh-live-actionable-recovery-watch",
            )
        }
        return ActionRecoveryDecision(
            retryFreshPlan = false,
            enterRecoveryWatch = false,
            allowEndTurn = true,
            reason = "fresh-live-non-actionable-normal-end-turn",
        )
    }
}
