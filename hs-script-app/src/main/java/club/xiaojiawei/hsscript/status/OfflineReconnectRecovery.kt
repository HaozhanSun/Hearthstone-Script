package club.xiaojiawei.hsscript.status

/**
 * Bounded state machine for the Blizzard offline/reconnect dialog.
 *
 * The dialog and the loading spinner are different screens. A reconnect
 * click being logged is not proof that the client accepted it, so the caller
 * must report the input result before this machine advances.
 */
internal class OfflineReconnectRecovery(
    private val spinnerWaitMs: Long = DEFAULT_SPINNER_WAIT_MS,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
    private val maxCancelAttempts: Int = DEFAULT_MAX_CANCEL_ATTEMPTS,
) {
    enum class Screen { OFFLINE_PROMPT, RECONNECT_SPINNER, CONNECTED, OTHER }
    enum class State { IDLE, RECONNECT_REQUESTED, WAITING_FOR_SPINNER, SPINNER_VISIBLE, CANCEL_REQUESTED, RECONNECTED, FAILED }
    enum class Action { NONE, FOCUS_AND_CLICK_RECONNECT, WAIT_FOR_SPINNER, FOCUS_AND_CLICK_CANCEL, MARK_RECONNECTED, ESCALATE }
    enum class ProbeLineage { INITIAL, SAME, SCREENSHOT_ROTATED, PROCESS_REPLACED }

    data class Decision(val state: State, val action: Action, val reason: String, val attempt: Int, val deadlineMs: Long)

    companion object {
        const val DEFAULT_SPINNER_WAIT_MS = 10_000L
        const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 2
        const val DEFAULT_MAX_CANCEL_ATTEMPTS = 1
    }

    var state: State = State.IDLE
        private set
    var reconnectAttempts: Int = 0
        private set
    var cancelAttempts: Int = 0
        private set
    private var spinnerDeadlineMs = 0L
    private var lastProbePid: Long? = null
    private var lastScreenshotPath: String? = null

    fun reset() {
        state = State.IDLE
        reconnectAttempts = 0
        cancelAttempts = 0
        spinnerDeadlineMs = 0L
        lastProbePid = null
        lastScreenshotPath = null
    }

    fun observeProbeLineage(processPid: Long?, screenshotPath: String?): ProbeLineage {
        val result = when {
            lastProbePid == null && lastScreenshotPath == null -> ProbeLineage.INITIAL
            processPid != null && lastProbePid != null && processPid != lastProbePid -> ProbeLineage.PROCESS_REPLACED
            screenshotPath != null && lastScreenshotPath != null && screenshotPath != lastScreenshotPath -> ProbeLineage.SCREENSHOT_ROTATED
            else -> ProbeLineage.SAME
        }
        lastProbePid = processPid ?: lastProbePid
        lastScreenshotPath = screenshotPath ?: lastScreenshotPath
        if (result == ProbeLineage.PROCESS_REPLACED) reset()
        return result
    }

    fun observe(screen: Screen, nowMs: Long): Decision = when (screen) {
        Screen.OFFLINE_PROMPT -> observeOfflinePrompt(nowMs)
        Screen.RECONNECT_SPINNER -> observeSpinner(nowMs)
        Screen.CONNECTED -> if (state == State.WAITING_FOR_SPINNER || state == State.CANCEL_REQUESTED || state == State.SPINNER_VISIBLE) {
            state = State.RECONNECTED
            decision(Action.MARK_RECONNECTED, "connected-after-reconnect", nowMs)
        } else decision(Action.NONE, "connected-without-reconnect", nowMs)
        Screen.OTHER -> decision(Action.NONE, "unrelated-screen", nowMs)
    }

    fun reportReconnectDispatched(nowMs: Long, accepted: Boolean): Decision {
        if (!accepted) {
            return if (reconnectAttempts >= maxReconnectAttempts.coerceAtLeast(1)) {
                state = State.FAILED
                decision(Action.ESCALATE, "reconnect-input-rejected-retry-exhausted", nowMs)
            } else {
                state = State.RECONNECT_REQUESTED
                decision(Action.NONE, "reconnect-input-rejected", nowMs)
            }
        }
        state = State.WAITING_FOR_SPINNER
        spinnerDeadlineMs = nowMs + spinnerWaitMs.coerceAtLeast(0L)
        return decision(Action.WAIT_FOR_SPINNER, "reconnect-input-accepted", nowMs)
    }

    fun reportCancelDispatched(nowMs: Long, accepted: Boolean): Decision {
        if (!accepted) {
            return if (cancelAttempts >= maxCancelAttempts.coerceAtLeast(1)) {
                state = State.FAILED
                decision(Action.ESCALATE, "cancel-input-rejected-retry-exhausted", nowMs)
            } else {
                state = State.SPINNER_VISIBLE
                decision(Action.NONE, "cancel-input-rejected", nowMs)
            }
        }
        state = State.CANCEL_REQUESTED
        spinnerDeadlineMs = nowMs + spinnerWaitMs.coerceAtLeast(0L)
        return decision(Action.WAIT_FOR_SPINNER, "cancel-input-accepted", nowMs)
    }

    fun reportProbeFailure(nowMs: Long): Decision {
        if (state != State.WAITING_FOR_SPINNER && state != State.CANCEL_REQUESTED) {
            return decision(Action.NONE, "probe-not-awaiting-reconnect", nowMs)
        }
        state = State.FAILED
        return decision(Action.ESCALATE, "spinner-probe-timeout-or-unknown", nowMs)
    }

    private fun observeOfflinePrompt(nowMs: Long): Decision {
        if (state == State.WAITING_FOR_SPINNER && nowMs < spinnerDeadlineMs) {
            return decision(Action.WAIT_FOR_SPINNER, "offline-prompt-during-spinner-wait", nowMs)
        }
        if (reconnectAttempts >= maxReconnectAttempts.coerceAtLeast(1)) {
            state = State.FAILED
            return decision(Action.ESCALATE, "offline-prompt-retry-exhausted", nowMs)
        }
        reconnectAttempts++
        state = State.RECONNECT_REQUESTED
        return decision(Action.FOCUS_AND_CLICK_RECONNECT, "offline-prompt-detected", nowMs)
    }

    private fun observeSpinner(nowMs: Long): Decision {
        if (state != State.WAITING_FOR_SPINNER && state != State.CANCEL_REQUESTED && state != State.SPINNER_VISIBLE) {
            return decision(Action.NONE, "spinner-without-reconnect-flow", nowMs)
        }
        if (state == State.WAITING_FOR_SPINNER && nowMs < spinnerDeadlineMs) {
            return decision(Action.WAIT_FOR_SPINNER, "spinner-before-ten-second-bound", nowMs)
        }
        if (cancelAttempts >= maxCancelAttempts.coerceAtLeast(1)) {
            state = State.FAILED
            return decision(Action.ESCALATE, "spinner-cancel-retry-exhausted", nowMs)
        }
        cancelAttempts++
        state = State.SPINNER_VISIBLE
        return decision(Action.FOCUS_AND_CLICK_CANCEL, "spinner-still-visible-after-ten-seconds", nowMs)
    }

    private fun decision(action: Action, reason: String, nowMs: Long): Decision = Decision(
        state = state,
        action = action,
        reason = reason,
        attempt = maxOf(reconnectAttempts, cancelAttempts),
        deadlineMs = spinnerDeadlineMs,
    )
}
