package club.xiaojiawei.hsscript.status

/** A terminal cleanup is complete only after a fresh, recognized deck or queue screen. */
object FreshPostResultDestinationPolicy {
    const val MIN_CONFIDENCE = 85

    fun isConfirmed(screenKind: String?, confidence: Int, freshCaptureAuthorized: Boolean): Boolean =
        freshCaptureAuthorized && confidence >= MIN_CONFIDENCE &&
            (screenKind == "DECK_SELECTION" || screenKind == "MATCHMAKING")
}
