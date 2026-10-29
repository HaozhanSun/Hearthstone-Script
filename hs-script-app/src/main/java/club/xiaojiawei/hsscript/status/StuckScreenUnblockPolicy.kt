package club.xiaojiawei.hsscript.status

/** Pure bounded ladder for idle-only blocker screens. Runtime dispatch must recheck every evidence fence. */
internal class StuckScreenUnblockPolicy(
    private val firstThresholdMs: Long = DEFAULT_FIRST_THRESHOLD_MS,
    private val secondStageMs: Long = DEFAULT_SECOND_STAGE_MS,
) {
    enum class Action { WAIT, CLICK_TOP_LEFT, REPROBE, RESTART_CLIENT, UNBLOCKED, STOP }
    data class Evidence(
        val nowMs: Long,
        val stableForMs: Long,
        val fingerprint: String,
        val captureFresh: Boolean,
        val exactWindowVerified: Boolean,
        val eligibleIdleProbe: Boolean,
        val recognizedSafeBlocker: Boolean,
        val currentSessionReady: Boolean,
        val activeGame: Boolean,
        val activeQueue: Boolean,
        val terminal: Boolean,
        val paused: Boolean,
        val modeMismatchDeck: Boolean = false,
        val reconnect: Boolean = false,
        val resultOrTerminalScreen: Boolean = false,
        val activeGameplayScreen: Boolean = false,
        val matchmakingScreen: Boolean = false,
        val dispatchAccepted: Boolean = true,
    )
    data class Decision(val action: Action, val clickNumber: Int, val reason: String)

    private var fingerprint: String? = null
    private var firstSeenAtMs = 0L
    private var clicks = 0
    private var thirdClickAtMs = 0L

    @Synchronized
    fun observe(e: Evidence): Decision {
        val excluded = e.resultOrTerminalScreen ||
            e.activeGameplayScreen || e.matchmakingScreen
        val safe = e.nowMs > 0 && e.fingerprint.isNotBlank() && e.captureFresh && e.exactWindowVerified &&
            e.eligibleIdleProbe && e.recognizedSafeBlocker && !e.currentSessionReady && !e.activeGame &&
            !e.activeQueue && !e.terminal && !e.paused && !excluded
        if (!safe) {
            reset()
            return Decision(Action.STOP, clicks, "evidence-fence")
        }
        val screenChanged = fingerprint != null && fingerprint != e.fingerprint
        if (fingerprint != e.fingerprint) {
            fingerprint = e.fingerprint
            firstSeenAtMs = (e.nowMs - e.stableForMs.coerceAtLeast(0L)).coerceAtLeast(1L)
            clicks = 0
            thirdClickAtMs = 0L
        }
        if (screenChanged) return Decision(Action.UNBLOCKED, 0, "screen-fingerprint-changed")
        if (clicks < MAX_TOP_LEFT_CLICKS) {
            if (e.nowMs - firstSeenAtMs < firstThresholdMs) return Decision(Action.WAIT, clicks, "first-stage-threshold")
            if (!e.dispatchAccepted) return Decision(Action.STOP, clicks, "click-dispatch-failed")
            clicks++
            if (clicks == MAX_TOP_LEFT_CLICKS) thirdClickAtMs = e.nowMs
            return Decision(Action.CLICK_TOP_LEFT, clicks, "bounded-top-left-unblock")
        }
        if (e.nowMs - thirdClickAtMs < secondStageMs) return Decision(Action.REPROBE, clicks, "second-stage-grace")
        return Decision(Action.RESTART_CLIENT, clicks, "same-screen-after-three-clicks-and-grace")
    }

    @Synchronized
    fun completeTopLeftClickBatch(expectedFingerprint: String, nowMs: Long): Boolean {
        if (fingerprint != expectedFingerprint || clicks !in 1 until MAX_TOP_LEFT_CLICKS || nowMs <= 0L) return false
        clicks = MAX_TOP_LEFT_CLICKS
        thirdClickAtMs = nowMs
        return true
    }

    @Synchronized fun reset() { fingerprint = null; firstSeenAtMs = 0L; clicks = 0; thirdClickAtMs = 0L }

    @Synchronized fun hasProgress(): Boolean = fingerprint != null

    /** Keep the staged ladder when OCR reclassifies the same exact client frame. */
    @Synchronized fun hasProgressFor(expectedFingerprint: String): Boolean =
        fingerprint != null && fingerprint == expectedFingerprint

    companion object {
        const val DEFAULT_FIRST_THRESHOLD_MS = 60_000L
        const val DEFAULT_SECOND_STAGE_MS = 60_000L
        const val MAX_TOP_LEFT_CLICKS = 3
    }
}
