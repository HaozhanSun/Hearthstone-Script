package club.xiaojiawei.hsscript.status.surrender

/** App adapter that converts OCR output into the GUI-free production policy contract. */
internal object RankEligibilityPolicy {
    data class Decision(val eligible: Boolean, val reason: String)

    fun evaluate(
        detection: CurrentRankDetector.Detection?,
        expectedMode: String,
        actualMode: String?,
        expectedInWar: Boolean,
        inWar: Boolean,
        nowMs: Long,
    ): Decision {
        val evidence = detection?.let {
            RankEvidence(
                rank = it.rank,
                confidence = it.confidence,
                captureWidth = it.captureBounds.width,
                captureHeight = it.captureBounds.height,
                provider = it.provider,
                capturedAtMs = it.capturedAtMs,
                agreementCount = it.agreementCount,
            )
        }
        val core = RankEligibilityCorePolicy.evaluate(
            evidence = evidence,
            expectedMode = expectedMode,
            actualMode = actualMode,
            expectedInWar = expectedInWar,
            inWar = inWar,
            nowMs = nowMs,
        )
        return Decision(core.eligible, core.reason)
    }
}
