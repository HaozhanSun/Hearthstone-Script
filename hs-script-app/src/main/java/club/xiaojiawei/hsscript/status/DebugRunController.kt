package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Application facade for the one-shot debug/test work-time override. */
object DebugRunController {
    const val MAX_DURATION_MILLIS = DebugRunLease.MAX_DURATION_MILLIS
    private val timestampFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    private val lease = DebugRunLease(
        schedule = { delayNanos, task ->
            EXTRA_THREAD_POOL.schedule(Runnable { task() }, delayNanos, TimeUnit.NANOSECONDS)
        },
        onExpired = { snapshot ->
            logSnapshot("DEBUG_OVERRIDE_EXPIRED", snapshot, "deadline-expired")
            WorkTimeListener.onDebugRunExpired()
        },
    )

    fun enable(reason: String = "toggle-on"): DebugRunLease.Snapshot {
        val snapshot = lease.enable()
        logSnapshot("DEBUG_OVERRIDE_REQUESTED", snapshot, reason)
        logSnapshot("DEBUG_OVERRIDE_ACTIVE", snapshot, reason)
        WorkTimeListener.checkWork()
        WorkTimeListener.tryWork()
        return snapshot
    }

    fun disable(reason: String = "toggle-off"): DebugRunLease.Snapshot {
        val snapshot = lease.disable()
        logSnapshot("DEBUG_OVERRIDE_DISABLED", snapshot, reason)
        WorkTimeListener.checkWork()
        if (!WorkTimeListener.canWork()) WorkTimeListener.working = false
        return snapshot
    }

    fun snapshot(): DebugRunLease.Snapshot = lease.snapshot()

    fun isActive(): Boolean = lease.isActiveWithoutExpiring()

    /** A persisted checkbox must never resurrect a live lease after restart. */
    fun resetAfterRestart() {
        val snapshot = lease.resetForRestart()
        ConfigUtil.putBoolean(ConfigEnum.DEBUG_RUN_MODE, false)
        logSnapshot("DEBUG_OVERRIDE_DISABLED", snapshot, "process-restart-no-live-lease")
    }

    private fun logSnapshot(event: String, snapshot: DebugRunLease.Snapshot, reason: String) {
        val start = if (snapshot.startEpochMillis == 0L) "n/a" else timestampFormatter.format(Instant.ofEpochMilli(snapshot.startEpochMillis))
        val end = if (snapshot.endEpochMillis == 0L) "n/a" else timestampFormatter.format(Instant.ofEpochMilli(snapshot.endEpochMillis))
        log.info { "$event start=$start end=$end reason=$reason remainingMs=${snapshot.remainingMillis}" }
    }
}



