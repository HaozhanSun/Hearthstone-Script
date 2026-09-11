package club.xiaojiawei.hsscript.status

/**
 * Keeps a missing foreground window from turning a visual recovery probe into
 * an unbounded loop.  A user bringing Hearthstone forward changes the normal
 * mode/input state and therefore starts a fresh recovery observation.
 */
internal object ScreenRecoveryFocusRetryPolicy {
    const val MAX_DEFERRED_ATTEMPTS = 3

    enum class Decision {
        RETRY_LATER,
        STOP_UNTIL_STATE_CHANGE,
    }

    fun afterForegroundFailure(previousFailures: Int): Decision =
        if (previousFailures + 1 < MAX_DEFERRED_ATTEMPTS) {
            Decision.RETRY_LATER
        } else {
            Decision.STOP_UNTIL_STATE_CHANGE
        }
}
