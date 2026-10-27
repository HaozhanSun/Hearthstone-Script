package club.xiaojiawei.hsscript.status

import java.util.concurrent.atomic.AtomicBoolean

/** Coalesces delayed restart callbacks so an alert/exhaustion observer cannot race a queued restart. */
internal class ClientRestartRequestGate {
    private val inFlight = AtomicBoolean(false)
    @Volatile private var expectedProcessId: Long = 0L
    @Volatile private var acquiredAt: Long = 0L

    @Synchronized
    fun tryAcquire(processId: Long, now: Long): Boolean {
        releaseIfExpired(now)
        if (processId <= 0L || !inFlight.compareAndSet(false, true)) return false
        expectedProcessId = processId
        acquiredAt = now
        return true
    }

    @Synchronized
    fun releaseIfProcessChanged(currentProcessId: Long) {
        if (inFlight.get() && expectedProcessId > 0L && currentProcessId > 0L &&
            expectedProcessId != currentProcessId
        ) release()
    }

    @Synchronized
    fun releaseIfExpired(now: Long): Boolean {
        if (inFlight.get() && acquiredAt > 0L && now - acquiredAt >= MAX_IN_FLIGHT_MS) {
            release()
            return true
        }
        return false
    }

    @Synchronized
    fun release() {
        expectedProcessId = 0L
        acquiredAt = 0L
        inFlight.set(false)
    }

    fun isInFlight(now: Long): Boolean {
        releaseIfExpired(now)
        return inFlight.get()
    }

    companion object { const val MAX_IN_FLIGHT_MS = 120_000L }
}

/** Bounded no-pause escalation for an unchanged, unclassified startup client. */
internal class UnknownStartupRestartPolicy(
    private val thresholdMs: Long = DEFAULT_THRESHOLD_MS,
    private val retryCooldownMs: Long = DEFAULT_RETRY_COOLDOWN_MS,
    private val maxRestarts: Int = DEFAULT_MAX_RESTARTS,
) {
    enum class Decision { WAIT, RESTART_CLIENT, ALERT_OPERATOR }

    private var fingerprint: String? = null
    private var firstObservedAt = 0L
    private var restartAttempts = 0
    private var lastRestartAt = 0L

    @Synchronized
    fun observe(
        stateFingerprint: String,
        now: Long,
        stuckForMs: Long,
        eligibleProbe: Boolean,
        noActiveGame: Boolean,
        noActiveQueue: Boolean,
    ): Decision {
        if (stateFingerprint.isBlank() || now <= 0L) return Decision.WAIT
        if (!eligibleProbe || !noActiveGame || !noActiveQueue) {
            resetObservation()
            return Decision.WAIT
        }
        if (fingerprint != stateFingerprint) {
            fingerprint = stateFingerprint
            firstObservedAt = (now - stuckForMs.coerceAtLeast(0L)).coerceAtLeast(1L)
        }
        if (now - firstObservedAt < thresholdMs) return Decision.WAIT
        if (restartAttempts >= maxRestarts) return Decision.ALERT_OPERATOR
        if (lastRestartAt > 0L && now - lastRestartAt < retryCooldownMs) return Decision.WAIT
        restartAttempts++
        lastRestartAt = now
        // Reset the stable interval so a restart gets time to make progress.
        firstObservedAt = now
        return Decision.RESTART_CLIENT
    }

    @Synchronized
    fun reset() {
        resetObservation()
        restartAttempts = 0
        lastRestartAt = 0L
    }

    private fun resetObservation() {
        fingerprint = null
        firstObservedAt = 0L
    }

    companion object {
        const val DEFAULT_THRESHOLD_MS = 60_000L
        const val DEFAULT_RETRY_COOLDOWN_MS = 120_000L
        const val DEFAULT_MAX_RESTARTS = 2
    }
}
