package club.xiaojiawei.hsscript.status

/** A dismissal is accepted only after an observation that the result page is gone. */
internal object ResultPageDismissalPolicy {
    enum class Decision {
        DISPATCH_CLICK,
        CONFIRMED_CLEARED,
        BLOCKED_UNCONFIRMED_DURING_WAR,
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
    ): Boolean = paused || (!gameplayMode && !terminalCleanupCapabilityValid)

    fun decide(
        inWar: Boolean,
        resultPageVisible: Boolean?,
        attempt: Int,
        maxAttempts: Int,
    ): Decision = when {
        resultPageVisible == false -> Decision.CONFIRMED_CLEARED
        attempt > maxAttempts -> Decision.EXHAUSTED
        inWar && resultPageVisible != true -> Decision.BLOCKED_UNCONFIRMED_DURING_WAR
        else -> Decision.DISPATCH_CLICK
    }
}
