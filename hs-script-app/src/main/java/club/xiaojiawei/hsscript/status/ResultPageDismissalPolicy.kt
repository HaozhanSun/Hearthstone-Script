package club.xiaojiawei.hsscript.status

/** A dismissal is accepted only after an observation that the result page is gone. */
internal object ResultPageDismissalPolicy {
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

    /** A mandatory-rank cleanup may outlive gameplay or an automatic safety pause,
     * but never a manual pause or a new game.
     */
    fun shouldStopWorker(
        paused: Boolean,
        gameplayMode: Boolean,
        terminalCleanupCapabilityValid: Boolean,
        newGameDetected: Boolean = false,
        automaticPause: Boolean = false,
    ): Boolean =
        (paused && !(automaticPause && terminalCleanupCapabilityValid)) ||
            newGameDetected || (!gameplayMode && !terminalCleanupCapabilityValid)

    fun decide(
        inWar: Boolean,
        resultPageVisible: Boolean?,
        attempt: Int,
        maxAttempts: Int,
        clickAttempts: Int = (attempt - 1).coerceAtLeast(0),
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
            resultPageVisible == null && clickAttempts >= MAX_UNKNOWN_RESULT_FALLBACK_INPUTS -> Decision.EXHAUSTED
        terminalCleanupAuthorized && priorResultPageConfirmed && captureAuthorized &&
            resultPageVisible == null && clickAttempts < MAX_UNKNOWN_RESULT_FALLBACK_INPUTS -> Decision.DISPATCH_CLICK
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

    /** One center-click/Enter pair may recover a known result page when fresh postchecks are UNKNOWN. */
    const val MAX_UNKNOWN_RESULT_FALLBACK_INPUTS = 2
}

/** Null visibility is distinct from no capture: only an authorized current-client frame may ground a terminal fallback. */
internal data class ResultScreenObservation(
    val resultVisible: Boolean?,
    val captureAuthorized: Boolean,
    val visualOnlyResultEvidence: Boolean = false,
    val rankProgressVisible: Boolean = false,
    val destinationScreen: String? = null,
    val destinationConfidence: Int = 0,
)
