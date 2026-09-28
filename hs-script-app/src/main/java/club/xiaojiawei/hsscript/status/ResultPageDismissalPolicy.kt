package club.xiaojiawei.hsscript.status

/** A dismissal is accepted only after an observation that the result page is gone. */
internal object ResultPageDismissalPolicy {
    enum class Decision {
        DISPATCH_CLICK,
        CONFIRMED_CLEARED,
        BLOCKED_UNCONFIRMED_DURING_WAR,
        EXHAUSTED,
    }

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
