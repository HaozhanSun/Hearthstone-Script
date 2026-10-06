package club.xiaojiawei.hsscript.strategy.mode

import club.xiaojiawei.hsscript.status.surrender.CurrentRankDetector
import club.xiaojiawei.hsscript.status.surrender.RankEligibilityCorePolicy
import club.xiaojiawei.hsscript.status.surrender.RankEligibilityPolicy

/**
 * Single fail-closed boundary between a pre-match rank read and queue input.
 * The supplied reader is invoked for each attempt; no rank cache is accepted.
 */
internal object PreMatchRankGate {
    data class Result(
        val detection: CurrentRankDetector.Detection?,
        val rankDecision: RankEligibilityPolicy.Decision,
        val queueAuthorization: MatchmakingGuardPolicy.QueueAuthorization,
        val captureFailure: Throwable? = null,
    )

    fun evaluate(
        working: Boolean,
        paused: Boolean,
        mandatoryRankSurrenderPending: Boolean,
        expectedMode: String,
        actualMode: String?,
        expectedInWar: Boolean,
        inWar: Boolean,
        nowMs: () -> Long,
        detectFreshRank: () -> CurrentRankDetector.Detection?,
    ): Result {
        val runtimeAllowsCapture = MatchmakingGuardPolicy.runtimeAllowsInput(
            working = working,
            paused = paused,
            mandatoryRankSurrenderPending = mandatoryRankSurrenderPending,
        )
        var captureFailure: Throwable? = null
        val detection = if (runtimeAllowsCapture) {
            try {
                detectFreshRank()
            } catch (error: Exception) {
                captureFailure = error
                null
            }
        } else {
            null
        }
        val rankDecision = RankEligibilityPolicy.evaluate(
            detection = detection,
            expectedMode = expectedMode,
            actualMode = actualMode,
            expectedInWar = expectedInWar,
            inWar = inWar,
            // Sample after OCR so a valid capture is never considered future-dated.
            nowMs = nowMs(),
        )
        val queueAuthorization = MatchmakingGuardPolicy.authorizeQueueInput(
            working = working,
            paused = paused,
            mandatoryRankSurrenderPending = mandatoryRankSurrenderPending,
            rankAuthorization = RankEligibilityCorePolicy.Decision(
                eligible = rankDecision.eligible,
                reason = rankDecision.reason,
            ),
        )
        return Result(detection, rankDecision, queueAuthorization, captureFailure)
    }
}
