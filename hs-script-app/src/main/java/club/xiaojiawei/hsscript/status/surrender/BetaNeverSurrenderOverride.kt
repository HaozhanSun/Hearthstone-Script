package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.status.DebugRunLease

/** Process-local Beta-only rank bypass; uncertain or stale rank evidence keeps the normal fail-closed policy. */
internal class BetaNeverSurrenderOverride(
    private val betaBuild: Boolean,
    private val neverSurrenderEnabled: () -> Boolean,
    private val debugRunLease: () -> DebugRunLease.Snapshot,
    private val maxRankAgeMs: Long = MAX_RANK_AGE_MS,
) {
    data class ActiveLease internal constructor(
        val startEpochMillis: Long,
        val endEpochMillis: Long,
    )

    init {
        require(maxRankAgeMs >= 0)
    }

    /**
     * This is deliberately narrower than the normal Never Surrender setting:
     * it is valid only for the current, full-duration Beta Debug Run lease.
     * A persisted toggle, an expired lease, or a shortened/non-Beta lease
     * cannot relax the mandatory exact-rank policy.
     */
    fun active(nowMs: Long): Boolean = activeLease(nowMs) != null

    /** A stable identity for the full lease that admitted the initial OCR proof. */
    fun activeLease(nowMs: Long): ActiveLease? {
        val snapshot = debugRunLease()
        val duration = snapshot.endEpochMillis - snapshot.startEpochMillis
        val active = betaBuild && neverSurrenderEnabled() &&
            snapshot.state == DebugRunLease.State.ACTIVE &&
            snapshot.startEpochMillis > 0L &&
            duration == DebugRunLease.MAX_DURATION_MILLIS &&
            nowMs >= snapshot.startEpochMillis && nowMs < snapshot.endEpochMillis
        return if (active) ActiveLease(snapshot.startEpochMillis, snapshot.endEpochMillis) else null
    }

    /**
     * Fresh OCR is required to open the barrier, not for every later card
     * action.  Later actions instead prove that the identical bounded lease
     * and the live toggle are still active.
     */
    fun isSameLeaseStillActive(lease: ActiveLease, nowMs: Long): Boolean =
        activeLease(nowMs) == lease

    val expiresAtMs: Long
        get() = debugRunLease().endEpochMillis

    fun allowsTemporaryContinue(detection: CurrentRankDetector.Detection?, nowMs: Long): Boolean {
        if (!active(nowMs) || detection == null) return false
        if (detection.rank !in 1..10) return false
        val capturedAt = detection.capturedAtMs
        if (capturedAt <= 0L || capturedAt > nowMs) return false
        if (nowMs - capturedAt > maxRankAgeMs) return false
        if (detection.captureBounds.width <= 0 || detection.captureBounds.height <= 0) return false
        if (detection.agreementCount < 1) return false
        return when (detection.provider.uppercase()) {
            "PADDLEX" -> detection.confidence?.let { it.isFinite() && it >= 0.90 } == true
            "LEGACY" -> detection.agreementCount >= 2
            else -> false
        }
    }

    companion object {
        const val DURATION_MS = 30 * 60 * 1_000L
        const val MAX_RANK_AGE_MS = 10_000L
    }
}
