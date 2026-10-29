package club.xiaojiawei.hsscript.status

/** Prevents repeated mode-selector attempts when the final startup gate has no screen proof. */
internal class TournamentModeEntryDispatchGuard(
    private val noticeCooldownMs: Long = DEFAULT_NOTICE_COOLDOWN_MS,
) {
    data class Decision(
        val allowed: Boolean,
        val emitNotice: Boolean,
        val suppressedSinceNotice: Int,
        val reason: String,
    )

    private var lastNoticeAtMs = Long.MIN_VALUE
    private var suppressedSinceNotice = 0

    @Synchronized
    fun decide(screenConfirmed: Boolean, nowMs: Long): Decision {
        if (screenConfirmed) {
            lastNoticeAtMs = Long.MIN_VALUE
            suppressedSinceNotice = 0
            return Decision(true, false, 0, "startup-screen-confirmed")
        }
        val cooldownElapsed = lastNoticeAtMs == Long.MIN_VALUE ||
            nowMs >= lastNoticeAtMs && nowMs - lastNoticeAtMs >= noticeCooldownMs
        if (cooldownElapsed) {
            val previousSuppressed = suppressedSinceNotice
            lastNoticeAtMs = nowMs
            suppressedSinceNotice = 0
            return Decision(false, true, previousSuppressed, "startup-screen-not-confirmed")
        }
        suppressedSinceNotice++
        return Decision(false, false, suppressedSinceNotice, "notice-cooldown")
    }

    companion object {
        const val DEFAULT_NOTICE_COOLDOWN_MS = 60_000L
    }
}
