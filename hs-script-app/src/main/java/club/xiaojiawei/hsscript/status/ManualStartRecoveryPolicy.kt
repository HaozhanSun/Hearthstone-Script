package club.xiaojiawei.hsscript.status

/** Keeps explicit production Start separate from an expired Debug/Test Run lease. */
internal object ManualStartRecoveryPolicy {
    enum class Decision { START, START_AFTER_DEBUG_EXPIRY, REARM_STALE_SESSION, RESUME_EXISTING_SESSION, BLOCK_OUTSIDE_ALLOWED_WINDOW }

    fun evaluate(
        scheduleAllowsStart: Boolean,
        working: Boolean,
        currentGameSessionReady: Boolean,
        debugRunExpired: Boolean,
        activeGameOrMatchmaking: Boolean = false,
    ): Decision {
        if (!scheduleAllowsStart) return Decision.BLOCK_OUTSIDE_ALLOWED_WINDOW
        if (activeGameOrMatchmaking) return Decision.RESUME_EXISTING_SESSION
        if (working && !currentGameSessionReady) return Decision.REARM_STALE_SESSION
        // An expired debug lease does not invalidate a normal schedule or an
        // explicit startup window. It also never grants work when both are closed.
        return if (debugRunExpired) Decision.START_AFTER_DEBUG_EXPIRY else Decision.START
    }
}
