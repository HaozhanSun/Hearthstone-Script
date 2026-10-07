package club.xiaojiawei.hsscript.strategy.mode


/** Authoritative game-start gate shared by matchmaking scheduled tasks. */
object MatchmakingGuardPolicy {
    data class LiveGameEvidence(val inWar: Boolean, val warPhase: String, val gameId: String, val powerLogPosition: Long)
    enum class Decision { ABORT_GAME_STARTED, CONTINUE_MATCHMAKING }

    data class QueueAuthorization(val allowed: Boolean, val reason: String)

    /** Runtime-only gate retained for non-queue callers. */
    fun runtimeAllowsInput(
        working: Boolean,
        paused: Boolean,
        mandatoryRankSurrenderPending: Boolean = false,
    ): Boolean = working && !paused && !mandatoryRankSurrenderPending

    fun authorizeQueueInput(
        working: Boolean,
        paused: Boolean,
        mandatoryRankSurrenderPending: Boolean,
    ): QueueAuthorization {
        if (!working) return QueueAuthorization(false, "runtime-not-working")
        if (paused) return QueueAuthorization(false, "paused")
        if (mandatoryRankSurrenderPending) return QueueAuthorization(false, "mandatory-rank-surrender-pending")
        return QueueAuthorization(true, "runtime-authorized-rank-deferred-until-active-match")
    }

    /** Testable dispatch boundary: denied authorization never invokes input. */
    fun dispatchIfAuthorized(authorization: QueueAuthorization, dispatch: () -> Unit): Boolean {
        if (!authorization.allowed) return false
        dispatch()
        return true
    }

    fun decide(evidence: LiveGameEvidence): Decision =
        if (evidence.inWar || (evidence.warPhase != "GAME_OVER" &&
                evidence.gameId.isNotBlank() && !evidence.gameId.equals("UNKNOWN", ignoreCase = true) &&
                evidence.powerLogPosition > 1L)
        ) Decision.ABORT_GAME_STARTED else Decision.CONTINUE_MATCHMAKING
}
