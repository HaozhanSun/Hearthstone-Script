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
) {
    enum class Screen { OFFLINE_PROMPT, RECONNECT_SPINNER, CONNECTED, OTHER }
    enum class State { IDLE, RECONNECT_REQUESTED, WAITING_FOR_SPINNER, SPINNER_VISIBLE, RECONNECTED, FAILED }
    enum class Action { NONE, FOCUS_AND_CLICK_RECONNECT, WAIT_FOR_SPINNER, MARK_RECONNECTED, ESCALATE }
    enum class ProbeLineage { INITIAL, SAME, SCREENSHOT_ROTATED, PROCESS_REPLACED }

    data class Decision(val state: State, val action: Action, val reason: String, val attempt: Int, val deadlineMs: Long)

    companion object {
        const val DEFAULT_SPINNER_WAIT_MS = 60_000L
        const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 1
    }

    var state: State = State.IDLE
        private set
    var reconnectAttempts: Int = 0
        private set
    private var spinnerDeadlineMs = 0L
    private var lastProbePid: Long? = null
    private var lastScreenshotPath: String? = null

    fun reset() {
        state = State.IDLE
        reconnectAttempts = 0
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
        Screen.CONNECTED -> if (state == State.WAITING_FOR_SPINNER || state == State.SPINNER_VISIBLE ||
            state == State.FAILED && reconnectAttempts > 0
        ) {
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

    fun reportProbeFailure(nowMs: Long): Decision {
        if (state != State.WAITING_FOR_SPINNER) {
            return decision(Action.NONE, "probe-not-awaiting-reconnect", nowMs)
        }
        state = State.FAILED
        return decision(Action.ESCALATE, "spinner-probe-timeout-or-unknown", nowMs)
    }

    private fun observeOfflinePrompt(nowMs: Long): Decision {
        if (state != State.RECONNECT_REQUESTED) {
            reconnectAttempts = 1
            state = State.RECONNECT_REQUESTED
            spinnerDeadlineMs = nowMs + spinnerWaitMs.coerceAtLeast(0L)
            return decision(Action.WAIT_FOR_SPINNER, "reconnect-transient-grace", nowMs)
        }
        if (nowMs < spinnerDeadlineMs) {
            return decision(Action.WAIT_FOR_SPINNER, "reconnect-transient-grace", nowMs)
        }
        if (reconnectAttempts >= maxReconnectAttempts.coerceAtLeast(1)) {
            state = State.FAILED
            return decision(Action.ESCALATE, "reconnect-persisted-through-grace", nowMs)
        }
        return decision(Action.NONE, "reconnect-grace-expired", nowMs)
    }

    private fun observeSpinner(nowMs: Long): Decision {
        if (state != State.WAITING_FOR_SPINNER && state != State.SPINNER_VISIBLE) {
            return decision(Action.NONE, "spinner-without-reconnect-flow", nowMs)
        }
        if (state == State.WAITING_FOR_SPINNER && nowMs < spinnerDeadlineMs) {
            return decision(Action.WAIT_FOR_SPINNER, "spinner-before-ten-second-bound", nowMs)
        }
        state = State.FAILED
        return decision(Action.ESCALATE, "spinner-still-visible-after-ten-seconds", nowMs)
    }

    private fun decision(action: Action, reason: String, nowMs: Long): Decision = Decision(
        state = state,
        action = action,
        reason = reason,
        attempt = reconnectAttempts,
        deadlineMs = spinnerDeadlineMs,
    )
}

/**
 * Bounded restart escalation for a reconnect screen that remains visible.
 * A reconnect may represent an active pending game, so active-game state does
 * not veto this narrowly classified recovery. Queue and terminal evidence do.
 */
internal object ReconnectRestartSafetyPolicy {
    fun mayRestart(
        exactReconnectVisible: Boolean,
        activeGame: Boolean = false,
        activeMatchmaking: Boolean,
        terminalState: Boolean,
    ): Boolean = exactReconnectVisible && !activeGame && !activeMatchmaking && !terminalState
}

internal object ReconnectActiveGameEvidencePolicy {
    fun isAuthoritative(warLatch: Boolean, currentSessionPowerLog: Boolean): Boolean =
        warLatch && currentSessionPowerLog
}

internal class IndefiniteReconnectRestartPolicy(
    private val thresholdMs: Long = DEFAULT_THRESHOLD_MS,
    private val retryCooldownMs: Long = DEFAULT_RETRY_COOLDOWN_MS,
    private val maxRestarts: Int = DEFAULT_MAX_RESTARTS,
) {
    enum class Decision { WAIT, RESTART_CLIENT_NO_PAUSE, DEFER_ACTIVE_QUEUE, DEFER_TERMINAL, ALERT_OPERATOR }

    private var firstObservedAt = 0L
    private var restartAttempts = 0
    private var lastRestartAt = 0L

    @Synchronized
    fun observe(
        exactReconnectVisible: Boolean,
        nowMs: Long,
        activeQueue: Boolean,
        terminalState: Boolean,
    ): Decision {
        if (!exactReconnectVisible || nowMs <= 0L) return Decision.WAIT
        if (activeQueue) return Decision.DEFER_ACTIVE_QUEUE
        if (terminalState) return Decision.DEFER_TERMINAL
        if (firstObservedAt == 0L) firstObservedAt = nowMs
        if (nowMs - firstObservedAt < thresholdMs) return Decision.WAIT
        if (restartAttempts >= maxRestarts) return Decision.ALERT_OPERATOR
        if (lastRestartAt > 0L && nowMs - lastRestartAt < retryCooldownMs) return Decision.WAIT
        restartAttempts++
        lastRestartAt = nowMs
        firstObservedAt = nowMs
        return Decision.RESTART_CLIENT_NO_PAUSE
    }

    @Synchronized
    fun reset() {
        firstObservedAt = 0L
        restartAttempts = 0
        lastRestartAt = 0L
    }

    @Synchronized
    fun attempts(): Int = restartAttempts

    companion object {
        const val DEFAULT_THRESHOLD_MS = 60_000L
        const val DEFAULT_RETRY_COOLDOWN_MS = 60_000L
        const val DEFAULT_MAX_RESTARTS = 2
    }
}
