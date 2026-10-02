package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.consts.GAME_WAR_LOG_NAME
import java.io.File

/** Pure validation that a disk Power.log belongs to the live game session. */
internal object PowerLogSessionBindingPolicy {
    private const val CLOCK_SKEW_TOLERANCE_MS = 10_000L

    fun isCurrentSession(
        powerLogPath: String?,
        gameLogsRoot: String?,
        length: Long,
        lastModifiedMs: Long,
        processStartedAtMs: Long?,
    ): Boolean {
        if (length <= 0L) return false
        return isSessionFileForProcess(powerLogPath, gameLogsRoot, lastModifiedMs, processStartedAtMs)
    }

    /** Validates current-session file lineage without treating an empty file as progress. */
    fun isSessionFileForProcess(
        powerLogPath: String?,
        gameLogsRoot: String?,
        lastModifiedMs: Long,
        processStartedAtMs: Long?,
    ): Boolean {
        if (powerLogPath.isNullOrBlank() || gameLogsRoot.isNullOrBlank() ||
            lastModifiedMs <= 0L || processStartedAtMs == null
        ) return false

        val powerLog = File(powerLogPath).absoluteFile.normalize().toPath()
        val logsRoot = File(gameLogsRoot).absoluteFile.normalize().toPath()
        if (!powerLog.startsWith(logsRoot) ||
            !powerLog.fileName.toString().equals(GAME_WAR_LOG_NAME, ignoreCase = true)
        ) return false

        return lastModifiedMs >= processStartedAtMs - CLOCK_SKEW_TOLERANCE_MS
    }
}
