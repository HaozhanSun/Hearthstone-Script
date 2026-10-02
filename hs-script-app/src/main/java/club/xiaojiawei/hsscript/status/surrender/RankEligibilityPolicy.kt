package club.xiaojiawei.hsscript.status.surrender

/** Hard authorization for continuing a constructed match after Mulligan entry. */
internal object RankEligibilityPolicy {
    const val MIN_PADDLEX_CONFIDENCE = 0.90
    const val MAX_EVIDENCE_AGE_MS = 10_000L

    data class Decision(val eligible: Boolean, val reason: String)

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
        // Validate evidence provenance before deciding whether the numeric
        // rank is eligible. Only the two exact numeric targets are allowed;
        // tier classification (including Legend) never changes this rule.
        val eligibleRank = rank == 5 || rank == 10
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
        if (!eligibleRank) return Decision(false, "rank-not-5-or-10")
        return Decision(true, "verified-exact-rank-$rank")
    }
}
