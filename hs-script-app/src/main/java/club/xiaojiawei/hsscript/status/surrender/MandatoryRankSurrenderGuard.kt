package club.xiaojiawei.hsscript.status.surrender

/** Temporary lock between a mandatory rank surrender and confirmed completion. */
object MandatoryRankSurrenderGuard {
    class RecoveryCapability internal constructor()
    class TerminalCleanupCapability internal constructor()

    @Volatile
    private var pending = false

    @Volatile
    private var recoveryUncertain = false

    @Volatile
    private var activeCapability: RecoveryCapability? = null

    @Volatile
    private var activeTerminalCleanupCapability: TerminalCleanupCapability? = null

    @Synchronized
    fun begin(): RecoveryCapability {
        recoveryUncertain = false
        val capability = RecoveryCapability()
        activeCapability = capability
        activeTerminalCleanupCapability = null
        pending = true
        return capability
    }

    fun isRecoveryCapabilityValid(capability: RecoveryCapability?): Boolean =
        pending && capability != null && capability === activeCapability

    /** Issue a one-purpose result-page dismissal token only after terminal evidence. */
    @Synchronized
    fun authorizeTerminalCleanup(evidence: String): TerminalCleanupCapability? {
        if (!pending || evidence !in setOf("POWERLOG_TERMINAL", "SCREEN_TERMINAL")) return null
        // Several independent terminal observers can report the same result
        // (Power.log, the phase handler, and screen recovery). Keep the first
        // live capability stable so a later observation cannot invalidate a
        // queued result-dismissal worker that already captured it.
        return activeTerminalCleanupCapability ?: TerminalCleanupCapability().also {
            activeTerminalCleanupCapability = it
        }
    }

    fun isTerminalCleanupCapabilityValid(capability: TerminalCleanupCapability?): Boolean =
        pending && capability != null && capability === activeTerminalCleanupCapability

    internal fun hasTerminalCleanupCapability(): Boolean =
        pending && activeTerminalCleanupCapability != null

    fun markRecoveryUncertain() {
        if (pending) recoveryUncertain = true
    }

    /** Only visible terminal/out-of-game evidence may release the lock; Power.log authorizes cleanup, not UI completion. */
    fun confirmCompleted(evidence: String): Boolean {
        val allowedEvidence = setOf(
            "SCREEN_MAIN_MENU",
            "SCREEN_MATCHMAKING",
            "SCREEN_RESULT_DISMISSED",
        )
        if (!pending || evidence !in allowedEvidence) return false
        completePendingSurrender()
        return true
    }

    /**
     * Deck selection is a valid post-result destination, but may complete a
     * mandatory surrender only with its already-issued terminal capability
     * and a fresh, high-confidence screen-recovery observation.
     */
    @Synchronized
    internal fun confirmDeckSelectionCompleted(
        screenKind: String,
        confidence: Int,
        visualEvidence: String,
        freshObservation: Boolean,
    ): Boolean {
        // Deliberately require a capability already minted by this surrender's
        // authoritative terminal observer; a deck screenshot cannot create it.
        if (!pending || activeTerminalCleanupCapability == null) return false
        if (!freshObservation || screenKind != "DECK_SELECTION" || confidence < 85) return false
        if (visualEvidence !in setOf("deck-selection-title", "deck-selection-title-roi")) return false
        if (MulliganRankDispatchBarrier.currentState() != MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED) {
            return false
        }
        completePendingSurrender()
        return true
    }

    private fun completePendingSurrender() {
        pending = false
        recoveryUncertain = false
        activeCapability = null
        activeTerminalCleanupCapability = null
        MulliganRankDispatchBarrier.completeSurrender()
    }

    fun isPending(): Boolean = pending

    fun isRecoveryUncertain(): Boolean = pending && recoveryUncertain

    internal fun resetForTest() {
        pending = false
        recoveryUncertain = false
        activeCapability = null
        activeTerminalCleanupCapability = null
    }
}
