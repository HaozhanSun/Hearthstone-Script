package club.xiaojiawei.hsscript.status

/** Bounded recovery for a visually exact Event Quest modal over a safe menu/deck-selection screen. */
internal class EventQuestOverlayRecoveryPolicy(
    private val maxClicks: Int = MAX_CLICKS,
    private val secondStageDelayMs: Long = SECOND_STAGE_DELAY_MS,
) {
    enum class Action { CLICK_TOP_LEFT, WAIT_AND_REPROBE, UNBLOCK_SAFE_TRANSITION, RESTART_CLIENT, DEFER, STOP }

    data class Evidence(
        val nowMs: Long,
        val captureTrusted: Boolean,
        val exactQuestOverlay: Boolean,
        val safeMenuOrDeckState: Boolean,
        val verifiedSafeTransition: Boolean,
        val working: Boolean,
        val paused: Boolean,
        val activeGame: Boolean,
        val activeQueue: Boolean,
        val terminalOrResult: Boolean,
    )

    data class Decision(val action: Action, val clickNumber: Int, val reason: String)

    private var clicksAttempted = 0
    private var secondStageStartedAt = 0L
    private var restartIssued = false

    @Synchronized
    fun observe(evidence: Evidence): Decision {
        if (!evidence.captureTrusted || evidence.nowMs <= 0L) return Decision(Action.STOP, clicksAttempted, "capture-untrusted")
        if (evidence.verifiedSafeTransition) {
            reset()
            return Decision(Action.UNBLOCK_SAFE_TRANSITION, 0, "fresh-safe-screen-transition")
        }
        if (!evidence.working) return Decision(Action.DEFER, clicksAttempted, "script-not-working")
        if (evidence.paused) return Decision(Action.DEFER, clicksAttempted, "paused")
        if (evidence.activeGame || evidence.activeQueue || evidence.terminalOrResult) {
            return Decision(Action.STOP, clicksAttempted, "game-queue-or-result-fence")
        }
        if (!evidence.safeMenuOrDeckState) return Decision(Action.STOP, clicksAttempted, "unsafe-underlay-state")
        if (!evidence.exactQuestOverlay) return Decision(Action.STOP, clicksAttempted, "exact-overlay-not-confirmed")
        if (restartIssued) return Decision(Action.STOP, clicksAttempted, "restart-already-issued")

        if (clicksAttempted < maxClicks) {
            clicksAttempted++
            if (clicksAttempted == maxClicks) secondStageStartedAt = evidence.nowMs
            return Decision(Action.CLICK_TOP_LEFT, clicksAttempted, "verified-exact-overlay")
        }
        if (secondStageStartedAt <= 0L || evidence.nowMs < secondStageStartedAt) {
            return Decision(Action.STOP, clicksAttempted, "second-stage-clock-invalid")
        }
        if (evidence.nowMs - secondStageStartedAt < secondStageDelayMs) {
            return Decision(Action.WAIT_AND_REPROBE, clicksAttempted, "second-stage-minute-pending")
        }
        return Decision(Action.RESTART_CLIENT, clicksAttempted, "exact-overlay-persisted-after-second-stage")
    }

    @Synchronized
    fun confirmRestartIssued() {
        restartIssued = true
    }

    @Synchronized
    fun clicksAttempted(): Int = clicksAttempted

    @Synchronized
    fun reset() {
        clicksAttempted = 0
        secondStageStartedAt = 0L
        restartIssued = false
    }

    companion object {
        const val MAX_CLICKS = 3
        const val SECOND_STAGE_DELAY_MS = 60_000L
    }
}

internal object EventQuestOverlayRecoveryContextPolicy {
    fun allowsDismiss(
        mode: String?,
        phase: String?,
        currentSessionReady: Boolean,
        nextModeGameplay: Boolean,
        activeGame: Boolean,
        activeQueue: Boolean,
        terminalOrResult: Boolean,
        working: Boolean,
        paused: Boolean,
    ): Boolean {
        val safeHub = mode == "HUB"
        val safeDeckSelection = mode == "TOURNAMENT" && phase == "FILL_DECK" && currentSessionReady
        return (safeHub || safeDeckSelection) && !nextModeGameplay && !activeGame && !activeQueue &&
            !terminalOrResult && working && !paused
    }
}
