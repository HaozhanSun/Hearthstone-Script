package club.xiaojiawei.hsscript.strategy.mode

import club.xiaojiawei.hsscript.status.surrender.RankEligibilityCorePolicy

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

    /**
     * Authorize constructed matchmaking only from fresh, current-mode rank
     * evidence. This is deliberately separate from the later mulligan check:
     * the queue must not be entered before rank eligibility is established.
     */
    fun authorizeQueueInput(
        working: Boolean,
        paused: Boolean,
        mandatoryRankSurrenderPending: Boolean,
        rankAuthorization: RankEligibilityCorePolicy.Decision,
    ): QueueAuthorization {
        if (!working) return QueueAuthorization(false, "runtime-not-working")
        if (paused) return QueueAuthorization(false, "paused")
        if (mandatoryRankSurrenderPending) return QueueAuthorization(false, "mandatory-rank-surrender-pending")
        if (!rankAuthorization.eligible) return QueueAuthorization(false, rankAuthorization.reason)
        return QueueAuthorization(true, rankAuthorization.reason)
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
