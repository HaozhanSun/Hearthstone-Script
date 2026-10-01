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
        val rank = detection.rank
            ?: return Decision(false, "rank-unresolved")
        if (detection.agreementCount < 1) return Decision(false, "rank-number-not-read-by-ocr")
        // The pre-match visual tier probe also sees the red/orange backdrop
        // around the badge and can label a clear rank-10 numeral as LEGEND.
        // The fresh, high-confidence numeric contract is authoritative for 5
        // and 10; only ratings above 20 require independent Legend tier support.
        val eligibleRank = rank == 5 || rank == 10
        val eligibleLegendRating = rank > 20 && detection.tier == CurrentRankDetector.RankTier.LEGEND
        if (!eligibleRank && !eligibleLegendRating) {
            val reason = when {
                rank > 20 -> "legend-rating-tier-unconfirmed"
                rank in 11..20 -> "rank-not-5-or-10"
                else -> "rank-not-5-or-10"
            }
            return Decision(false, reason)
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
        return Decision(true, if (eligibleRank) "verified-exact-rank-$rank" else "verified-legend-rating-$rank")
    }
}
