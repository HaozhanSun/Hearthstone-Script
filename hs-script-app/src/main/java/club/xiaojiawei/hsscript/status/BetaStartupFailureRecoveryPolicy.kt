package club.xiaojiawei.hsscript.status

/** Pure, paced recovery decisions for a Beta game process that never reaches a usable startup state. */
internal class BetaStartupFailureRecoveryPolicy(
    private val noProgressTimeoutMs: Long = DEFAULT_NO_PROGRESS_TIMEOUT_MS,
) {
    enum class Action { WAIT, REBIND_WINDOW, RESTART_CLIENT, RESTART_STARTER_CHAIN }

    data class DialogEvidence(
        val hwnd: Long,
        val title: String,
        val body: String,
        val hostPid: Long,
        val ownerPid: Long?,
        val firstSeenAtMs: Long,
    )

    data class Snapshot(
        val nowMs: Long,
        val stalledForMs: Long,
        val working: Boolean,
        val paused: Boolean,
        val terminalState: Boolean,
        val liveMatch: Boolean,
        val processAlive: Boolean,
        val currentPid: Long?,
        val processStartedAtMs: Long?,
        val gameWindowMatchesPid: Boolean,
        val powerLogIsCurrentSession: Boolean,
        val powerLogLength: Long,
        val powerLogAgeMs: Long,
        val knownUsableScreen: Boolean,
        val dialog: DialogEvidence?,
    )

    data class Decision(
        val action: Action,
        val reason: String,
        val attempt: Int = 0,
        val retryDelayMs: Long = 0L,
        val currentApplicationErrorDialogHwnd: Long? = null,
    )

    companion object {
        const val DEFAULT_NO_PROGRESS_TIMEOUT_MS = 180_000L
        const val INITIAL_RETRY_DELAY_MS = 15_000L
        const val MAX_RETRY_DELAY_MS = 300_000L
        const val MAX_POWER_LOG_PROGRESS_AGE_MS = 120_000L
        const val APPLICATION_ERROR_CONFIRMATION_MS = 3_000L
        private const val MAX_BACKOFF_ATTEMPTS = 5
    }

    private var attempts = 0
    private var nextAttemptAtMs = 0L

    @Synchronized
    fun reset() {
        attempts = 0
        nextAttemptAtMs = 0L
    }

    @Synchronized
    fun observe(snapshot: Snapshot): Decision {
        if (!snapshot.working || snapshot.paused) return Decision(Action.WAIT, "not-working-or-paused")
        if (snapshot.terminalState) return Decision(Action.WAIT, "terminal-state-priority")

        // An exact native crash dialog correlated by its observation time and
        // the independently verified Hearthstone HWND is stronger evidence
        // than startup grace or a Power.log written before the dialog. Windows
        // may host the dialog under csrss/WerFault, so its own HWND PID is not
        // the game-process identity. This can override stale in-memory live
        // match state, but never authoritative terminal/result evidence.
        val dialog = snapshot.dialog
        if (dialog != null && snapshot.processAlive && snapshot.currentPid != null) {
            val startedAt = snapshot.processStartedAtMs
            if (startedAt == null || dialog.firstSeenAtMs < startedAt ||
                dialog.firstSeenAtMs > snapshot.nowMs
            ) {
                return Decision(Action.WAIT, "stale-or-unattributed-error-dialog")
            }
            if (snapshot.nowMs - dialog.firstSeenAtMs < APPLICATION_ERROR_CONFIRMATION_MS) {
                return Decision(Action.WAIT, "application-error-dialog-confirmation-window")
            }
            if (snapshot.nowMs < nextAttemptAtMs) {
                return Decision(
                    Action.WAIT,
                    "paced-retry-backoff",
                    attempt = attempts,
                    retryDelayMs = nextAttemptAtMs - snapshot.nowMs,
                )
            }
            val action = if (snapshot.gameWindowMatchesPid) Action.RESTART_CLIENT else
                if (attempts == 0) Action.REBIND_WINDOW else Action.RESTART_STARTER_CHAIN
            val reason = if (snapshot.gameWindowMatchesPid) "hearthstone-application-error-dialog"
            else "application-error-dialog-game-window-unverified"
            attempts++
            val delay = retryDelayMs(attempts)
            nextAttemptAtMs = snapshot.nowMs + delay
            return Decision(
                action = action,
                reason = reason,
                attempt = attempts,
                retryDelayMs = delay,
                currentApplicationErrorDialogHwnd = dialog.hwnd.takeIf { snapshot.gameWindowMatchesPid },
            )
        }

        if (snapshot.liveMatch) return Decision(Action.WAIT, "authoritative-live-match-priority")

        val currentLogRecentlyUpdated = snapshot.powerLogIsCurrentSession && snapshot.powerLogLength > 0L &&
            snapshot.powerLogAgeMs in 0..MAX_POWER_LOG_PROGRESS_AGE_MS
        if (currentLogRecentlyUpdated || snapshot.knownUsableScreen) {
            reset()
            return Decision(Action.WAIT, "authoritative-progress-or-usable-screen")
        }

        val zeroLengthLogAge = snapshot.powerLogAgeMs.takeIf {
            snapshot.powerLogIsCurrentSession && snapshot.powerLogLength == 0L
        } ?: 0L
        val stalledFor = maxOf(snapshot.stalledForMs.coerceAtLeast(0L), zeroLengthLogAge)
        if (stalledFor < noProgressTimeoutMs) {
            return Decision(Action.WAIT, "startup-no-progress-grace", retryDelayMs = noProgressTimeoutMs - stalledFor)
        }
        if (snapshot.nowMs < nextAttemptAtMs) {
            return Decision(
                Action.WAIT,
                "paced-retry-backoff",
                attempt = attempts,
                retryDelayMs = nextAttemptAtMs - snapshot.nowMs,
            )
        }

        val action: Action
        val reason: String
        if (!snapshot.processAlive || snapshot.currentPid == null) {
            if (attempts == 0) {
                action = Action.REBIND_WINDOW
                reason = "game-process-missing"
            } else {
                action = Action.RESTART_STARTER_CHAIN
                reason = "game-process-missing-retry"
            }
        } else {
            if (!snapshot.gameWindowMatchesPid) {
                action = if (attempts == 0) Action.REBIND_WINDOW else Action.RESTART_STARTER_CHAIN
                reason = "game-window-unavailable"
            } else {
                return Decision(Action.WAIT, "no-positive-startup-failure-evidence")
            }
        }

        attempts++
        val delay = retryDelayMs(attempts)
        nextAttemptAtMs = snapshot.nowMs + delay
        return Decision(action, reason, attempts, delay)
    }

    private fun retryDelayMs(attempt: Int): Long =
        (INITIAL_RETRY_DELAY_MS * (1L shl (attempt - 1).coerceIn(0, MAX_BACKOFF_ATTEMPTS)))
            .coerceAtMost(MAX_RETRY_DELAY_MS)
}

