package club.xiaojiawei.hsscript.strategy.mode

/**
 * Authorizes only the runtime portion of the matchmaking entry path. Rank
 * evidence belongs to the active game's Mulligan preflight, where it has a
 * current-game identity and can safely trigger the mandatory surrender flow.
 */
internal object PreMatchRankGate {
    data class Result(
        val queueAuthorization: MatchmakingGuardPolicy.QueueAuthorization,
    )

    fun evaluate(
        working: Boolean,
        paused: Boolean,
        mandatoryRankSurrenderPending: Boolean,
    ): Result {
        return Result(MatchmakingGuardPolicy.authorizeQueueInput(
            working = working,
            paused = paused,
            mandatoryRankSurrenderPending = mandatoryRankSurrenderPending,
        ))
    }
}
