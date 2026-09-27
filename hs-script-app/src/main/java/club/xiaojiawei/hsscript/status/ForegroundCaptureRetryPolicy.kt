package club.xiaojiawei.hsscript.status

/** Bounded recapture policy after a foreground-confirmed but rejected frame. */
internal object ForegroundCaptureRetryPolicy {
    const val MAX_ATTEMPTS = 3

    enum class Decision { ACCEPT, RETRY_FRESH_CAPTURE, DEFER_FOREGROUND, EXHAUSTED }

    data class Attempt<T>(
        val foregroundConfirmed: Boolean,
        val provenanceAccepted: Boolean,
        val frame: T?,
    )

    data class Outcome<T>(val decision: Decision, val attempts: Int, val frame: T?)

    fun decide(attempt: Int, foregroundConfirmed: Boolean, captureAccepted: Boolean): Decision = when {
        !foregroundConfirmed -> Decision.DEFER_FOREGROUND
        captureAccepted -> Decision.ACCEPT
        attempt < MAX_ATTEMPTS -> Decision.RETRY_FRESH_CAPTURE
        else -> Decision.EXHAUSTED
    }

    /** Executes a fresh acquire callback on every retry, never reusing rejected pixels. */
    fun <T> run(
        acquire: (attempt: Int) -> Attempt<T>,
        onRetry: (attempt: Int) -> Unit = {},
    ): Outcome<T> {
        for (attempt in 1..MAX_ATTEMPTS) {
            val result = acquire(attempt)
            val decision = decide(attempt, result.foregroundConfirmed, result.provenanceAccepted && result.frame != null)
            when (decision) {
                Decision.ACCEPT -> return Outcome(decision, attempt, result.frame)
                Decision.DEFER_FOREGROUND -> return Outcome(decision, attempt, null)
                Decision.RETRY_FRESH_CAPTURE -> onRetry(attempt)
                Decision.EXHAUSTED -> return Outcome(decision, attempt, null)
            }
        }
        return Outcome(Decision.EXHAUSTED, MAX_ATTEMPTS, null)
    }
}
