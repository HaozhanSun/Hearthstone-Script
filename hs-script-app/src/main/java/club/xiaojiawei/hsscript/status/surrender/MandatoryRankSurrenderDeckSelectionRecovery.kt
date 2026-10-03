package club.xiaojiawei.hsscript.status.surrender

/** Shared production/test handoff for a terminal surrender observed at deck selection. */
internal object MandatoryRankSurrenderDeckSelectionRecovery {
    enum class Result { NOT_REQUIRED, COMPLETED, BLOCKED }

    fun completeIfRequired(
        screenKind: String,
        confidence: Int,
        visualEvidence: String,
        freshObservation: Boolean,
    ): Result {
        if (!MandatoryRankSurrenderGuard.isPending()) return Result.NOT_REQUIRED
        val completed = MandatoryRankSurrenderGuard.confirmDeckSelectionCompleted(
            screenKind = screenKind,
            confidence = confidence,
            visualEvidence = visualEvidence,
            freshObservation = freshObservation,
        )
        return if (completed) Result.COMPLETED else Result.BLOCKED
    }
}
