package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.const.BuildChannel

/** Bounded retry spacing for stale, non-game screen classification. */
internal object StuckStateRecoveryCadencePolicy {
    const val BETA_RETRY_INTERVAL_MS = 10_000L
    const val DEFAULT_RETRY_INTERVAL_MS = 30_000L
    const val STATE_TIMEOUT_MS = 30_000L

    fun retryIntervalMs(channel: String?): Long =
        if (BuildChannel.identityToken(channel) == "beta") BETA_RETRY_INTERVAL_MS
        else DEFAULT_RETRY_INTERVAL_MS

    fun shouldProbe(
        channel: String?,
        stillStuck: Boolean,
        stuckForMs: Long,
        lastAttemptAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!stillStuck || stuckForMs < STATE_TIMEOUT_MS || nowMs <= 0L) return false
        val interval = retryIntervalMs(channel)
        return lastAttemptAtMs <= 0L || nowMs - lastAttemptAtMs >= interval
    }
}
