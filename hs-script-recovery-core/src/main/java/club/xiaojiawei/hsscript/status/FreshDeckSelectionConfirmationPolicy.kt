package club.xiaojiawei.hsscript.status

/** Requires a fresh, confidently classified deck-selection observation before queue recovery proceeds. */
object FreshDeckSelectionConfirmationPolicy {
    const val MIN_CONFIDENCE = 85

    fun isConfirmed(
        screenKind: String,
        confidence: Int,
        evidence: String,
        freshObservation: Boolean,
    ): Boolean = freshObservation && screenKind == "DECK_SELECTION" &&
        confidence >= MIN_CONFIDENCE && evidence.isNotBlank()
}
