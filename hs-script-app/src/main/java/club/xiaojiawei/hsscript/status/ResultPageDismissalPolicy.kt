package club.xiaojiawei.hsscript.status

/** A dismissal is accepted only after an observation that the result page is gone. */
internal object ResultPageDismissalPolicy {
    enum class FlowScreen { UNKNOWN, DEFEAT, RANK_PROGRESS, POST_RESULT_DESTINATION }

    data class FlowProgress(
        val lastObserved: FlowScreen = FlowScreen.UNKNOWN,
        val defeatObserved: Boolean = false,
        val rankProgressObserved: Boolean = false,
        val postRankTransitionVerified: Boolean = false,
    )

    enum class Input {
        CENTER_CLICK,
        KEYBOARD_ENTER,
        RETRY_CLICK,
    }

    enum class Decision {
        DISPATCH_CLICK,
        CONFIRMED_CLEARED,
        BLOCKED_UNCONFIRMED_DURING_WAR,
        WAIT_FOR_SCREEN_TRANSITION,
        EXHAUSTED,
        NOT_APPLICABLE,
    }

    /** Preserve the upstream-compatible stale-page sequence for non-terminal recovery. */
    fun inputForClickAttempt(clickAttempt: Int, maxAttempts: Int): Input? = when {
        clickAttempt !in 1..maxAttempts -> null
        clickAttempt == 1 -> Input.CENTER_CLICK
        clickAttempt == 2 -> Input.KEYBOARD_ENTER
        else -> Input.RETRY_CLICK
    }

    /** Repeat the exact live-observed click/Enter pair under the shared terminal episode budget. */
    fun terminalInputForClickAttempt(clickAttempt: Int, maxAttempts: Int): Input? = when {
        clickAttempt !in 1..maxAttempts -> null
        clickAttempt % 2 == 1 -> Input.CENTER_CLICK
        else -> Input.KEYBOARD_ENTER
    }

    /**
     * A mandatory-rank result cleanup may outlive the GAMEPLAY mode when the
     * client has already advanced to tournament/deck selection. Only its
     * one-purpose live capability may keep that worker alive; pause always
     * cancels it.
     */
    fun shouldStopWorker(
        paused: Boolean,
        gameplayMode: Boolean,
        terminalCleanupCapabilityValid: Boolean,
        newGameDetected: Boolean = false,
    ): Boolean = paused || newGameDetected || (!gameplayMode && !terminalCleanupCapabilityValid)

    fun decide(
        inWar: Boolean,
        resultPageVisible: Boolean?,
        attempt: Int,
        maxAttempts: Int,
        clickAttempts: Int = (attempt - 1).coerceAtLeast(0),
        unknownFallbackAttempts: Int = clickAttempts,
        terminalCleanupAuthorized: Boolean = false,
        captureAuthorized: Boolean = false,
        visualOnlyResultEvidence: Boolean = false,
        priorResultPageConfirmed: Boolean = false,
        destinationTransitionConfirmed: Boolean = false,
    ): Decision = when {
        visualOnlyResultEvidence && !terminalCleanupAuthorized -> Decision.WAIT_FOR_SCREEN_TRANSITION
        terminalCleanupAuthorized && resultPageVisible == false && captureAuthorized && destinationTransitionConfirmed ->
            Decision.CONFIRMED_CLEARED
        // `attempt` is a screen probe sequence number, not a dispatched-input
        // count. Probe exhaustion is owned by TerminalPageCleanupCoordinator;
        // only clickAttempts may consume this action budget.
        terminalCleanupAuthorized && clickAttempts >= maxAttempts -> Decision.EXHAUSTED
        terminalCleanupAuthorized && resultPageVisible == false -> Decision.WAIT_FOR_SCREEN_TRANSITION
        resultPageVisible == false -> Decision.CONFIRMED_CLEARED
        terminalCleanupAuthorized && priorResultPageConfirmed && captureAuthorized &&
            resultPageVisible == null && unknownFallbackAttempts >= MAX_UNKNOWN_RESULT_FALLBACK_INPUTS -> Decision.EXHAUSTED
        terminalCleanupAuthorized && priorResultPageConfirmed && captureAuthorized &&
            resultPageVisible == null && unknownFallbackAttempts < MAX_UNKNOWN_RESULT_FALLBACK_INPUTS -> Decision.DISPATCH_CLICK
        terminalCleanupAuthorized && clickAttempts < maxAttempts && captureAuthorized &&
            resultPageVisible == true -> Decision.DISPATCH_CLICK
        !terminalCleanupAuthorized && resultPageVisible == true && clickAttempts < maxAttempts -> Decision.DISPATCH_CLICK
        // Terminal proof permits only the bounded, visible-result allowlist.
        // If capture is unavailable/unknown after that budget, stop; never wait
        // forever or infer success from the input call.
        terminalCleanupAuthorized -> Decision.WAIT_FOR_SCREEN_TRANSITION
        attempt > maxAttempts -> Decision.EXHAUSTED
        resultPageVisible == true -> Decision.EXHAUSTED
        inWar -> Decision.BLOCKED_UNCONFIRMED_DURING_WAR
        else -> Decision.WAIT_FOR_SCREEN_TRANSITION
    }

