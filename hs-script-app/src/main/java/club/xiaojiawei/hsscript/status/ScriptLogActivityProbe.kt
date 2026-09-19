package club.xiaojiawei.hsscript.status

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Describes the file currently receiving Logback application output.
 *
 * Logback's SizeAndTimeBasedRollingPolicy keeps [ACTIVE_FILE_NAME] as the
 * active path and renames the old contents to a numbered archive.  A numbered
 * archive is therefore historical evidence, never proof that the application
 * has stopped.  Keeping this rule in one small, pure-data helper prevents
 * watchdogs and offline diagnostics from independently choosing a misleading
 * "latest" archive.
 */
data class ScriptLogFileSnapshot(
    val path: String,
    val identity: String,
    val length: Long,
    val lastModifiedMillis: Long,
)

/** The active appender file plus archives retained for diagnostics only. */
data class ScriptLogDirectorySnapshot(
    val active: ScriptLogFileSnapshot?,
    val archives: List<ScriptLogFileSnapshot>,
)

data class ScriptLogProgress(
    val activeFileAdvanced: Boolean,
    val activeFileRolledOver: Boolean,
    val powerLogAdvanced: Boolean,
    val lifecycleAdvanced: Boolean,
    val actionAdvanced: Boolean,
    val genuinelyStalled: Boolean,
    val reason: String,
)

/** Inputs from the independent progress channels used by the stall policy. */
data class ScriptActivitySample(
    val checkedAtMillis: Long,
    val scriptLog: ScriptLogFileSnapshot?,
    val powerLogPath: String?,
    val powerLogPosition: Long?,
    val lifecycleProgressAtMillis: Long?,
    val actionProgressAtMillis: Long?,
)

object ScriptLogActivityProbe {
    const val ACTIVE_FILE_NAME = "hs_script.log"
    private val ARCHIVE_FILE_PATTERN = Regex("hs_script-\\d{4}-\\d{2}-\\d{2}\\.\\d+\\.log")
    private const val UNKNOWN_POSITION = Long.MIN_VALUE

    /** Return only Logback's unnumbered active file; never fall back to an archive. */
    fun findActiveFile(logDirectory: File): File? =
        logDirectory.resolve(ACTIVE_FILE_NAME).takeIf { it.isFile }

    /**
     * Inspect both sides of a Logback rollover without letting an archive
     * become the liveness source.  Callers use [active] for progress and may
     * use [archives] to explain which rollover history was present.
     */
    fun snapshotDirectory(logDirectory: File): ScriptLogDirectorySnapshot {
        val archives = logDirectory.listFiles()
            ?.filter { it.isFile && ARCHIVE_FILE_PATTERN.matches(it.name) }
            ?.sortedBy { it.name }
            ?.map(::snapshotFile)
            ?: emptyList()
        return ScriptLogDirectorySnapshot(
            active = findActiveFile(logDirectory)?.let(::snapshotFile),
            archives = archives,
        )
    }

    fun snapshot(logDirectory: File): ScriptLogFileSnapshot? =
        findActiveFile(logDirectory)?.let(::snapshotFile)

    fun snapshotFile(file: File): ScriptLogFileSnapshot {
        val attributes = runCatching {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        }.getOrNull()
        val identity = attributes?.fileKey()?.toString()
            ?: attributes?.creationTime()?.toMillis()?.toString()
            ?: "${file.absolutePath}|${file.lastModified()}"
        return ScriptLogFileSnapshot(
            path = file.absoluteFile.normalize().path,
            identity = identity,
            length = file.length(),
            lastModifiedMillis = file.lastModified(),
        )
    }

    /**
     * Compare two samples without treating a numbered archive as activity.
     * A new active-file identity or a length reset is progress: both are the
     * normal observable shape of a 5MB Logback rollover.
     */
    fun evaluate(
        previous: ScriptActivitySample,
        current: ScriptActivitySample,
        stallTimeoutMillis: Long,
    ): ScriptLogProgress {
        val previousScript = previous.scriptLog
        val currentScript = current.scriptLog
        val activeFileRolledOver = previousScript != null && currentScript != null && (
            previousScript.path != currentScript.path ||
                previousScript.identity != currentScript.identity ||
                currentScript.length < previousScript.length
            )
        val activeFileAdvanced = activeFileRolledOver ||
            (previousScript != null && currentScript != null &&
                currentScript.path == previousScript.path &&
                currentScript.length > previousScript.length)

        val powerLogAdvanced = previous.powerLogPath != current.powerLogPath ||
            (previous.powerLogPosition != null && current.powerLogPosition != null &&
                previous.powerLogPosition != UNKNOWN_POSITION &&
                current.powerLogPosition != UNKNOWN_POSITION &&
                current.powerLogPosition != previous.powerLogPosition)
        val lifecycleAdvanced = hasAdvancedTimestamp(
            previous.lifecycleProgressAtMillis,
            current.lifecycleProgressAtMillis,
        )
        val actionAdvanced = hasAdvancedTimestamp(
            previous.actionProgressAtMillis,
            current.actionProgressAtMillis,
        )
        val anyProgress = activeFileAdvanced || powerLogAdvanced || lifecycleAdvanced || actionAdvanced
        val lastKnownProgress = listOfNotNull(
            previous.lifecycleProgressAtMillis,
            previous.actionProgressAtMillis,
            previous.checkedAtMillis.takeIf { activeFileAdvanced || powerLogAdvanced },
        ).maxOrNull() ?: previous.checkedAtMillis
        val genuinelyStalled = !anyProgress &&
            current.checkedAtMillis - lastKnownProgress >= stallTimeoutMillis.coerceAtLeast(0L)
        val reason = when {
            activeFileRolledOver -> "active-log-rollover"
            activeFileAdvanced -> "active-log-grew"
            powerLogAdvanced -> "power-log-progress"
            lifecycleAdvanced -> "lifecycle-progress"
            actionAdvanced -> "action-progress"
            genuinelyStalled -> "all-progress-channels-stalled"
            else -> "below-stall-threshold"
        }
        return ScriptLogProgress(
            activeFileAdvanced = activeFileAdvanced,
            activeFileRolledOver = activeFileRolledOver,
            powerLogAdvanced = powerLogAdvanced,
            lifecycleAdvanced = lifecycleAdvanced,
            actionAdvanced = actionAdvanced,
            genuinelyStalled = genuinelyStalled,
            reason = reason,
        )
    }

    private fun hasAdvancedTimestamp(previous: Long?, current: Long?): Boolean =
        previous != null && current != null && current > previous
}
