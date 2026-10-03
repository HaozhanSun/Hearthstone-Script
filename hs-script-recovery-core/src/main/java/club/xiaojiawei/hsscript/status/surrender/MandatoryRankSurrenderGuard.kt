package club.xiaojiawei.hsscript.status.surrender

/** Temporary lock between a mandatory rank surrender and confirmed completion. */
object MandatoryRankSurrenderGuard {
    class RecoveryCapability internal constructor()
    class TerminalCleanupCapability internal constructor()

    @Volatile private var pending = false
    @Volatile private var recoveryUncertain = false
    @Volatile private var activeCapability: RecoveryCapability? = null
    @Volatile private var activeTerminalCleanupCapability: TerminalCleanupCapability? = null
    @Volatile private var pendingGameIdentity: String? = null

    @Synchronized
    fun begin(gameIdentity: String? = null): RecoveryCapability {
        if (pending) {
            // Duplicate callbacks for the same match must not invalidate the
            // already-issued recovery token or reset terminal proof.
            if (pendingGameIdentity == gameIdentity) return requireNotNull(activeCapability)
            return requireNotNull(activeCapability)
        }
        recoveryUncertain = false
        val capability = RecoveryCapability()
        activeCapability = capability
        activeTerminalCleanupCapability = null
        pendingGameIdentity = gameIdentity?.takeIf { it.isNotBlank() }
        pending = true
        return capability
    }

    fun isRecoveryCapabilityValid(capability: RecoveryCapability?): Boolean =
        pending && capability != null && capability === activeCapability

    /** Issue cleanup only from complete, current-game Power.log surrender evidence. */
    @Synchronized
    fun authorizeTerminalCleanup(
        evidence: CurrentGameSurrenderTerminalEvidence?,
    ): TerminalCleanupCapability? {
        if (!pending || !MandatorySurrenderTerminalEvidence.authorizes(pendingGameIdentity, evidence)) return null
        return activeTerminalCleanupCapability ?: TerminalCleanupCapability().also {
            activeTerminalCleanupCapability = it
        }
    }

    fun isTerminalCleanupCapabilityValid(capability: TerminalCleanupCapability?): Boolean =
        pending && capability != null && capability === activeTerminalCleanupCapability

    /** Screen paths may reuse proof already authorized by Power.log, never mint it. */
    fun existingTerminalCleanupCapability(): TerminalCleanupCapability? =
        activeTerminalCleanupCapability.takeIf { pending }

    fun hasTerminalCleanupCapability(): Boolean = pending && activeTerminalCleanupCapability != null

    fun markRecoveryUncertain() {
        if (pending) recoveryUncertain = true
    }

    /** A screen transition may finish recovery only after current-game Power.log proof was authorized. */
    fun confirmCompleted(evidence: String): Boolean =
        confirmCompleted(evidence, existingTerminalCleanupCapability())

    @Synchronized
    fun confirmCompleted(
        evidence: String,
        terminalCleanupCapability: TerminalCleanupCapability?,
    ): Boolean {
        if (!pending || evidence !in setOf("SCREEN_MAIN_MENU", "SCREEN_MATCHMAKING", "SCREEN_RESULT_DISMISSED")) {
            return false
        }
        if (!isTerminalCleanupCapabilityValid(terminalCleanupCapability)) return false
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
        pendingGameIdentity = null
        MulliganRankDispatchBarrier.completeSurrender()
    }

    fun isPending(): Boolean = pending
    fun isRecoveryUncertain(): Boolean = pending && recoveryUncertain

    fun resetForTest() {
        pending = false
        recoveryUncertain = false
        activeCapability = null
        activeTerminalCleanupCapability = null
        pendingGameIdentity = null
    }
}
