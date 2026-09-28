package club.xiaojiawei.hsscript.strategy

/**
 * Contract for the app-side MCTS turn-end recovery loop.
 *
 * The initial strategy invocation is planning pass zero and is not counted as
 * a re-plan. A re-plan always starts from a new live scan and a new strategy
 * invocation; the previous action/path is never reused. If the re-plan budget
 * is exhausted, the caller must record the diagnostic state and finish the turn
 * instead of holding the game indefinitely.
 */
internal object MctsTurnEndReplanPolicy {
    const val INITIAL_PLANNING_PASS = 0
    const val MAX_REPLANS = 3
    const val MAX_END_TURN_DISPATCH_ATTEMPTS = 20

    enum class ExhaustionFallbackResult {
        NOT_SELECTED,
        PAUSED,
        TURN_NOT_ACTIVE,
        DISPATCHED,
    }

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
            allowEndTurnWhenExhausted = true,
            reason = if (liveActionable) {
                "live-state-actionable-after-replan-budget-exhausted"
            } else {
                "no-live-actionable-creator"
            },
        )
    }

    /**
     * Runs the selected fallback without changing pause state. Manual pause and
     * a turn/mode transition remain authoritative at the dispatch boundary.
     */
    fun dispatchExhaustionFallback(
        decision: Decision,
        paused: Boolean,
        turnActive: Boolean,
        dispatch: () -> Unit,
    ): ExhaustionFallbackResult {
        if (!decision.allowEndTurnWhenExhausted) return ExhaustionFallbackResult.NOT_SELECTED
        if (paused) return ExhaustionFallbackResult.PAUSED
        if (!turnActive) return ExhaustionFallbackResult.TURN_NOT_ACTIVE
        dispatch()
        return ExhaustionFallbackResult.DISPATCHED
    }

    /**
     * Executes bounded end-turn attempts while the caller's live turn/pause
     * predicate remains true. Re-checking before every attempt makes an F2
     * pause or turn transition stop retries immediately.
     */
    fun runBoundedEndTurnAttempts(
        shouldContinue: () -> Boolean,
        attempt: (attemptNumber: Int) -> Unit,
    ): Int {
        var attempts = 0
        while (attempts < MAX_END_TURN_DISPATCH_ATTEMPTS && shouldContinue()) {
            attempts++
            attempt(attempts)
        }
        return attempts
    }
}
