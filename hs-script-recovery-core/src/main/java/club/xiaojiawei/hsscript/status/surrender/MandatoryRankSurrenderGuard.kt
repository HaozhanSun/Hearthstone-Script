package club.xiaojiawei.hsscript.status.surrender

/** Temporary lock between a mandatory rank surrender and confirmed completion. */
object MandatoryRankSurrenderGuard {
    class RecoveryCapability internal constructor()
    class TerminalCleanupCapability internal constructor()

    @Volatile private var pending = false
    @Volatile private var recoveryUncertain = false
    @Volatile private var activeCapability: RecoveryCapability? = null
    @Volatile private var activeTerminalCleanupCapability: TerminalCleanupCapability? = null

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
        return activeTerminalCleanupCapability ?: TerminalCleanupCapability().also {
            activeTerminalCleanupCapability = it
        }
    }

    fun isTerminalCleanupCapabilityValid(capability: TerminalCleanupCapability?): Boolean =
        pending && capability != null && capability === activeTerminalCleanupCapability

    fun hasTerminalCleanupCapability(): Boolean = pending && activeTerminalCleanupCapability != null

    fun markRecoveryUncertain() {
        if (pending) recoveryUncertain = true
    }

    /** Only visible terminal/out-of-game evidence releases the lock. */
    fun confirmCompleted(evidence: String): Boolean {
        if (!pending || evidence !in setOf("SCREEN_MAIN_MENU", "SCREEN_MATCHMAKING", "SCREEN_RESULT_DISMISSED")) {
            return false
        }
        completePendingSurrender()
        return true
    }

    /** Deck selection requires terminal proof plus fresh, high-confidence visual evidence. */
    @Synchronized
    fun confirmDeckSelectionCompleted(
        screenKind: String,
        confidence: Int,
        visualEvidence: String,
        freshObservation: Boolean,
    ): Boolean {
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

    fun resetForTest() {
        pending = false
        recoveryUncertain = false
        activeCapability = null
        activeTerminalCleanupCapability = null
    }
}
