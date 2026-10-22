package club.xiaojiawei.hsscript.status

/** Admit one deck-selection re-entry only after the terminal cleanup fence has cleared. */
internal object TerminalCleanupQueueResumePolicy {
    fun shouldResume(
        destinationScreen: String?,
        destinationConfirmed: Boolean,
        working: Boolean,
        paused: Boolean,
        inWar: Boolean,
        mandatoryRankSurrenderPending: Boolean,
        terminalCleanupPending: Boolean,
        deckStrategyReady: Boolean,
    ): Boolean = destinationConfirmed && destinationScreen == "DECK_SELECTION" && working && !paused && !inWar &&
        !mandatoryRankSurrenderPending && !terminalCleanupPending && deckStrategyReady
}
