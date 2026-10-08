package club.xiaojiawei.hsscript.strategy.mode

/**
 * Authorizes the first matchmaking input from a fresh deck-selection rank
 * read.  This is deliberately separate from the in-match surrender policy:
 * there must be no queue input at all unless the pre-match badge says exactly
 * rank 5 or 10.
 */
internal object PreMatchRankGate {
    data class Result(
        val queueAuthorization: MatchmakingGuardPolicy.QueueAuthorization,
    )

    fun evaluate(
        working: Boolean,
        paused: Boolean,
        mandatoryRankSurrenderPending: Boolean,
        tournamentMode: Boolean,
        inWar: Boolean,
        observedRank: Int?,
        freshRankObservation: Boolean,
        ocrFailure: Boolean,
    ): Result {
        val runtimeAuthorization = MatchmakingGuardPolicy.authorizeQueueInput(
            working = working,
            paused = paused,
            mandatoryRankSurrenderPending = mandatoryRankSurrenderPending,
        )
        if (!runtimeAuthorization.allowed) return Result(runtimeAuthorization)

        val reason = when {
            !tournamentMode -> "pre-match-mode-mismatch"
            inWar -> "pre-match-game-already-active"
            ocrFailure -> "pre-match-rank-ocr-failed"
            !freshRankObservation -> "pre-match-rank-observation-stale"
            observedRank == null -> "pre-match-rank-unknown"
            observedRank !in setOf(5, 10) -> "pre-match-rank-not-5-or-10"
            else -> "pre-match-rank-authorized-$observedRank"
        }
        return Result(
            MatchmakingGuardPolicy.QueueAuthorization(
                allowed = observedRank in setOf(5, 10) &&
                    freshRankObservation &&
                    !ocrFailure &&
                    tournamentMode &&
                    !inWar,
                reason = reason,
            ),
        )
    }
}
