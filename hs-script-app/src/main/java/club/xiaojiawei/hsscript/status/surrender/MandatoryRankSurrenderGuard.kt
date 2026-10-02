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
        return TerminalCleanupCapability().also { activeTerminalCleanupCapability = it }
    }

    fun isTerminalCleanupCapabilityValid(capability: TerminalCleanupCapability?): Boolean =
        pending && capability != null && capability === activeTerminalCleanupCapability

    fun markRecoveryUncertain() {
        if (pending) recoveryUncertain = true
    }

    /** Only authoritative terminal evidence or a positive out-of-game screen may release the lock. */
    fun confirmCompleted(evidence: String): Boolean {
        val allowedEvidence = setOf(
            "POWERLOG_TERMINAL",
            "SCREEN_TERMINAL",
            "SCREEN_MAIN_MENU",
            "SCREEN_MATCHMAKING",
            "SCREEN_RESULT_DISMISSED",
        )
        if (!pending || evidence !in allowedEvidence) return false
        pending = false
        recoveryUncertain = false
        activeCapability = null
        activeTerminalCleanupCapability = null
        MulliganRankDispatchBarrier.completeSurrender()
        return true
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
