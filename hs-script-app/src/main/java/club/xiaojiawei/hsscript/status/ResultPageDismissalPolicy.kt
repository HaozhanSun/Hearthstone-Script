package club.xiaojiawei.hsscript.status

/** A dismissal is accepted only after an observation that the result page is gone. */
internal object ResultPageDismissalPolicy {
    enum class Decision {
        DISPATCH_CLICK,
        CONFIRMED_CLEARED,
        BLOCKED_UNCONFIRMED_DURING_WAR,
        WAIT_FOR_SCREEN_TRANSITION,
        EXHAUSTED,
    }

    /**
     * A mandatory-rank result cleanup may outlive the GAMEPLAY mode when the
     * client has already advanced to tournament/deck selection. Only its
     * one-purpose live capability may keep that worker alive; pause always
     * cancels it.
     */
    fun shouldStopWorker(
        paused: Boolean,
        gameplayMode: Boolean,
        terminalCleanupCapabilityValid: Boolean,
        newGameDetected: Boolean = false,
    ): Boolean = paused || newGameDetected || (!gameplayMode && !terminalCleanupCapabilityValid)

    fun decide(
        inWar: Boolean,
        resultPageVisible: Boolean?,
        attempt: Int,
        maxAttempts: Int,
        clickAttempts: Int = (attempt - 1).coerceAtLeast(0),
        terminalCleanupAuthorized: Boolean = false,
    ): Decision = when {
        resultPageVisible == false -> Decision.CONFIRMED_CLEARED
        resultPageVisible == true && clickAttempts < maxAttempts -> Decision.DISPATCH_CLICK
        // A terminal cleanup capability keeps only passive observation alive
        // after its bounded click budget. It never authorizes speculative input.
        terminalCleanupAuthorized -> Decision.WAIT_FOR_SCREEN_TRANSITION
        attempt > maxAttempts -> Decision.EXHAUSTED
        resultPageVisible == true -> Decision.EXHAUSTED
        inWar -> Decision.BLOCKED_UNCONFIRMED_DURING_WAR
        else -> Decision.WAIT_FOR_SCREEN_TRANSITION
    }
}
