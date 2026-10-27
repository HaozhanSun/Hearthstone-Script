package club.xiaojiawei.hsscript.starter

/** Bounds read-only startup observations while allowing delayed client UI to settle. */
internal object StartupScreenProbeRetryPolicy {
    const val MAX_DURATION_MS = 90_000L
    const val RETRY_DELAY_MS = 2_000L

    fun shouldContinue(elapsedMs: Long, paused: Boolean): Boolean =
        !paused && elapsedMs >= 0L && elapsedMs < MAX_DURATION_MS
}
