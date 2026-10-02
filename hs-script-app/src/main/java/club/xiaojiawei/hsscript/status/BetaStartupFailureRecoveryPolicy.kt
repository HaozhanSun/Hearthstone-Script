package club.xiaojiawei.hsscript.status

/** Pure, paced recovery decisions for a Beta game process that never reaches a usable startup state. */
internal class BetaStartupFailureRecoveryPolicy(
    private val noProgressTimeoutMs: Long = DEFAULT_NO_PROGRESS_TIMEOUT_MS,
) {
    enum class Action { WAIT, REBIND_WINDOW, RESTART_CLIENT, RESTART_STARTER_CHAIN, DISMISS_PERSISTENT_DIALOG }

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
        val escalated: Boolean = false,
    )

    companion object {
        const val DEFAULT_NO_PROGRESS_TIMEOUT_MS = 180_000L
        const val INITIAL_RETRY_DELAY_MS = 15_000L
        const val MAX_RETRY_DELAY_MS = 300_000L
        const val MAX_POWER_LOG_PROGRESS_AGE_MS = 120_000L
        const val APPLICATION_ERROR_CONFIRMATION_MS = 3_000L
        const val MAX_APPLICATION_ERROR_RECOVERY_ATTEMPTS = 4
        const val ESCALATED_RETRY_COOLDOWN_MS = 300_000L
        private const val MAX_BACKOFF_ATTEMPTS = 5
    }

    private var attempts = 0
    private var nextAttemptAtMs = 0L
    private var applicationErrorAttempts = 0
    private var lastApplicationErrorDialogHwnd: Long? = null

    @Synchronized
    fun reset() {
        attempts = 0
        nextAttemptAtMs = 0L
        applicationErrorAttempts = 0
        lastApplicationErrorDialogHwnd = null
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
            if (startedAt == null || dialog.firstSeenAtMs > snapshot.nowMs) {
                return Decision(Action.WAIT, "stale-or-unattributed-error-dialog")
            }
            val persistedAcrossReplacement = dialog.firstSeenAtMs < startedAt &&
                applicationErrorAttempts > 0 &&
                dialog.hwnd == lastApplicationErrorDialogHwnd &&
                snapshot.gameWindowMatchesPid
            if (dialog.firstSeenAtMs < startedAt && !persistedAcrossReplacement) {
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
            if (applicationErrorAttempts >= MAX_APPLICATION_ERROR_RECOVERY_ATTEMPTS) {
                val nextAttempt = applicationErrorAttempts + 1
                val action = if (nextAttempt % 2 == 1) Action.RESTART_CLIENT else Action.DISMISS_PERSISTENT_DIALOG
                applicationErrorAttempts = nextAttempt
                attempts++
                lastApplicationErrorDialogHwnd = dialog.hwnd
                nextAttemptAtMs = snapshot.nowMs + ESCALATED_RETRY_COOLDOWN_MS
                return Decision(
                    action = action,
                    reason = "application-error-dialog-escalated-retry",
                    attempt = attempts,
                    retryDelayMs = ESCALATED_RETRY_COOLDOWN_MS,
                    currentApplicationErrorDialogHwnd = dialog.hwnd,
                    escalated = true,
                )
            }
            val action = when {
                !snapshot.gameWindowMatchesPid ->
                    if (applicationErrorAttempts == 0) Action.REBIND_WINDOW else Action.RESTART_STARTER_CHAIN
                persistedAcrossReplacement && applicationErrorAttempts % 2 == 1 ->
                    Action.DISMISS_PERSISTENT_DIALOG
                else -> Action.RESTART_CLIENT
            }
            val reason = when (action) {
                Action.DISMISS_PERSISTENT_DIALOG -> "persistent-application-error-dialog-close"
                Action.RESTART_CLIENT -> if (persistedAcrossReplacement) {
                    "persistent-application-error-dialog-hard-restart"
                } else {
                    "hearthstone-application-error-dialog"
                }
                Action.REBIND_WINDOW, Action.RESTART_STARTER_CHAIN ->
                    "application-error-dialog-game-window-unverified"
                else -> "application-error-dialog-recovery"
            }
            applicationErrorAttempts++
            attempts++
            lastApplicationErrorDialogHwnd = dialog.hwnd
            val delay = retryDelayMs(applicationErrorAttempts)
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
    enum class Result {
        NO_ACTION,
        WINDOW_REBOUND,
        WINDOW_REBIND_FAILED,
        CLIENT_RESTARTED,
        STARTER_CHAIN_RESTARTED,
        PERSISTENT_DIALOG_DISMISSED,
        PERSISTENT_DIALOG_DISMISS_FAILED,
    }

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
        dismissPersistentDialog: () -> Boolean = { false },
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
        BetaStartupFailureRecoveryPolicy.Action.DISMISS_PERSISTENT_DIALOG ->
            if (dismissPersistentDialog()) Result.PERSISTENT_DIALOG_DISMISSED
            else Result.PERSISTENT_DIALOG_DISMISS_FAILED
    }
}
