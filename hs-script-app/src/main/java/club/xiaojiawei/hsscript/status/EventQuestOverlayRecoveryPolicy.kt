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
        val stableForMs: Long = FIRST_STAGE_DELAY_MS,
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

        val stableForMs = evidence.stableForMs.coerceAtLeast(0L)
        val firstSeenAtMs = evidence.nowMs - stableForMs
        if (firstStageStartedAt <= 0L) firstStageStartedAt = firstSeenAtMs
        if (firstStageStartedAt != firstSeenAtMs && clicksAttempted == 0) {
            firstStageStartedAt = firstSeenAtMs
        }
        if (evidence.nowMs - firstStageStartedAt < FIRST_STAGE_DELAY_MS) {
            return Decision(Action.WAIT_AND_REPROBE, clicksAttempted, "first-stage-minute-pending")
        }

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
        firstStageStartedAt = 0L
        secondStageStartedAt = 0L
        restartIssued = false
    }

    companion object {
        const val FIRST_STAGE_DELAY_MS = 60_000L
        const val MAX_CLICKS = 3
        const val SECOND_STAGE_DELAY_MS = 60_000L
    }

    private var firstStageStartedAt = 0L
}

internal object EventQuestOverlayRecoveryContextPolicy {
    fun allowsDismiss(
        mode: String?,
        phase: String?,
        currentSessionReady: Boolean,
        nextModeGameplay: Boolean,
        activeGame: Boolean,
        activeQueueScreen: Boolean,
        recentInputIntent: Boolean,
        shortRaceWindow: Boolean,
        matchmakingSearchVisual: Boolean,
        terminalOrResult: Boolean,
        working: Boolean,
        paused: Boolean,
    ): Boolean {
        val safeHub = mode == "HUB"
        // Exact visible Event Quest structure over Tournament/FILL_DECK is
        // sufficient safe-underlay proof; Power.log may still be empty after launch.
        val safeDeckSelection = mode == "TOURNAMENT" && phase == "FILL_DECK"
        val activeQueue = activeQueueScreen || matchmakingSearchVisual ||
            if (safeDeckSelection) shortRaceWindow else recentInputIntent
        return (safeHub || safeDeckSelection) && !nextModeGameplay && !activeGame && !activeQueue &&
            !terminalOrResult && working && !paused
    }
}
