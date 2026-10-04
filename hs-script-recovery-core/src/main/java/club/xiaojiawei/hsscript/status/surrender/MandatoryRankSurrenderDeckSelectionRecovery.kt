package club.xiaojiawei.hsscript.status.surrender

/** Production handoff for an authoritative terminal surrender at deck selection. */
object MandatoryRankSurrenderDeckSelectionRecovery {
    enum class Result { NOT_REQUIRED, COMPLETED, BLOCKED }

    fun completeIfRequired(
        screenKind: String,
        confidence: Int,
        visualEvidence: String,
        freshObservation: Boolean,
    ): Result {
        if (!MandatoryRankSurrenderGuard.isPending() && !MandatoryRankSurrenderGuard.isTerminalCleanupPending()) {
            return Result.NOT_REQUIRED
        }
        val completed = MandatoryRankSurrenderGuard.confirmDeckSelectionCompleted(
            screenKind, confidence, visualEvidence, freshObservation,
        )
        return if (completed) Result.COMPLETED else Result.BLOCKED
    }
}
