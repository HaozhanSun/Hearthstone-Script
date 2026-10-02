package club.xiaojiawei.hsscript.status.surrender

/** Temporary lock between a mandatory rank surrender and confirmed completion. */
object MandatoryRankSurrenderGuard {
    @Volatile
    private var pending = false

    @Volatile
    private var recoveryUncertain = false

    fun begin() {
        recoveryUncertain = false
        pending = true
    }

    fun markRecoveryUncertain() {
        if (pending) recoveryUncertain = true
    }

    /** Only authoritative terminal evidence or a positive out-of-game screen may release the lock. */
    fun confirmCompleted(evidence: String): Boolean {
        val allowedEvidence = setOf("POWERLOG_TERMINAL", "SCREEN_TERMINAL", "SCREEN_MAIN_MENU", "SCREEN_MATCHMAKING")
        if (!pending || evidence !in allowedEvidence) return false
        pending = false
        recoveryUncertain = false
        return true
    }

    fun isPending(): Boolean = pending

    fun isRecoveryUncertain(): Boolean = pending && recoveryUncertain

    internal fun resetForTest() {
        pending = false
        recoveryUncertain = false
    }
}
