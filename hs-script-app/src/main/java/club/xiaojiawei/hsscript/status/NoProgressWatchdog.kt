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
        ESCALATE_PAUSE,
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
    )

    data class Decision(
        val action: RecoveryAction,
        val reason: String,
        val elapsedNoProgressMs: Long,
        val recoveryAttempt: Int,
    )

    companion object {
        const val DEFAULT_NO_PROGRESS_TIMEOUT_MS = 120_000L
        const val DEFAULT_FOREGROUND_FAILURE_THRESHOLD = 3
        const val DEFAULT_MAX_RECOVERY_ATTEMPTS = 2
    }

    private var baselineFingerprint: String? = null
    private var noProgressSinceMs: Long? = null
    private var recoveryAttempts = 0

    fun reset() {
        baselineFingerprint = null
        noProgressSinceMs = null
        recoveryAttempts = 0
    }

    fun observe(snapshot: Snapshot): Decision {
        val firstObservation = baselineFingerprint == null
        val processLineageChanged = !firstObservation &&
            snapshot.currentPid != null && snapshot.boundPid != null &&
                snapshot.currentPid != snapshot.boundPid
        if (processLineageChanged) {
            if (recoveryAttempts > 0) {
                return markRecoveryProgress(snapshot, "process-replaced-after-recovery")
            }
            return recoverOrPause("process-replaced", snapshot.nowMs)
        }
        val logLineageChanged = !firstObservation && snapshot.boundPowerLogPath != null &&
            snapshot.powerLogPath != snapshot.boundPowerLogPath
        if (logLineageChanged) {
            if (recoveryAttempts > 0) {
                return markRecoveryProgress(snapshot, "power-log-rebound-after-recovery")
            }
            return recoverOrPause("stale-power-log-lineage", snapshot.nowMs)
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

        if (snapshot.screen == ScreenExpectation.RESULT) {
            return decision(RecoveryAction.NOOP_RESULT, "result-screen-priority", snapshot.nowMs)
        }

        val elapsed = snapshot.nowMs - (noProgressSinceMs ?: snapshot.nowMs)
        if (snapshot.paddlexInitializing || snapshot.screen == ScreenExpectation.STARTUP ||
            snapshot.screen == ScreenExpectation.MENU_OR_MATCHING
        ) {
            if (elapsed < noProgressTimeoutMs) {
                return decision(RecoveryAction.WAIT_EXPECTED, "expected-startup-or-initialization", snapshot.nowMs)
            }
            return recoverOrPause("startup-or-initialization-timeout", snapshot.nowMs)
        }

        if (snapshot.screen == ScreenExpectation.OPPONENT_TURN ||
            snapshot.screen == ScreenExpectation.ANIMATION
        ) {
            return decision(RecoveryAction.WAIT_EXPECTED, "expected-opponent-turn-or-animation", snapshot.nowMs)
        }
        if (!snapshot.processAlive || snapshot.currentPid == null) {
            return recoverOrPause("process-missing", snapshot.nowMs)
        }
        if (!snapshot.windowPresent) {
            return recoverOrPause("game-window-missing", snapshot.nowMs)
        }

        if (!snapshot.foregroundMatches &&
            snapshot.foregroundFailureCount >= foregroundFailureThreshold
        ) {
            return recoverOrPause("foreground-mismatch-persistent", snapshot.nowMs)
        }

        if (elapsed < noProgressTimeoutMs) {
            return decision(RecoveryAction.WAIT, "below-no-progress-timeout", snapshot.nowMs)
        }

        return when (snapshot.screen) {
            ScreenExpectation.EXTERNAL_MODAL -> recoverOrPause("external-modal", snapshot.nowMs)
            ScreenExpectation.ACTIVE_GAMEPLAY,
            ScreenExpectation.UNKNOWN,
            -> recoverOrPause("authoritative-progress-stalled", snapshot.nowMs)
            else -> decision(RecoveryAction.WAIT_EXPECTED, "expected-screen", snapshot.nowMs)
        }
    }

    private fun recoverOrPause(reason: String, nowMs: Long): Decision {
        val nextAttempt = recoveryAttempts + 1
        if (nextAttempt > maxRecoveryAttempts.coerceAtLeast(0)) {
            return decision(
                RecoveryAction.ESCALATE_PAUSE,
                "$reason-retry-exhausted",
                nowMs,
                nextAttempt,
            )
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

