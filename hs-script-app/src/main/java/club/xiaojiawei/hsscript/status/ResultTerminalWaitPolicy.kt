package club.xiaojiawei.hsscript.status

/** Keeps result handling pending only until authoritative Power.log evidence arrives or the wait expires. */
internal class ResultTerminalWaitPolicy(private val timeoutMs: Long) {
    enum class Outcome { READY, WAITING, TIMED_OUT }
    data class Evaluation(val outcome: Outcome, val firstWait: Boolean = false)

    private var startedAtMs = 0L

    @Synchronized
    fun evaluate(authoritativeTerminalAvailable: Boolean, nowMs: Long): Evaluation {
        if (authoritativeTerminalAvailable) {
            startedAtMs = 0L
            return Evaluation(Outcome.READY)
        }
        if (startedAtMs == 0L) {
            startedAtMs = nowMs.coerceAtLeast(1L)
            return Evaluation(Outcome.WAITING, firstWait = true)
        }
        if (nowMs - startedAtMs >= timeoutMs) {
            startedAtMs = 0L
            return Evaluation(Outcome.TIMED_OUT)
        }
        return Evaluation(Outcome.WAITING)
    }

    @Synchronized
    fun reset() {
        startedAtMs = 0L
    }
}
