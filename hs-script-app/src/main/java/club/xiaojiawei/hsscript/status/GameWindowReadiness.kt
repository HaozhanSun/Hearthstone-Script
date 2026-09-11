package club.xiaojiawei.hsscript.status

/** Pure, replayable predicate for deciding whether desktop input may target the game. */
internal object GameWindowReadiness {
    fun sameVisibleGameProcess(
        targetVisible: Boolean,
        foregroundVisible: Boolean,
        targetPid: Int,
        foregroundPid: Int,
    ): Boolean =
        targetVisible && foregroundVisible && targetPid != 0 && targetPid == foregroundPid
}
