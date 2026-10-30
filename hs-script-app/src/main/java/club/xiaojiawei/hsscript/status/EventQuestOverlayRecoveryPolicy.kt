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
        val postGameTerminalProof: Boolean = false,
        val freshEmptySessionLoginProof: Boolean = false,
        val stableForMs: Long = FIRST_STAGE_DELAY_MS,
        val automationRunId: Long = 0L,
        val semanticScreenIdentity: String = "EVENT_QUEST_OVERLAY",
    )

    data class Decision(val action: Action, val clickNumber: Int, val reason: String)

    private var clicksAttempted = 0
    private var secondStageStartedAt = 0L
    private var restartIssued = false
    private var restartScope: String? = null
    private var restartCount = 0

    @Synchronized
    fun observe(evidence: Evidence): Decision {
        if (!evidence.captureTrusted || evidence.nowMs <= 0L) return Decision(Action.STOP, clicksAttempted, "capture-untrusted")
        val nextRestartScope = "${evidence.automationRunId}|${evidence.semanticScreenIdentity}"
        if (restartScope != nextRestartScope) {
            restartScope = nextRestartScope
            restartCount = 0
        }
        if (evidence.verifiedSafeTransition) {
            reset()
            restartCount = 0
            return Decision(Action.UNBLOCK_SAFE_TRANSITION, 0, "fresh-safe-screen-transition")
        }
        if (!evidence.working) return Decision(Action.DEFER, clicksAttempted, "script-not-working")
        if (evidence.paused) return Decision(Action.DEFER, clicksAttempted, "paused")
        if (evidence.activeGame || evidence.activeQueue ||
            evidence.terminalOrResult && !evidence.postGameTerminalProof && !evidence.freshEmptySessionLoginProof
        ) {
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
        if (restartCount >= MAX_CLIENT_RESTARTS_PER_RUN_AND_SCREEN) {
            return Decision(Action.STOP, clicksAttempted, "client-restart-cap-exhausted")
        }
        restartCount++
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
        const val MAX_CLIENT_RESTARTS_PER_RUN_AND_SCREEN = 1
    }

    private var firstStageStartedAt = 0L
}

internal object EventQuestOverlayRecoveryContextPolicy {
    fun allowsFreshEmptySessionLoginTransition(
        questOverlayVisible: Boolean,
        centeredModalVisible: Boolean,
        freshEmptySessionLoginProof: Boolean,
    ): Boolean = !questOverlayVisible && !centeredModalVisible && freshEmptySessionLoginProof

    fun allowsFreshEmptySessionLoginProof(
        mode: String?,
        phase: String?,
        exactForegroundCapture: Boolean,
        emptyCurrentSessionPowerLog: Boolean,
        currentSessionReady: Boolean,
        nextModeGameplay: Boolean,
        activeGame: Boolean,
        activeQueueScreen: Boolean,
        recentInputIntent: Boolean,
        shortRaceWindow: Boolean,
        matchmakingSearchVisual: Boolean,
        reconnectDialogVisual: Boolean,
        mandatoryRankGuard: Boolean,
        working: Boolean,
        paused: Boolean,
    ): Boolean = mode == "LOGIN" && phase == "FILL_DECK" && exactForegroundCapture && emptyCurrentSessionPowerLog &&
        !currentSessionReady && !nextModeGameplay && !activeGame && !activeQueueScreen &&
        !recentInputIntent && !shortRaceWindow && !matchmakingSearchVisual && !reconnectDialogVisual &&
        !mandatoryRankGuard && working && !paused

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
        postGameTerminalProof: Boolean = false,
        freshEmptySessionLoginProof: Boolean = false,
        reconnectDialogVisual: Boolean = false,
    ): Boolean {
        val safeHub = mode == "HUB"
        // Exact visible Event Quest structure over Tournament/FILL_DECK is
        // sufficient safe-underlay proof; only a verified post-game result
        // may override the stale terminal phase latch in that same state.
        val safeDeckSelection = mode == "TOURNAMENT" && phase == "FILL_DECK"
        val safeEmptySessionLogin = mode == "LOGIN" && phase == "FILL_DECK" &&
            freshEmptySessionLoginProof
        val activeQueue = activeQueueScreen || matchmakingSearchVisual ||
            if (safeDeckSelection) shortRaceWindow else recentInputIntent || shortRaceWindow
        val terminalContextSafe = !terminalOrResult ||
            (safeDeckSelection && postGameTerminalProof) || safeEmptySessionLogin
        return (safeHub || safeDeckSelection || safeEmptySessionLogin) && !reconnectDialogVisual &&
            !nextModeGameplay && !activeGame && !activeQueue && terminalContextSafe && working && !paused
    }
}

/** Last injectable gate before a single exact-window quest-overlay input is sent. */
internal object EventQuestOverlayClickDispatch {
    fun dispatch(
        action: EventQuestOverlayRecoveryPolicy.Action,
        freshCaptureTrusted: Boolean,
        foregroundVerified: Boolean,
        safeContext: Boolean,
        click: () -> Boolean,
    ): Boolean = action == EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT &&
        freshCaptureTrusted && foregroundVerified && safeContext && click()
}
