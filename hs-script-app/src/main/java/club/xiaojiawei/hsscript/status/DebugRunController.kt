package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Application facade for the one-shot debug/test work-time override. */
object DebugRunController {
    const val MAX_DURATION_MILLIS = DebugRunLease.MAX_DURATION_MILLIS
    private val timestampFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)
    private val metadataLock = Any()
    private var activeRunId: String? = null

    private val lease = DebugRunLease(
        schedule = { delayNanos, task ->
            EXTRA_THREAD_POOL.schedule(Runnable { task() }, delayNanos, TimeUnit.NANOSECONDS)
        },
        onExpired = { snapshot ->
            synchronized(metadataLock) {
                logSnapshot("DEBUG_OVERRIDE_EXPIRED", snapshot, "deadline-expired", activeRunId)
                logRuntime("EXPIRED", snapshot, activeRunId)
                activeRunId = null
            }
            WorkTimeListener.onDebugRunExpired()
        },
    )

    fun enable(reason: String = "toggle-on"): DebugRunLease.Snapshot = synchronized(metadataLock) {
        val wasActive = lease.isActiveWithoutExpiring()
        val snapshot = lease.enable()
        if (snapshot.state == DebugRunLease.State.ACTIVE && !wasActive) {
            activeRunId = UUID.randomUUID().toString()
        }
        logSnapshot("DEBUG_OVERRIDE_REQUESTED", snapshot, reason, activeRunId)
        if (!wasActive) {
            logSnapshot("DEBUG_OVERRIDE_ACTIVE", snapshot, reason, activeRunId)
            logRuntime("ACTIVE", snapshot, activeRunId)
        }
        WorkTimeListener.checkWork()
        WorkTimeListener.tryWork()
        snapshot
    }

    fun disable(reason: String = "toggle-off"): DebugRunLease.Snapshot = synchronized(metadataLock) {
        val runId = activeRunId
        val snapshot = lease.disable()
        logSnapshot("DEBUG_OVERRIDE_DISABLED", snapshot, reason, runId)
        logRuntime("STOPPED", snapshot, runId)
        activeRunId = null
        WorkTimeListener.checkWork()
        if (!WorkTimeListener.canWork()) WorkTimeListener.working = false
        snapshot
    }

    fun snapshot(): DebugRunLease.Snapshot = lease.snapshot()

    fun isActive(): Boolean = lease.isActiveWithoutExpiring()

    fun currentOverrideInfo(): ScheduleOverrideInfo? = synchronized(metadataLock) {
        val snapshot = lease.snapshot()
        if (snapshot.state != DebugRunLease.State.ACTIVE) return@synchronized null
        ScheduleOverrideInfo(
            source = "debug-run",
            runId = activeRunId ?: "unknown",
            deadline = Instant.ofEpochMilli(snapshot.endEpochMillis),
            mode = currentMode(),
            provider = currentProvider(),
        )
    }

    /** A persisted checkbox must never resurrect a live lease after restart. */
    fun resetAfterRestart() {
        synchronized(metadataLock) {
            val snapshot = lease.resetForRestart()
            ConfigUtil.putBoolean(ConfigEnum.DEBUG_RUN_MODE, false)
            logSnapshot("DEBUG_OVERRIDE_DISABLED", snapshot, "process-restart-no-live-lease", activeRunId)
            activeRunId = null
        }
    }

    private fun logSnapshot(event: String, snapshot: DebugRunLease.Snapshot, reason: String, runId: String?) {
        val start = if (snapshot.startEpochMillis == 0L) "n/a" else timestampFormatter.format(Instant.ofEpochMilli(snapshot.startEpochMillis))
        val end = if (snapshot.endEpochMillis == 0L) "n/a" else timestampFormatter.format(Instant.ofEpochMilli(snapshot.endEpochMillis))
        log.info {
            "$event source=debug-run runId=${runId ?: "n/a"} start=$start end=$end " +
                "mode=${currentMode()} provider=${currentProvider()} reason=$reason remainingMs=${snapshot.remainingMillis}"
        }
    }

    private fun logRuntime(event: String, snapshot: DebugRunLease.Snapshot, runId: String?) {
        log.info {
            "SCHEDULE_RUNTIME timestamp=${Instant.now()} event=$event source=debug-run " +
                "runId=${runId ?: "n/a"} deadline=${if (snapshot.endEpochMillis == 0L) "n/a" else timestampFormatter.format(Instant.ofEpochMilli(snapshot.endEpochMillis))} " +
                "mode=${currentMode()} provider=${currentProvider()} remainingMs=${snapshot.remainingMillis}"
        }
    }

    private fun currentMode(): String = runCatching {
        (Mode.currMode ?: Mode.nextMode)?.name ?: "UNKNOWN"
    }.getOrDefault("UNKNOWN")

    private fun currentProvider(): String = runCatching {
        OcrRuntime.currentProvider().name
    }.getOrDefault("UNKNOWN")
}
