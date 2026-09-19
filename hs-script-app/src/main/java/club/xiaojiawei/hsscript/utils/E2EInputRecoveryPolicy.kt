package club.xiaojiawei.hsscript.utils

/**
 * Finite policy for recovering the Hearthstone foreground before real input.
 * Input must never be sent to an unconfirmed foreground window.
 */
internal object E2EInputRecoveryPolicy {

    const val MAX_FOREGROUND_ATTEMPTS = 3
    const val RETRY_DELAY_MS = 120

    enum class Decision {
        SEND,
        RETRY,
        BLOCK,
    }

    fun decide(attempt: Int, windowValid: Boolean, foregroundMatches: Boolean): Decision {
        if (windowValid && foregroundMatches) return Decision.SEND
        if (attempt + 1 >= MAX_FOREGROUND_ATTEMPTS) return Decision.BLOCK
        return Decision.RETRY
    }
}
