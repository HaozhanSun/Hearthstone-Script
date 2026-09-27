package club.xiaojiawei.hsscript.starter

/**
 * Pure startup state policy.  Battle.net is a long-lived launcher and may
 * have several helper processes, so a missing Hearthstone process means
 * "retry the game handoff" rather than "kill every Battle.net process".
 */
internal object GameStartupRecoveryPolicy {
    const val RETRY_BACKOFF_BASE_MS = 1_500L
    const val RETRY_BACKOFF_MAX_MS = 30_000L
    const val RELAUNCH_COOLDOWN_MS = 8_000L

    enum class Decision {
        WAIT_FOR_HANDOFF,
        RETRY_GAME_HANDOFF,
    }

    enum class RetryAction { NONE, STARTER_CHAIN, REATTACH_GAME_STARTER }

    fun decide(
        gameAlive: Boolean,
        startupConfirmed: Boolean,
        now: Long,
        lastLaunchAt: Long,
    ): Decision {
        if (startupConfirmed || gameAlive) return Decision.WAIT_FOR_HANDOFF
        if (lastLaunchAt > 0L && now - lastLaunchAt < RELAUNCH_COOLDOWN_MS) {
            return Decision.WAIT_FOR_HANDOFF
        }
        return Decision.RETRY_GAME_HANDOFF
    }

    /** Retry indefinitely at a capped rate; launch failure alone must not pause the user's session. */
    fun retryDelayMs(now: Long, lastLaunchAt: Long, consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures - 1).coerceAtLeast(0).coerceAtMost(5)
        val backoff = (RETRY_BACKOFF_BASE_MS * (1L shl exponent)).coerceAtMost(RETRY_BACKOFF_MAX_MS)
        val cooldownRemaining = if (lastLaunchAt > 0L) {
            (RELAUNCH_COOLDOWN_MS - (now - lastLaunchAt)).coerceAtLeast(0L)
        } else {
            0L
        }
        return maxOf(backoff, cooldownRemaining)
    }

    fun retryAction(startupConfirmed: Boolean, gameAlive: Boolean): RetryAction = when {
        startupConfirmed -> RetryAction.NONE
        gameAlive -> RetryAction.REATTACH_GAME_STARTER
        else -> RetryAction.STARTER_CHAIN
    }
}
