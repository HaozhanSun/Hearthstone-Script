package club.xiaojiawei.hsscript.status

/**
 * Small deterministic budget for faults in long-lived workers.
 *
 * A worker must not spin on one bad input, but a single transient fault must
 * not kill the worker either. Callers apply the returned delay before the
 * next observation; no action is repeated by this class.
 */
internal class RuntimeFaultBackoff(
    private val maxDelayMs: Long = 5_000L,
    private val baseDelayMs: Long = 250L,
) {
    data class Decision(
        val failureCount: Int,
        val delayMs: Long,
        val circuitOpened: Boolean,
    )

    private var failures = 0

    fun onSuccess() {
        failures = 0
    }

    fun onFailure(): Decision {
        failures++
        val exponent = (failures - 1).coerceAtMost(20)
        val delay = (baseDelayMs * (1L shl exponent)).coerceAtMost(maxDelayMs)
        return Decision(
            failureCount = failures,
            delayMs = delay,
            circuitOpened = failures >= 3,
        )
    }

    internal fun failureCount(): Int = failures
}
