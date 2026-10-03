package club.xiaojiawei.hsscript.strategy.mode

/** Authoritative game-start gate shared by matchmaking scheduled tasks. */
object MatchmakingGuardPolicy {
    data class LiveGameEvidence(val inWar: Boolean, val warPhase: String, val gameId: String, val powerLogPosition: Long)
    enum class Decision { ABORT_GAME_STARTED, CONTINUE_MATCHMAKING }

    /** Rank is deliberately not part of the queue gate; rank is checked in Mulligan. */
    fun runtimeAllowsInput(
        working: Boolean,
        paused: Boolean,
        mandatoryRankSurrenderPending: Boolean = false,
    ): Boolean = working && !paused && !mandatoryRankSurrenderPending

    fun decide(evidence: LiveGameEvidence): Decision =
        if (evidence.inWar || (evidence.warPhase != "GAME_OVER" &&
                evidence.gameId.isNotBlank() && !evidence.gameId.equals("UNKNOWN", ignoreCase = true) &&
                evidence.powerLogPosition > 1L)
        ) Decision.ABORT_GAME_STARTED else Decision.CONTINUE_MATCHMAKING
}
