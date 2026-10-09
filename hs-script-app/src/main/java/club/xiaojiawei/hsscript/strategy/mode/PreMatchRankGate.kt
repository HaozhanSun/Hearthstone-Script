package club.xiaojiawei.hsscript.strategy.mode

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fail-closed authorization for the very first input that can enter the
 * matchmaking queue. A rank observed after the queue has been submitted is
 * too late: it can only surrender an already-created game.
 */
internal object PreMatchRankGate {
    const val REQUIRED_PHASE = "DECK_SELECTION"
    const val MAX_OBSERVATION_AGE_MS = 5_000L
    const val MIN_CONFIDENCE = 0.95

    enum class OcrOutcome { SUCCESS, UNKNOWN, FAILURE, CANCELLED }

    data class Evidence(
        val working: Boolean,
        val paused: Boolean,
        val mandatoryRankSurrenderPending: Boolean,
        val tournamentMode: Boolean,
        val inWar: Boolean,
        val phase: String,
        val ocrOutcome: OcrOutcome,
        val observedRank: Int?,
        val confidence: Double?,
        val capturedAtMs: Long,
    )

    data class RuntimeEvidence(
        val working: Boolean,
        val paused: Boolean,
        val mandatoryRankSurrenderPending: Boolean,
        val tournamentMode: Boolean,
        val inWar: Boolean,
    )

    data class Result(
        val queueAuthorization: MatchmakingGuardPolicy.QueueAuthorization,
        val permit: Permit? = null,
    )

    /** A permit is single-use and must still be fresh at dispatch time. */
    class Permit internal constructor(
        private val evidence: Evidence,
        private val issuedAtMs: Long,
    ) {
        private val consumed = AtomicBoolean(false)

        fun dispatchIfCurrent(
            runtime: RuntimeEvidence,
            nowMs: Long,
            dispatch: () -> Unit,
        ): Boolean {
            if (!runtimeAllowsQueue(runtime)) return false
            if (!isFresh(evidence.capturedAtMs, nowMs)) return false
            if (!consumed.compareAndSet(false, true)) return false
            dispatch()
            return true
        }

        fun reasonIfInvalid(runtime: RuntimeEvidence, nowMs: Long): String = when {
            !runtime.working -> "runtime-not-working"
            runtime.paused -> "paused"
            runtime.mandatoryRankSurrenderPending -> "mandatory-rank-surrender-pending"
            !runtime.tournamentMode -> "mode-mismatch"
            runtime.inWar -> "game-already-active"
            !isFresh(evidence.capturedAtMs, nowMs) -> "rank-observation-stale"
            consumed.get() -> "rank-permit-already-consumed"
            else -> "rank-permit-unavailable"
        }

        override fun toString(): String = "PreMatchRankPermit(issuedAtMs=$issuedAtMs)"
    }

    fun evaluate(evidence: Evidence, nowMs: Long = System.currentTimeMillis()): Result {
        val reason = when {
            !evidence.working -> "runtime-not-working"
            evidence.paused -> "paused"
            evidence.mandatoryRankSurrenderPending -> "mandatory-rank-surrender-pending"
            !evidence.tournamentMode -> "mode-mismatch"
            evidence.inWar -> "game-already-active"
            !evidence.phase.equals(REQUIRED_PHASE, ignoreCase = true) -> "phase-mismatch"
            evidence.ocrOutcome != OcrOutcome.SUCCESS -> "rank-ocr-${evidence.ocrOutcome.name.lowercase()}"
            !isFresh(evidence.capturedAtMs, nowMs) -> "rank-observation-stale"
            evidence.confidence == null || evidence.confidence < MIN_CONFIDENCE -> "rank-confidence-low"
            evidence.observedRank !in setOf(5, 10) -> "rank-not-exact-5-or-10"
            else -> null
        }
        if (reason != null) return Result(MatchmakingGuardPolicy.QueueAuthorization(false, reason))

        return Result(
            queueAuthorization = MatchmakingGuardPolicy.QueueAuthorization(true, "fresh-exact-rank-${evidence.observedRank}"),
            permit = Permit(evidence, nowMs),
        )
    }

    private fun runtimeAllowsQueue(runtime: RuntimeEvidence): Boolean =
        runtime.working && !runtime.paused && !runtime.mandatoryRankSurrenderPending &&
            runtime.tournamentMode && !runtime.inWar

    private fun isFresh(capturedAtMs: Long, nowMs: Long): Boolean =
        capturedAtMs > 0L && nowMs >= capturedAtMs && nowMs - capturedAtMs <= MAX_OBSERVATION_AGE_MS
}
