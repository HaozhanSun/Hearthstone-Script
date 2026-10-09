package club.xiaojiawei.hsscript.status.surrender

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless contract adapter for the app's pre-match rank permit.  The offline
 * module deliberately has no GUI/OCR dependency, so fixtures provide the
 * already-captured rank evidence and this adapter verifies its queue boundary.
 */
object OfflinePreMatchRankQueuePermit {
    const val MAX_AGE_MS = 5_000L
    const val MIN_CONFIDENCE = 0.95

    enum class OcrOutcome { SUCCESS, UNKNOWN, FAILURE, CANCELLED }

    data class Evidence(
        val rank: Int?,
        val confidence: Double?,
        val capturedAtMs: Long,
        val outcome: OcrOutcome,
        val mode: String = "TOURNAMENT",
        val phase: String = "DECK_SELECTION",
        val working: Boolean = true,
        val paused: Boolean = false,
        val mandatorySurrenderPending: Boolean = false,
        val inWar: Boolean = false,
    )

    data class Decision(val allowed: Boolean, val reason: String, val permit: Permit? = null)

    data class RuntimeEvidence(
        val working: Boolean,
        val paused: Boolean,
        val mandatorySurrenderPending: Boolean,
        val mode: String,
        val inWar: Boolean,
    )

    class Permit internal constructor(private val evidence: Evidence) {
        private val consumed = AtomicBoolean(false)

        /** Mirrors production's last-moment runtime guard immediately before input. */
        fun dispatch(runtime: RuntimeEvidence, nowMs: Long, dispatch: () -> Unit): Boolean {
            if (!runtimeAllowsQueue(runtime)) return false
            if (!isFresh(evidence.capturedAtMs, nowMs)) return false
            if (!consumed.compareAndSet(false, true)) return false
            dispatch()
            return true
        }
    }

    fun evaluate(evidence: Evidence, nowMs: Long): Decision {
        val reason = when {
            !evidence.working -> "runtime-not-working"
            evidence.paused -> "paused"
            evidence.mandatorySurrenderPending -> "mandatory-rank-surrender-pending"
            evidence.mode != "TOURNAMENT" -> "mode-mismatch"
            evidence.inWar -> "game-already-active"
            evidence.phase != "DECK_SELECTION" -> "phase-mismatch"
            evidence.outcome != OcrOutcome.SUCCESS -> "rank-ocr-${evidence.outcome.name.lowercase()}"
            !isFresh(evidence.capturedAtMs, nowMs) -> "rank-observation-stale"
            evidence.confidence == null || evidence.confidence < MIN_CONFIDENCE -> "rank-confidence-low"
            evidence.rank !in setOf(5, 10) -> "rank-not-exact-5-or-10"
            else -> null
        }
        return if (reason == null) {
            Decision(true, "fresh-exact-rank-${evidence.rank}", Permit(evidence))
        } else {
            Decision(false, reason)
        }
    }

    private fun isFresh(capturedAtMs: Long, nowMs: Long): Boolean =
        capturedAtMs > 0L && nowMs >= capturedAtMs && nowMs - capturedAtMs <= MAX_AGE_MS

    private fun runtimeAllowsQueue(runtime: RuntimeEvidence): Boolean =
        runtime.working && !runtime.paused && !runtime.mandatorySurrenderPending &&
            runtime.mode == "TOURNAMENT" && !runtime.inWar

    fun allowedRuntime() = RuntimeEvidence(
        working = true,
        paused = false,
        mandatorySurrenderPending = false,
        mode = "TOURNAMENT",
        inWar = false,
    )
}
