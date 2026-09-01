package club.xiaojiawei.hsscript.status

import java.util.concurrent.ScheduledFuture

/**
 * One-shot monotonic-time lease for the debug/test work-time override.
 * A second enable request while active is idempotent and never extends it.
 */
class DebugRunLease(
    private val maxDurationMillis: Long = MAX_DURATION_MILLIS,
    private val nanoTime: () -> Long = System::nanoTime,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val schedule: ((Long, () -> Unit) -> ScheduledFuture<*>)? = null,
    private val onExpired: (Snapshot) -> Unit = {},
) {
    init {
        require(maxDurationMillis in 1L..MAX_DURATION_MILLIS) {
            "maxDurationMillis must be between 1 ms and 30 minutes"
        }
    }

    enum class State { DISABLED, ACTIVE, EXPIRED }

    data class Snapshot(
        val state: State,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val remainingMillis: Long,
    )

    private val lock = Any()
    private var generation = 0L
    private var state = State.DISABLED
    private var deadlineNanos = 0L
    private var startEpochMillis = 0L
    private var endEpochMillis = 0L
    private var expiryFuture: ScheduledFuture<*>? = null

    fun enable(requestedDurationMillis: Long = maxDurationMillis): Snapshot {
        var expired: Snapshot? = null
        val snapshot = synchronized(lock) {
            val nowNanos = nanoTime()
            if (state == State.ACTIVE && nowNanos < deadlineNanos) {
                return@synchronized snapshotLocked(nowNanos)
            }
            if (state == State.ACTIVE) expired = expireLocked(nowNanos)

            val durationMillis = requestedDurationMillis.coerceIn(1L, maxDurationMillis)
            val nowWallClock = wallClockMillis()
            generation += 1
            state = State.ACTIVE
            deadlineNanos = nowNanos + durationMillis * NANOS_PER_MILLI
            startEpochMillis = nowWallClock
            endEpochMillis = nowWallClock + durationMillis
            expiryFuture?.cancel(false)
            expiryFuture = null
            scheduleExpiryLocked(generation, durationMillis * NANOS_PER_MILLI)
            snapshotLocked(nowNanos)
        }
        expired?.let(onExpired)
        return snapshot
    }

    fun disable(): Snapshot {
        var expired: Snapshot? = null
        val snapshot = synchronized(lock) {
            val nowNanos = nanoTime()
            if (state == State.ACTIVE && nowNanos >= deadlineNanos) expired = expireLocked(nowNanos)
            generation += 1
            expiryFuture?.cancel(false)
            expiryFuture = null
            state = State.DISABLED
            deadlineNanos = 0L
            snapshotLocked(nowNanos)
        }
        expired?.let(onExpired)
        return snapshot
    }

    /** Clear all live state without invoking expiry callbacks on process restart. */
    fun resetForRestart(): Snapshot = synchronized(lock) {
        generation += 1
        expiryFuture?.cancel(false)
        expiryFuture = null
        state = State.DISABLED
        deadlineNanos = 0L
        startEpochMillis = 0L
        endEpochMillis = 0L
        snapshotLocked(nanoTime())
    }

    fun snapshot(): Snapshot {
        var expired: Snapshot? = null
        val snapshot = synchronized(lock) {
            val nowNanos = nanoTime()
            if (state == State.ACTIVE && nowNanos >= deadlineNanos) expired = expireLocked(nowNanos)
            snapshotLocked(nowNanos)
        }
        expired?.let(onExpired)
        return snapshot
    }

    fun isActive(): Boolean = snapshot().state == State.ACTIVE

    /** Read-only gate check; unlike [isActive], it never invokes callbacks. */
    fun isActiveWithoutExpiring(): Boolean = synchronized(lock) {
        state == State.ACTIVE && nanoTime() < deadlineNanos
    }

    fun expireIfNeeded(): Boolean {
        val expired = synchronized(lock) {
            if (state != State.ACTIVE) return@synchronized null
            val nowNanos = nanoTime()
            if (nowNanos < deadlineNanos) return@synchronized null
            expireLocked(nowNanos)
        }
        expired?.let(onExpired)
        return expired != null
    }

    private fun scheduleExpiryLocked(expectedGeneration: Long, delayNanos: Long) {
        val scheduleExpiry = schedule ?: return
        expiryFuture = scheduleExpiry(delayNanos.coerceAtLeast(1L)) {
            val expired = synchronized(lock) {
                if (state != State.ACTIVE || generation != expectedGeneration) return@synchronized null
                val nowNanos = nanoTime()
                val remainingNanos = deadlineNanos - nowNanos
                if (remainingNanos > 0L) {
                    scheduleExpiryLocked(expectedGeneration, remainingNanos)
                    return@synchronized null
                }
                expireLocked(nowNanos)
            }
            expired?.let(onExpired)
        }
    }

    private fun snapshotLocked(nowNanos: Long): Snapshot {
        val remainingMillis = if (state == State.ACTIVE) {
            ((deadlineNanos - nowNanos).coerceAtLeast(0L) + NANOS_PER_MILLI - 1L) / NANOS_PER_MILLI
        } else {
            0L
        }
        return Snapshot(state, startEpochMillis, endEpochMillis, remainingMillis)
    }

    private fun expireLocked(nowNanos: Long): Snapshot {
        state = State.EXPIRED
        generation += 1
        expiryFuture = null
        return snapshotLocked(nowNanos)
    }

    companion object {
        const val MAX_DURATION_MILLIS: Long = 30L * 60L * 1000L
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Gate-only composition: the configured schedule remains authoritative. */
        fun effectiveCanWork(ordinaryScheduleActive: Boolean, debugLeaseActive: Boolean): Boolean =
            ordinaryScheduleActive || debugLeaseActive
    }
}



