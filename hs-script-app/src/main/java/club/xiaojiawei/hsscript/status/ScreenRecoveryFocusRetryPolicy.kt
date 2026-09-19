package club.xiaojiawei.hsscript.status

/**
 * Keeps a missing foreground window from turning one visual recovery probe
 * into a busy loop. Recovery itself must remain retryable: foreground ownership
 * can change without the lifecycle fingerprint changing.
 */
internal object ScreenRecoveryFocusRetryPolicy {
    enum class Decision {
        RETRY_LATER,
    }

    fun afterForegroundFailure(previousFailures: Int): Decision =
        Decision.RETRY_LATER
}
