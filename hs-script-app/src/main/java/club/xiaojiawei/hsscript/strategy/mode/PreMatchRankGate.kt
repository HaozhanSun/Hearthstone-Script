package club.xiaojiawei.hsscript.strategy.mode

/** Rank is intentionally deferred until the active match's Mulligan evidence exists. */
internal object PreMatchRankGate {
    data class Result(
        val queueAuthorization: MatchmakingGuardPolicy.QueueAuthorization,
    )

    fun evaluate(
        working: Boolean,
        paused: Boolean,
        mandatoryRankSurrenderPending: Boolean,
    ): Result {
        val queueAuthorization = MatchmakingGuardPolicy.authorizeQueueInput(
            working = working,
            paused = paused,
            mandatoryRankSurrenderPending = mandatoryRankSurrenderPending,
        )
        return Result(queueAuthorization)
    }
}