    /**
     * Map the production screen observation to a retained result-flow trace.
     * UNKNOWN keeps prior evidence; an accepted input is never part of this
     * observation and therefore cannot advance the flow on its own.
     */
    fun observeFlow(
        previous: FlowProgress,
        observation: ResultScreenObservation,
        destinationTransitionConfirmed: Boolean,
    ): FlowProgress {
        val screen = when {
            destinationTransitionConfirmed -> FlowScreen.POST_RESULT_DESTINATION
            observation.rankProgressVisible -> FlowScreen.RANK_PROGRESS
            observation.resultVisible == true -> FlowScreen.DEFEAT
            else -> FlowScreen.UNKNOWN
        }
        return FlowProgress(
            lastObserved = screen,
            defeatObserved = previous.defeatObserved || screen == FlowScreen.DEFEAT,
            rankProgressObserved = previous.rankProgressObserved || screen == FlowScreen.RANK_PROGRESS,
            postRankTransitionVerified = previous.postRankTransitionVerified ||
                (screen == FlowScreen.POST_RESULT_DESTINATION && previous.rankProgressObserved),
        )
    }

    /** Keep the rank reward continuation inside the same result-dismissal policy boundary. */
    fun decideRankProgress(
        rankProgressVisible: Boolean,
        terminalCleanupAuthorized: Boolean,
        captureAuthorized: Boolean,
        rankProgressInputAttempts: Int,
    ): Decision = when (
        PostResultRankProgressPolicy.decide(
            rankProgressVisible = rankProgressVisible,
            terminalCleanupAuthorized = terminalCleanupAuthorized,
            captureAuthorized = captureAuthorized,
            rankProgressInputAttempts = rankProgressInputAttempts,
        )
    ) {
        PostResultRankProgressPolicy.Action.CONTINUE -> Decision.DISPATCH_CLICK
        PostResultRankProgressPolicy.Action.INPUT_BUDGET_EXHAUSTED -> Decision.EXHAUSTED
        PostResultRankProgressPolicy.Action.WAIT_FOR_AUTHORIZED_CAPTURE -> Decision.WAIT_FOR_SCREEN_TRANSITION
        PostResultRankProgressPolicy.Action.NOT_APPLICABLE -> Decision.NOT_APPLICABLE
    }

    /** One center-click/Enter pair may recover a known result page when fresh postchecks are UNKNOWN. */
    const val MAX_UNKNOWN_RESULT_FALLBACK_INPUTS = 2

    const val TERMINAL_RETRY_MIN_INTERVAL_MS = 5_000
    const val TERMINAL_RETRY_MAX_INTERVAL_MS = 7_000
}

/** Null visibility is distinct from no capture: only an authorized current-client frame may ground a terminal fallback. */
internal data class ResultScreenObservation(
    val resultVisible: Boolean?,
    val captureAuthorized: Boolean,
    val visualOnlyResultEvidence: Boolean = false,
    val rankProgressVisible: Boolean = false,
    val continueControlVisible: Boolean = false,
    val destinationScreen: String? = null,
    val destinationConfidence: Int = 0,
    val capturedAtNanos: Long = System.nanoTime(),
)
