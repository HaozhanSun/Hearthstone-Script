package club.xiaojiawei.hsscript.status.surrender

/** Hard authorization for entering or continuing a constructed match. */
internal object RankEligibilityPolicy {
    const val MIN_PADDLEX_CONFIDENCE = 0.90
    const val MAX_EVIDENCE_AGE_MS = 10_000L

    data class Decision(val eligible: Boolean, val reason: String)

    fun shouldDispatchMatchmaking(decision: Decision, working: Boolean, paused: Boolean): Boolean =
        decision.eligible && working && !paused

    fun evaluate(
        detection: CurrentRankDetector.Detection?,
        expectedMode: String,
        actualMode: String?,
        expectedInWar: Boolean,
        inWar: Boolean,
        nowMs: Long,
    ): Decision {
        if (actualMode != expectedMode) return Decision(false, "mode-mismatch")
        if (inWar != expectedInWar) return Decision(false, "war-state-mismatch")
        if (detection == null) return Decision(false, "rank-evidence-missing")
        if (detection.captureBounds.width <= 0 || detection.captureBounds.height <= 0) {
            return Decision(false, "rank-capture-invalid")
        }
        val ageMs = nowMs - detection.capturedAtMs
        if (detection.capturedAtMs <= 0L || ageMs < 0L || ageMs > MAX_EVIDENCE_AGE_MS) {
            return Decision(false, "rank-evidence-stale")
        }
        if (detection.rank !in setOf(5, 10)) {
            return Decision(false, if (detection.rank == null) "rank-unresolved" else "rank-not-5-or-10")
        }
        if (detection.agreementCount < 1) return Decision(false, "rank-number-not-read-by-ocr")
        // A Legendary tier and an ordinary rank numeral cannot both describe
        // the same badge. Treat the contradiction as unsafe rather than letting
        // either OCR route override the other.
        if (detection.tier == CurrentRankDetector.RankTier.LEGEND) {
            return Decision(false, "rank-tier-conflict-legend")
        }
        when (detection.provider.uppercase()) {
            "PADDLEX" -> {
                val confidence = detection.confidence
                if (confidence == null || !confidence.isFinite() || confidence < MIN_PADDLEX_CONFIDENCE) {
                    return Decision(false, "rank-confidence-below-threshold")
                }
            }
            "LEGACY" -> if (detection.agreementCount < 2) {
                return Decision(false, "legacy-rank-not-repeated")
            }
            else -> return Decision(false, "rank-provider-unverified")
        }
        return Decision(true, "verified-exact-rank-${detection.rank}")
    }
}
