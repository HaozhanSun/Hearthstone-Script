package club.xiaojiawei.hsscript.strategy.phase

import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier

/**
 * Bounded retry budget for an executor-rejected mandatory rank surrender.
 * Exhaustion deliberately leaves the rank barrier closed; it never authorizes
 * ordinary Mulligan/gameplay input and never pauses the runtime.
 */
internal class MulliganRankSurrenderRetryPolicy(
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    data class RetrySchedule(
        val delayMs: Long,
        val attempt: Int,
        val cooldown: Boolean = false,
        val pause: Boolean = false,
        val allowPhaseAdvance: Boolean = false,
    )

    private var scheduledRetries = 0

    init {
        require(retryDelaysMs.all { it >= 0L }) { "retry delays must be non-negative" }
        require(cooldownMs > 0L) { "retry cooldown must be positive" }
    }

    @Synchronized
    fun nextRetryAfterRejection(): RetrySchedule {
        if (scheduledRetries >= retryDelaysMs.size) {
            scheduledRetries = 0
            return RetrySchedule(delayMs = cooldownMs, attempt = 0, cooldown = true)
        }
        val attempt = ++scheduledRetries
        return RetrySchedule(retryDelaysMs[attempt - 1], attempt)
    }

    @Synchronized
    fun scheduledRetryCount(): Int = scheduledRetries

    @Synchronized
    fun reset() {
        scheduledRetries = 0
    }

    companion object {
        val DEFAULT_RETRY_DELAYS_MS = listOf(500L, 1_000L, 2_000L)
        const val DEFAULT_COOLDOWN_MS = 10_000L

        fun mustBlockMulliganAdvance(state: MulliganRankDispatchBarrier.State): Boolean =
            state == MulliganRankDispatchBarrier.State.PENDING ||
                state == MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED
    }
}
