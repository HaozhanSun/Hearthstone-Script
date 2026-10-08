package club.xiaojiawei.hsscript.status.surrender

/** Provider-neutral data contract passed from OCR adapters into the shared rank authorization policy. */
data class RankEvidence(
    val rank: Int?,
    val confidence: Double?,
    val captureWidth: Int,
    val captureHeight: Int,
    val provider: String,
    val capturedAtMs: Long,
    val agreementCount: Int,
)

/** GUI/OCR-free production decision logic shared by the app and offline replay module. */
object RankEligibilityCorePolicy {
    const val MIN_PADDLEX_CONFIDENCE = 0.90
    const val MAX_EVIDENCE_AGE_MS = 10_000L
    data class Decision(val eligible: Boolean, val reason: String)

    fun evaluate(
        evidence: RankEvidence?,
        expectedMode: String,
        actualMode: String?,
        expectedInWar: Boolean,
        inWar: Boolean,
        nowMs: Long,
    ): Decision {
        if (actualMode != expectedMode) return Decision(false, "mode-mismatch")
        if (inWar != expectedInWar) return Decision(false, "war-state-mismatch")
        if (evidence == null) return Decision(false, "rank-evidence-missing")
        if (evidence.captureWidth <= 0 || evidence.captureHeight <= 0) return Decision(false, "rank-capture-invalid")
        val ageMs = nowMs - evidence.capturedAtMs
        if (evidence.capturedAtMs <= 0L || ageMs < 0L || ageMs > MAX_EVIDENCE_AGE_MS) {
            return Decision(false, "rank-evidence-stale")
        }
        val rank = evidence.rank ?: return Decision(false, "rank-unresolved")
        if (evidence.agreementCount < 1) return Decision(false, "rank-number-not-read-by-ocr")
        val eligibleRank = rank == 5 || rank == 10
        when (evidence.provider.uppercase()) {
            "PADDLEX" -> {
                val confidence = evidence.confidence
                if (confidence == null || !confidence.isFinite() || confidence < MIN_PADDLEX_CONFIDENCE) {
                    return Decision(false, "rank-confidence-below-threshold")
                }
            }
            "LEGACY" -> if (evidence.agreementCount < 2) return Decision(false, "legacy-rank-not-repeated")
            else -> return Decision(false, "rank-provider-unverified")
        }
        if (!eligibleRank) return Decision(false, "rank-not-5-or-10")
        return Decision(true, "verified-exact-rank-$rank")
    }
}
