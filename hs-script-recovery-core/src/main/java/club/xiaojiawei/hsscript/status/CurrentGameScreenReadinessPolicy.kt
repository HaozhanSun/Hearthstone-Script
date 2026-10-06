package club.xiaojiawei.hsscript.status

/** Binds ordinary screen/rank capture to a non-empty Power.log for the current game process. */
object CurrentGameScreenReadinessPolicy {
    fun isReady(
        gameWindowVerified: Boolean,
        attachedPowerLogPath: String?,
        currentSessionPowerLogPath: String?,
        powerLogLength: Long,
    ): Boolean {
        if (!gameWindowVerified || powerLogLength <= 0L) return false
        if (attachedPowerLogPath.isNullOrBlank() || currentSessionPowerLogPath.isNullOrBlank()) return false
        return normalizePath(attachedPowerLogPath) == normalizePath(currentSessionPowerLogPath)
    }

    private fun normalizePath(path: String): String =
        path.replace('/', '\\').trimEnd('\\').lowercase()
}
