package club.xiaojiawei.hsscript.status

/**
 * A pure, bounded policy for a live client whose authoritative event stream
 * or input path has stopped making progress.  The caller owns screen capture
 * and recovery side effects; this class only decides what is safe to do next.
 *
 * A log message is not treated as progress.  Progress is a changed
 * Power.log lineage/cursor or an explicitly observed authoritative state
 * transition.  This distinction is important when a worker keeps logging
 * retries while Windows is sending them to another foreground window.
 */
internal class NoProgressWatchdog(
    private val noProgressTimeoutMs: Long = DEFAULT_NO_PROGRESS_TIMEOUT_MS,
    private val foregroundFailureThreshold: Int = DEFAULT_FOREGROUND_FAILURE_THRESHOLD,
    private val maxRecoveryAttempts: Int = DEFAULT_MAX_RECOVERY_ATTEMPTS,
) {

    enum class ScreenExpectation {
        STARTUP,
        PADDLEX_INITIALIZATION,
        OPPONENT_TURN,
        ANIMATION,
        MULLIGAN,
        RESULT,
        EXTERNAL_MODAL,
        ACTIVE_GAMEPLAY,
        MENU_OR_MATCHING,
        UNKNOWN,
    }

    enum class RecoveryAction {
        WAIT,
        WAIT_EXPECTED,
        NOOP_RESULT,
        DISMISS_EXTERNAL_MODAL,
        REBIND,
        RESTART,
        RECOVERY_RETRY_BACKOFF,
    }

    data class Snapshot(
        val nowMs: Long,
        val mode: String,
        val expectedMode: String,
        val screen: ScreenExpectation,
        val processAlive: Boolean,
        val currentPid: Long?,
        val boundPid: Long?,
        val windowPresent: Boolean,
        val foregroundMatches: Boolean = true,
        val foregroundFailureCount: Int = 0,
        val powerLogPath: String?,
        val boundPowerLogPath: String?,
        val powerLogPosition: Long,
        val powerLogLength: Long,
        val powerLogAgeMs: Long,
        val authoritativeTransition: Boolean = false,
        val paddlexInitializing: Boolean = false,
        val powerLogUsable: Boolean = false,
        val authoritativeLiveMatch: Boolean = false,
        val screenConfirmed: Boolean = false,
    )

    data class Decision(
        val action: RecoveryAction,
        val reason: String,
        val elapsedNoProgressMs: Long,
        val recoveryAttempt: Int,
    )

    companion object {
        const val DEFAULT_NO_PROGRESS_TIMEOUT_MS = 180_000L
        const val DEFAULT_FOREGROUND_FAILURE_THRESHOLD = 3
        const val DEFAULT_MAX_RECOVERY_ATTEMPTS = 2
    }

    private var baselineFingerprint: String? = null
    private var noProgressSinceMs: Long? = null
    private var recoveryAttempts = 0
    private var unboundMissingProcessSinceMs: Long? = null
    private var unboundMissingProcessRecoveryAtMs: Long? = null

    fun reset() {
        baselineFingerprint = null
        noProgressSinceMs = null
        recoveryAttempts = 0
        unboundMissingProcessSinceMs = null
        unboundMissingProcessRecoveryAtMs = null
    }

    fun observe(snapshot: Snapshot): Decision {
        if (snapshot.screen == ScreenExpectation.RESULT) {
            return decision(RecoveryAction.NOOP_RESULT, "result-screen-priority", snapshot.nowMs)
        }
        // A reconstructed live match (including Mulligan) is stronger evidence
        // than stale STARTUP mode. Never feed it to the starter/relaunch chain.
        if (snapshot.authoritativeLiveMatch) {
            val processLineageChanged = snapshot.currentPid != null && snapshot.boundPid != null &&
                snapshot.currentPid != snapshot.boundPid
            if (!snapshot.processAlive || snapshot.currentPid == null) {
                return recoverOrBackoff("live-match-process-missing", snapshot.nowMs)
            }
            if (processLineageChanged) {
                return recoverOrBackoff("live-match-process-lineage-changed", snapshot.nowMs)
            }
            return decision(RecoveryAction.WAIT_EXPECTED, "live-match-preserved", snapshot.nowMs)
        }
        // With no live-match evidence and no game process, an absent Power.log
        // must not hold recovery in WAIT_EXPECTED forever. Still require a full
        // bounded observation window, and space each retry by the same window.
        // A live process with an unbound log remains non-actionable below.
        if ((!snapshot.processAlive || snapshot.currentPid == null) && !snapshot.powerLogUsable) {
            val since = unboundMissingProcessSinceMs ?: snapshot.nowMs.also {
                unboundMissingProcessSinceMs = it
            }
            val elapsed = (snapshot.nowMs - since).coerceAtLeast(0L)
            val lastRecovery = unboundMissingProcessRecoveryAtMs
            if (elapsed < noProgressTimeoutMs ||
                (lastRecovery != null && snapshot.nowMs - lastRecovery < noProgressTimeoutMs)
            ) {
                return decision(RecoveryAction.WAIT_EXPECTED, "process-missing-unbound-log-grace", snapshot.nowMs)
            }
            val recovery = recoverOrBackoff("process-missing-unbound-power-log", snapshot.nowMs)
            if (recovery.action != RecoveryAction.WAIT) unboundMissingProcessRecoveryAtMs = snapshot.nowMs
            return recovery
        }
        // An absent/unbound/unreadable Power.log is UNKNOWN, not evidence that
        // a live process is stuck. Keep the bounded observation window armed,
        // but do not rebind/restart based on mode text or sentinel positions.
        if (!snapshot.powerLogUsable) {
            return decision(RecoveryAction.WAIT_EXPECTED, "power-log-unbound-or-unusable", snapshot.nowMs)
        }
        if (!snapshot.screenConfirmed) {
            return decision(RecoveryAction.WAIT_EXPECTED, "screen-evidence-unconfirmed", snapshot.nowMs)
        }
        val firstObservation = baselineFingerprint == null
        val processLineageChanged = !firstObservation &&
            snapshot.currentPid != null && snapshot.boundPid != null &&
                snapshot.currentPid != snapshot.boundPid
        if (processLineageChanged) {
            if (recoveryAttempts > 0) {
                return markRecoveryProgress(snapshot, "process-replaced-after-recovery")
            }
            return recoverOrBackoff("process-replaced", snapshot.nowMs)
        }
        val logLineageChanged = !firstObservation && snapshot.boundPowerLogPath != null &&
            snapshot.powerLogPath != snapshot.boundPowerLogPath
        if (logLineageChanged) {
            if (recoveryAttempts > 0) {
                return markRecoveryProgress(snapshot, "power-log-rebound-after-recovery")
            }
            return recoverOrBackoff("stale-power-log-lineage", snapshot.nowMs)
        }

        val fingerprint = fingerprint(snapshot)
        val changed = baselineFingerprint != null && baselineFingerprint != fingerprint
        if (baselineFingerprint == null || changed || snapshot.authoritativeTransition) {
            baselineFingerprint = fingerprint
            noProgressSinceMs = snapshot.nowMs
            if (changed || snapshot.authoritativeTransition) recoveryAttempts = 0
            return decision(
                RecoveryAction.WAIT,
                if (changed) "authoritative-progress" else "baseline",
                snapshot.nowMs,
            )
        }
        if (noProgressSinceMs == null) noProgressSinceMs = snapshot.nowMs

        val elapsed = snapshot.nowMs - (noProgressSinceMs ?: snapshot.nowMs)
        if (snapshot.screen == ScreenExpectation.MENU_OR_MATCHING) {
            return decision(RecoveryAction.WAIT_EXPECTED, "visible-menu-or-matchmaking", snapshot.nowMs)
        }
        if (snapshot.paddlexInitializing || snapshot.screen == ScreenExpectation.STARTUP) {
            if (elapsed < noProgressTimeoutMs) {
                return decision(RecoveryAction.WAIT_EXPECTED, "expected-startup-or-initialization", snapshot.nowMs)
            }
            return recoverOrBackoff("startup-or-initialization-timeout", snapshot.nowMs)
        }

        if (snapshot.screen == ScreenExpectation.OPPONENT_TURN ||
            snapshot.screen == ScreenExpectation.ANIMATION ||
            snapshot.screen == ScreenExpectation.MULLIGAN
        ) {
            return decision(RecoveryAction.WAIT_EXPECTED, "expected-live-match-phase", snapshot.nowMs)
        }
        if (!snapshot.processAlive || snapshot.currentPid == null) {
            return recoverOrBackoff("process-missing", snapshot.nowMs)
        }
        if (!snapshot.windowPresent) {
            return recoverOrBackoff("game-window-missing", snapshot.nowMs)
        }

        if (!snapshot.foregroundMatches &&
            snapshot.foregroundFailureCount >= foregroundFailureThreshold
        ) {
            return recoverOrBackoff("foreground-mismatch-persistent", snapshot.nowMs)
        }

        if (elapsed < noProgressTimeoutMs) {
            return decision(RecoveryAction.WAIT, "below-no-progress-timeout", snapshot.nowMs)
        }

        return when (snapshot.screen) {
            ScreenExpectation.EXTERNAL_MODAL -> recoverOrBackoff("external-modal", snapshot.nowMs)
            ScreenExpectation.ACTIVE_GAMEPLAY,
            ScreenExpectation.UNKNOWN,
            -> recoverOrBackoff("authoritative-progress-stalled", snapshot.nowMs)
            else -> decision(RecoveryAction.WAIT_EXPECTED, "expected-screen", snapshot.nowMs)
        }
    }

    private fun recoverOrBackoff(reason: String, nowMs: Long): Decision {
        val nextAttempt = recoveryAttempts + 1
        if (nextAttempt > maxRecoveryAttempts.coerceAtLeast(0)) {
            // Recovery/error handling must not pause the user's session. Re-arm
            // a bounded cycle; the caller keeps recovery alive and the starter
            // chain retains its own capped backoff.
            recoveryAttempts = 0
            noProgressSinceMs = nowMs
            return decision(RecoveryAction.RECOVERY_RETRY_BACKOFF, "$reason-retry-exhausted-rearmed", nowMs, nextAttempt)
        }
        recoveryAttempts = nextAttempt
        val action = when {
            reason == "external-modal" && nextAttempt == 1 -> RecoveryAction.DISMISS_EXTERNAL_MODAL
            nextAttempt == 1 -> RecoveryAction.REBIND
            else -> RecoveryAction.RESTART
        }
        return decision(action, reason, nowMs, nextAttempt)
    }

    private fun markRecoveryProgress(snapshot: Snapshot, reason: String): Decision {
        baselineFingerprint = fingerprint(snapshot)
        noProgressSinceMs = snapshot.nowMs
        recoveryAttempts = 0
        return decision(RecoveryAction.WAIT, reason, snapshot.nowMs)
    }

    private fun decision(
        action: RecoveryAction,
        reason: String,
        nowMs: Long,
        attempt: Int = recoveryAttempts,
    ): Decision {
        val elapsed = noProgressSinceMs?.let { last -> (nowMs - last).coerceAtLeast(0L) } ?: 0L
        return Decision(action, reason, elapsedNoProgressMs = elapsed, recoveryAttempt = attempt)
    }

    private fun fingerprint(snapshot: Snapshot): String = listOf(
        snapshot.powerLogPath ?: "none",
        snapshot.powerLogPosition,
        snapshot.powerLogLength,
        snapshot.mode,
        snapshot.expectedMode,
        snapshot.screen,
    ).joinToString("|")
}

