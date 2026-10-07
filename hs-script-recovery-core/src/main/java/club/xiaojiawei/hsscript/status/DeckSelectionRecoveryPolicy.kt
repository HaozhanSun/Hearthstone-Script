package club.xiaojiawei.hsscript.status

/**
 * A recovery observation is not progress when the state machine already
 * represents the visible deck-selection screen. Re-queuing from that same
 * fingerprint can reset the watchdog without changing the screen.
 */
object DeckSelectionRecoveryPolicy {
    fun shouldApply(
        screenKind: String,
        currentMode: String?,
        currentPhase: String,
    ): Boolean = screenKind != "DECK_SELECTION" ||
        currentMode != "TOURNAMENT" ||
        currentPhase != "FILL_DECK"
}
