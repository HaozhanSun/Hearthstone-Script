package club.xiaojiawei.hsscript.status

/**
 * Pure policy for a Beta client that repeatedly presents the same positively
 * identified blocked screen. It deliberately has no idle-time input: a fresh,
 * foreground-authorized capture must keep proving the same screen.
 */
internal class BetaCandidateScreenWatchdog(
    private val stableThresholdMs: Long = DEFAULT_STABLE_THRESHOLD_MS,
    private val repeatBackoffMs: Long = DEFAULT_REPEAT_BACKOFF_MS,
    private val maxRestartsPerIdentity: Int = DEFAULT_MAX_RESTARTS_PER_IDENTITY,
) {

    enum class Action {
        WAIT,
        RESET_IDENTITY,
        WAIT_UNCERTAIN,
        DEFER_NOT_WORKING,
        DEFER_MANUAL_PAUSE,
        DEFER_ACTIVE_GAME,
        DEFER_MULLIGAN,
        DEFER_TERMINAL,
        DEFER_HEALTHY_SCREEN,
        RESTART,
        BACKOFF,
        REARM,
        EXHAUSTED,
    }

    data class Snapshot(
        val nowMs: Long,
        val screen: String?,
        val dialogScreen: String? = null,
        val visualModalConfirmed: Boolean = false,
        val visualModalFingerprint: Long? = null,
        val confidence: Int,
        val captureConfirmed: Boolean,
        val foregroundConfirmed: Boolean,
        val pid: Long?,
        val hwnd: String?,
        val foregroundIdentity: String? = hwnd,
        val working: Boolean,
        val paused: Boolean,
        val inWar: Boolean,
        val mulligan: Boolean,
        val terminal: Boolean,
    )

    data class Decision(
        val action: Action,
        val reason: String,
        val stableForMs: Long,
        val positiveObservations: Int,
        val restartCount: Int,
    )

    private data class Identity(
        val screen: String,
        val pid: Long,
        val hwnd: String,
        val foregroundIdentity: String,
    )

    private var identity: Identity? = null
    private var stableSinceMs: Long? = null
    private var lastObservationAtMs: Long? = null
    private var positiveObservations = 0
    private var restartCount = 0
    private var lastRestartAtMs: Long? = null
    private var exhausted = false

    fun reset() {
        identity = null
        stableSinceMs = null
        lastObservationAtMs = null
        positiveObservations = 0
        restartCount = 0
        lastRestartAtMs = null
        exhausted = false
    }

    fun observe(snapshot: Snapshot): Decision {
        if (!snapshot.working) return defer(Action.DEFER_NOT_WORKING, "worker-not-working")
        if (snapshot.paused) return defer(Action.DEFER_MANUAL_PAUSE, "manual-pause")
        if (snapshot.inWar) return defer(Action.DEFER_ACTIVE_GAME, "active-game")
        if (snapshot.mulligan) return defer(Action.DEFER_MULLIGAN, "mulligan")
        if (snapshot.terminal) return defer(Action.DEFER_TERMINAL, "terminal-state")

        val screen = snapshot.screen?.takeIf { it.isNotBlank() }
        val dialog = snapshot.dialogScreen?.takeIf { it.isNotBlank() }
        val visualModal = snapshot.visualModalFingerprint?.let { fingerprint ->
            if (snapshot.visualModalConfirmed && screen?.equals("UNKNOWN", ignoreCase = true) == true) {
                "VISUAL_MODAL:${java.lang.Long.toUnsignedString(fingerprint, 16)}"
            } else null
        }
        val positiveVisualModal = visualModal != null
        if (screen?.equals("RESULT", ignoreCase = true) == true ||
            screen?.equals("RANK_PROGRESS_CONTINUATION", ignoreCase = true) == true
        ) {
            return defer(Action.DEFER_TERMINAL, "result-screen")
        }
        if (!snapshot.captureConfirmed || !snapshot.foregroundConfirmed ||
            snapshot.pid == null || snapshot.pid <= 0L || snapshot.hwnd.isNullOrBlank() ||
            snapshot.foregroundIdentity.isNullOrBlank() ||
            (snapshot.confidence < MIN_POSITIVE_CONFIDENCE && !positiveVisualModal) ||
            screen == null || (screen.equals("UNKNOWN", ignoreCase = true) && visualModal == null) ||
            dialog?.equals("UNKNOWN", ignoreCase = true) == true
        ) {
            return defer(Action.WAIT_UNCERTAIN, "capture-or-ocr-uncertain")
        }

        // A recognized dialog is the topmost candidate. Never allow an
        // actionable underlay such as DECK_SELECTION to hide it.
        val candidateScreen = dialog?.let { "DIALOG:$it" } ?: visualModal ?: screen!!
        if (dialog == null && visualModal == null && isHealthyScreen(screen)) {
            return defer(Action.DEFER_HEALTHY_SCREEN, "healthy-screen")
        }

        val currentIdentity = Identity(
            screen = candidateScreen,
            pid = snapshot.pid,
            hwnd = snapshot.hwnd,
            foregroundIdentity = snapshot.foregroundIdentity,
        )
        val previousIdentity = identity
        if (previousIdentity != currentIdentity ||
            lastObservationAtMs != null && snapshot.nowMs < lastObservationAtMs!!
        ) {
            identity = currentIdentity
            stableSinceMs = snapshot.nowMs
            lastObservationAtMs = snapshot.nowMs
            positiveObservations = 1
            restartCount = 0
            lastRestartAtMs = null
            exhausted = false
            return decision(Action.RESET_IDENTITY, "candidate-identity-baseline", 0L)
        }

        positiveObservations++
        lastObservationAtMs = snapshot.nowMs
        val stableForMs = (snapshot.nowMs - (stableSinceMs ?: snapshot.nowMs)).coerceAtLeast(0L)
        val lastRestart = lastRestartAtMs
        if (lastRestart != null && snapshot.nowMs - lastRestart < repeatBackoffMs) {
            return decision(Action.BACKOFF, "bounded-repeat-backoff", stableForMs)
        }
        if (lastRestart != null) {
            stableSinceMs = snapshot.nowMs
            positiveObservations = 1
            lastRestartAtMs = null
            return decision(Action.REARM, "repeat-backoff-expired", 0L)
        }
        if (exhausted || restartCount >= maxRestartsPerIdentity.coerceAtLeast(1)) {
            exhausted = true
            return decision(Action.EXHAUSTED, "restart-budget-exhausted", stableForMs)
        }
        if (positiveObservations < MIN_POSITIVE_OBSERVATIONS || stableForMs < stableThresholdMs) {
            return decision(Action.WAIT, "candidate-stable-below-threshold", stableForMs)
        }

        restartCount++
        lastRestartAtMs = snapshot.nowMs
        return decision(Action.RESTART, "candidate-stable-threshold-reached", stableForMs)
    }

    private fun defer(action: Action, reason: String): Decision {
        reset()
        return Decision(action, reason, stableForMs = 0L, positiveObservations = 0, restartCount = 0)
    }

    private fun decision(action: Action, reason: String, nowMs: Long): Decision = Decision(
        action = action,
        reason = reason,
        stableForMs = nowMs,
        positiveObservations = positiveObservations,
        restartCount = restartCount,
    )

    private fun isHealthyScreen(screen: String): Boolean = screen.uppercase() in HEALTHY_SCREENS

    companion object {
        const val DEFAULT_STABLE_THRESHOLD_MS = 120_000L
        const val DEFAULT_REPEAT_BACKOFF_MS = 120_000L
        const val DEFAULT_MAX_RESTARTS_PER_IDENTITY = 2
        const val MIN_POSITIVE_CONFIDENCE = 85
        const val MIN_POSITIVE_OBSERVATIONS = 2

        private val HEALTHY_SCREENS = setOf(
            "HOME",
            "HOME_TASK_OVERLAY",
            "TOURNAMENT",
            "DECK_SELECTION",
            "MATCHMAKING",
            "COLLECTION",
            "GAME_MODE",
            "GAMEPLAY",
        )
    }
}
