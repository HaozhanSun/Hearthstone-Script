package club.xiaojiawei.hsscript.starter

/**
 * Pure startup state policy.  Battle.net is a long-lived launcher and may
 * have several helper processes, so a missing Hearthstone process means
 * "retry the game handoff" rather than "kill every Battle.net process".
 */
internal object GameStartupRecoveryPolicy {
    const val MAX_FAILURES = 3
    const val RELAUNCH_COOLDOWN_MS = 8_000L

    enum class Decision {
        WAIT_FOR_HANDOFF,
        RETRY_GAME_HANDOFF,
        PAUSE_WITH_DIAGNOSTIC,
    }

    fun decide(
        gameAlive: Boolean,
        startupConfirmed: Boolean,
        now: Long,
        lastLaunchAt: Long,
        consecutiveFailures: Int,
    ): Decision {
        if (startupConfirmed || gameAlive) return Decision.WAIT_FOR_HANDOFF
        if (consecutiveFailures >= MAX_FAILURES) return Decision.PAUSE_WITH_DIAGNOSTIC
        if (lastLaunchAt > 0L && now - lastLaunchAt < RELAUNCH_COOLDOWN_MS) {
            return Decision.WAIT_FOR_HANDOFF
        }
        return Decision.RETRY_GAME_HANDOFF
    }
}
