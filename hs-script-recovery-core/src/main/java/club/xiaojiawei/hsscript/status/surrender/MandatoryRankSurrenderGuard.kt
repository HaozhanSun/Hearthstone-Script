package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.status.FreshDeckSelectionConfirmationPolicy

/** Temporary lock between a mandatory rank surrender and confirmed completion. */
object MandatoryRankSurrenderGuard {
    class RecoveryCapability internal constructor()
    class TerminalCleanupCapability internal constructor()

    @Volatile private var pending = false
    @Volatile private var recoveryUncertain = false
    @Volatile private var activeCapability: RecoveryCapability? = null
    @Volatile private var activeTerminalCleanupCapability: TerminalCleanupCapability? = null
    @Volatile private var pendingGameIdentity: String? = null
    @Volatile private var terminalCleanupPending = false

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
        terminalCleanupPending = false
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
        if (!pending || terminalCleanupPending ||
            !MandatorySurrenderTerminalEvidence.authorizes(pendingGameIdentity, evidence)
        ) return null
        return activeTerminalCleanupCapability ?: TerminalCleanupCapability().also {
            activeTerminalCleanupCapability = it
        }
    }

    /**
     * Authorize bounded terminal-screen cleanup for a complete local result
     * when no mandatory-surrender recovery is pending. A pending mandatory
     * recovery must still pass the stricter surrender-specific evidence path.
     */
    @Synchronized
    fun authorizeCurrentGameResultCleanup(
        evidence: CurrentGameSurrenderTerminalEvidence?,
    ): TerminalCleanupCapability? {
        if (pending) {
            authorizeTerminalCleanup(evidence)?.let { return it }
            // A terminal, complete local result proves the match has already
            // ended even if the surrender request identity was lost or stale.
            // It may release only the terminal dismissal capability; ordinary
            // gameplay remains fenced until the result transition is verified.
            if (!CurrentGameResultCleanupEvidence.authorizes(evidence)) return null
            val capability = activeTerminalCleanupCapability ?: TerminalCleanupCapability()
            releaseRankBarrierForTerminalCleanup()
            activeTerminalCleanupCapability = capability
            return capability
        }
        if (terminalCleanupPending) return activeTerminalCleanupCapability
        if (!CurrentGameResultCleanupEvidence.authorizes(evidence)) return null
        MulliganRankDispatchBarrier.completeForCurrentTerminalResult()
        terminalCleanupPending = true
        return TerminalCleanupCapability().also { activeTerminalCleanupCapability = it }
    }

    /**
     * Reconcile a late, complete current-game Power.log terminal block.
     *
     * GAME_OVER may be entered before the final STATE=COMPLETE line is
     * consumed.  Callers that observe that tail can atomically mint the
     * already-authorized cleanup capability and release the Mulligan barrier;
     * incomplete or mismatched evidence remains fail-closed.
     */
    @Synchronized
    fun reconcileTerminalEvidence(
        evidence: CurrentGameSurrenderTerminalEvidence?,
    ): TerminalCleanupCapability? {
        val capability = authorizeTerminalCleanup(evidence) ?: return null
        return capability.takeIf { confirmCompleted("POWERLOG_TERMINAL", it) }
    }

    fun isTerminalCleanupCapabilityValid(capability: TerminalCleanupCapability?): Boolean =
        (pending || terminalCleanupPending) && capability != null && capability === activeTerminalCleanupCapability

    /** Screen paths may reuse proof already authorized by Power.log, never mint it. */
    fun existingTerminalCleanupCapability(): TerminalCleanupCapability? =
        activeTerminalCleanupCapability.takeIf { pending || terminalCleanupPending }

    fun hasTerminalCleanupCapability(): Boolean =
        (pending || terminalCleanupPending) && activeTerminalCleanupCapability != null

    /** Terminal proof releases the rank barrier while keeping only result-screen dismissal enabled. */
    fun isTerminalCleanupPending(): Boolean = terminalCleanupPending

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
        if (!isTerminalCleanupCapabilityValid(terminalCleanupCapability)) return false
        if (evidence == "POWERLOG_TERMINAL") {
            if (!pending || terminalCleanupPending) return false
            releaseRankBarrierForTerminalCleanup()
            return true
        }
        if (evidence !in setOf(
                "SCREEN_MAIN_MENU",
                "SCREEN_MATCHMAKING",
                "SCREEN_RESULT_DISMISSED",
                "SCREEN_DECK_SELECTION",
            )
        ) return false
        completePendingSurrender()
        return true
    }

    /**
     * A fresh, high-confidence deck-selection capture is terminal cleanup
     * completion evidence even when no mandatory surrender was pending.
     * Callers must pass the exact classified screen from their authorized
     * current-client capture; stale or lower-confidence observations cannot
     * release the dispatch fence.
     */
    @Synchronized
    fun confirmAtFreshDeckSelection(
        screenKind: String,
        confidence: Int,
        visualEvidence: String,
        freshObservation: Boolean,
    ): Boolean {
        if (!FreshDeckSelectionConfirmationPolicy.isConfirmed(
                screenKind = screenKind,
                confidence = confidence,
                evidence = visualEvidence,
                freshObservation = freshObservation,
            )
        ) return false
        // Mandatory rank-surrender completion must pass through
        // confirmDeckSelectionCompleted, which also checks the barrier phase.
        if (pending) return false
        if (!terminalCleanupPending) return true
        if (MulliganRankDispatchBarrier.currentState() != MulliganRankDispatchBarrier.State.IDLE) return false
        val capability = existingTerminalCleanupCapability() ?: return false
        return confirmCompleted("SCREEN_DECK_SELECTION", capability)
    }

    /** Deck selection requires terminal proof plus fresh, high-confidence visual evidence. */
    @Synchronized
    fun confirmDeckSelectionCompleted(
        screenKind: String,
        confidence: Int,
        visualEvidence: String,
        freshObservation: Boolean,
    ): Boolean {
        if ((!pending && !terminalCleanupPending) || activeTerminalCleanupCapability == null) return false
        if (!freshObservation || screenKind != "DECK_SELECTION" || confidence < 85) return false
        if (visualEvidence !in setOf("deck-selection-title", "deck-selection-title-roi")) return false
        val barrierState = MulliganRankDispatchBarrier.currentState()
        if ((terminalCleanupPending && barrierState != MulliganRankDispatchBarrier.State.IDLE) ||
            (!terminalCleanupPending && barrierState != MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED)
        ) {
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
        terminalCleanupPending = false
        pendingGameIdentity = null
        MulliganRankDispatchBarrier.completeSurrender()
    }

    private fun releaseRankBarrierForTerminalCleanup() {
        pending = false
        recoveryUncertain = false
        activeCapability = null
        terminalCleanupPending = true
        pendingGameIdentity = null
        MulliganRankDispatchBarrier.completeForCurrentTerminalResult()
    }

    fun isPending(): Boolean = pending
    fun isRecoveryUncertain(): Boolean = pending && recoveryUncertain

    /** Rank state belongs to a live game; a confirmed missing process invalidates that game's cached ticket. */
    @Synchronized
    fun resetForNoGameProcess(gameProcessAlive: Boolean): Boolean {
        if (gameProcessAlive) return false
        pending = false
        recoveryUncertain = false
        activeCapability = null
        activeTerminalCleanupCapability = null
        terminalCleanupPending = false
        pendingGameIdentity = null
        MulliganRankDispatchBarrier.resetForNewGame()
        return true
    }

    /**
     * A complete current-game Power.log result plus a missing client makes
     * result-screen cleanup impossible. Release its stale action fence so the
     * configured startup chain can restore Hearthstone; incomplete proof and a
     * still-live process leave the fence untouched.
     */
    @Synchronized
    fun reconcileMissingProcessAfterTerminalProof(
        completeCurrentGameTerminalProof: Boolean,
        gameProcessAlive: Boolean,
    ): Boolean {
        if (gameProcessAlive || !completeCurrentGameTerminalProof) return false
        if (pending || terminalCleanupPending) completePendingSurrender()
        else MulliganRankDispatchBarrier.resetForNewGame()
        return true
    }

    fun resetForTest() {
        pending = false
        recoveryUncertain = false
        activeCapability = null
        activeTerminalCleanupCapability = null
        terminalCleanupPending = false
        pendingGameIdentity = null
    }
}