/** Side-effect boundary shared by live dispatch and offline verification. */
internal object BetaStartupFailureRecoveryDispatch {
    enum class Result { NO_ACTION, WINDOW_REBOUND, WINDOW_REBIND_FAILED, CLIENT_RESTARTED, STARTER_CHAIN_RESTARTED }

    fun shouldBlockForGameState(
        terminalState: Boolean,
        liveMatch: Boolean,
        currentApplicationErrorDialogConfirmed: Boolean,
    ): Boolean = terminalState || (liveMatch && !currentApplicationErrorDialogConfirmed)

    fun dispatch(
        action: BetaStartupFailureRecoveryPolicy.Action,
        rebindWindow: () -> Boolean,
        restartClient: () -> Unit,
        restartStarterChain: () -> Unit,
    ): Result = when (action) {
        BetaStartupFailureRecoveryPolicy.Action.WAIT -> Result.NO_ACTION
        BetaStartupFailureRecoveryPolicy.Action.REBIND_WINDOW ->
            if (rebindWindow()) Result.WINDOW_REBOUND else Result.WINDOW_REBIND_FAILED
        BetaStartupFailureRecoveryPolicy.Action.RESTART_CLIENT -> {
            restartClient()
            Result.CLIENT_RESTARTED
        }
        BetaStartupFailureRecoveryPolicy.Action.RESTART_STARTER_CHAIN -> {
            restartStarterChain()
            Result.STARTER_CHAIN_RESTARTED
        }
    }
}
