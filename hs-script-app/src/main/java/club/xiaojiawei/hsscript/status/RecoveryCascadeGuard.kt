package club.xiaojiawei.hsscript.status

/**
 * Prevents a terminal automatic pause from being mistaken for permission to
 * continue visual recovery forever.  The fence is cleared only after an
 * explicit resume (when PauseStatus is no longer paused).
 */
internal class RecoveryCascadeGuard {
    private var terminalReason: String? = null

    fun trip(reason: String): Boolean {
        if (terminalReason != null) return false
        terminalReason = reason
        return true
    }

    fun suppressWhilePaused(isPaused: Boolean): Boolean {
        if (!isPaused) {
            terminalReason = null
            return false
        }
        return terminalReason != null
    }

    companion object {
        enum class LogRole { ROOT_CAUSE, FOLLOW_ON, OTHER }

        fun classifyLogLine(line: String): LogRole = when {
            line.contains("GAME_STARTUP_STOPPED") && line.contains("handshake-timeout") ->
                LogRole.ROOT_CAUSE
            line.contains("NO_PROGRESS_ESCALATED") && line.contains("terminal=PAUSE") ->
                LogRole.ROOT_CAUSE
            line.contains("SCREEN_RECOVERY_") ||
                line.contains("UNKNOWN_STATE_SCREENSHOT") ||
                line.contains("PLATFORM_CLOSE_SKIPPED") -> LogRole.FOLLOW_ON
            else -> LogRole.OTHER
        }
    }
}
