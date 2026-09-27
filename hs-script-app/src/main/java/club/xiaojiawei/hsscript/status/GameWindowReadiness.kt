package club.xiaojiawei.hsscript.status

/** Pure, replayable predicate for deciding whether desktop input may target the game. */
internal object GameWindowReadiness {
    fun exactVisibleForeground(
        targetVisible: Boolean,
        foregroundVisible: Boolean,
        targetHandle: Long,
        foregroundHandle: Long,
    ): Boolean = targetVisible && foregroundVisible && targetHandle != 0L && targetHandle == foregroundHandle

    fun sameVisibleGameProcess(
        targetVisible: Boolean,
        foregroundVisible: Boolean,
        targetPid: Int,
        foregroundPid: Int,
    ): Boolean =
        targetVisible && foregroundVisible && targetPid != 0 && targetPid == foregroundPid

    /**
     * A foreground check is only useful for a capture when it remains true
     * for the whole capture window.  Keep this predicate pure so the stale
     * desktop/capture regression can be replayed offline.
     */
    fun captureRemainsOnGame(
        targetVisible: Boolean,
        foregroundVisibleBefore: Boolean,
        foregroundVisibleAfter: Boolean,
        targetPid: Int,
        foregroundPidBefore: Int,
        foregroundPidAfter: Int,
    ): Boolean =
        sameVisibleGameProcess(
            targetVisible = targetVisible,
            foregroundVisible = foregroundVisibleBefore,
            targetPid = targetPid,
            foregroundPid = foregroundPidBefore,
        ) && sameVisibleGameProcess(
            targetVisible = targetVisible,
            foregroundVisible = foregroundVisibleAfter,
            targetPid = targetPid,
            foregroundPid = foregroundPidAfter,
        )
}
