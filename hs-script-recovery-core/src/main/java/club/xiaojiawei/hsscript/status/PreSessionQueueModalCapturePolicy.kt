package club.xiaojiawei.hsscript.status

/** The single capture-purpose exception to the normal current-session Power.log requirement. */
object PreSessionQueueModalCapturePolicy {
    fun isAuthorized(
        exactQueueModalPurpose: Boolean,
        tournamentMode: Boolean,
        activeGame: Boolean,
        mulligan: Boolean,
        terminal: Boolean,
    ): Boolean = exactQueueModalPurpose && tournamentMode && !activeGame && !mulligan && !terminal
}
