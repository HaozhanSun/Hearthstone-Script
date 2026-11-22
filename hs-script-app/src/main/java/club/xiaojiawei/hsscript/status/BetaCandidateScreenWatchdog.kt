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
        DEFER_ACTIVE_MATCHMAKING,
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
        /** A rank-9/5 mandatory surrender is pending; this permits only the
         *  no-live-game mulligan recovery path.  It never overrides inWar. */
        val mandatoryRankSurrenderPending: Boolean = false,
        val terminal: Boolean,
        /** Current Power.log/session proof is required before restarting a result page. */
        val currentSessionReady: Boolean = false,
        /** False when the fresh frame or lifecycle evidence says a queue is active. */
        val noActiveMatchmakingEvidence: Boolean = false,
        /** A no-session constructed hub/deck frame may be the stalled startup candidate. */
        val allowHealthyScreenRecovery: Boolean = false,
        /** True when this probe returned no usable screen observation at all. */
        val observationMissing: Boolean = false,
        /** A prior authorized positive frame still identifies this same candidate. */
        val staleObservationAvailable: Boolean = false,
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
    private var requireFreshBaseline = false
    private var missingObservationSinceMs: Long? = null

    fun reset() {
        identity = null
        stableSinceMs = null
        lastObservationAtMs = null
        positiveObservations = 0
        restartCount = 0
        lastRestartAtMs = null
        exhausted = false
        requireFreshBaseline = false
        missingObservationSinceMs = null
    }

    fun observe(snapshot: Snapshot): Decision {
        val exactMandatoryMulliganScreen = snapshot.mandatoryRankSurrenderPending &&
            snapshot.mulligan && snapshot.screen?.equals("MULLIGAN", ignoreCase = true) == true
        // Manual pause is a hard dispatch fence, but it must not erase a
        // positively identified episode.  Preserve its identity/timer so a
        // later explicit resume can finish the already-earned bounded wait;
        // contradictory screen/PID/HWND/queue evidence still resets below.
        if (snapshot.paused) return deferManualPause(snapshot)
        if (!snapshot.working) return deferWithoutEvidence(snapshot, Action.DEFER_NOT_WORKING, "worker-not-working")
        // A mandatory rank surrender is the one bounded exception to the
        // ordinary live-game fence: if the exact current frame is Mulligan,
        // the surrender barrier is pending, and queue evidence is absent, the
        // user-authorized two-minute Beta restart may abandon this invalid
        // test game. Normal gameplay remains fenced.
        if (snapshot.inWar && !exactMandatoryMulliganScreen) {
            return deferProgress(Action.DEFER_ACTIVE_GAME, "active-game")
        }
        if (snapshot.mulligan && !exactMandatoryMulliganScreen) {
            return deferProgress(Action.DEFER_MULLIGAN, "mulligan")
        }
        if (snapshot.observationMissing) {
            return deferMissingObservation(snapshot)
        }

        val screen = snapshot.screen?.takeIf { it.isNotBlank() }
        val dialog = snapshot.dialogScreen?.takeIf { it.isNotBlank() }
        val terminalScreen = isTerminalScreen(screen)
        if (!snapshot.noActiveMatchmakingEvidence) {
            return deferProgress(Action.DEFER_ACTIVE_MATCHMAKING, "active-matchmaking-evidence")
        }
        if (terminalScreen) {
            // A visible result page is a safe restart candidate only when the
            // current process/session has authoritative terminal proof. This
            // keeps a stale result-looking frame from interrupting a live game.
            if (!snapshot.currentSessionReady || !snapshot.terminal) {
                return deferProgress(Action.DEFER_TERMINAL, "terminal-proof-missing")
            }
        } else if (snapshot.terminal) {
            return deferProgress(Action.DEFER_TERMINAL, "terminal-state")
        }
        val modalFingerprint = snapshot.visualModalFingerprint
            ?.takeIf { snapshot.visualModalConfirmed }
            ?.let { java.lang.Long.toUnsignedString(it, 16) }
        val visualModal = modalFingerprint?.let { fingerprint ->
            if (screen?.equals("UNKNOWN", ignoreCase = true) == true) "VISUAL_MODAL:$fingerprint" else null
        }
        val positiveVisualModal = visualModal != null
        if (!snapshot.captureConfirmed || !snapshot.foregroundConfirmed ||
            snapshot.pid == null || snapshot.pid <= 0L || snapshot.hwnd.isNullOrBlank() ||
            snapshot.foregroundIdentity.isNullOrBlank() ||
            (snapshot.confidence < MIN_POSITIVE_CONFIDENCE && !positiveVisualModal) ||
            screen == null ||
            (screen.equals("UNKNOWN", ignoreCase = true) && visualModal == null) ||
            dialog?.equals("UNKNOWN", ignoreCase = true) == true
        ) {
            return deferWithoutEvidence(snapshot, Action.WAIT_UNCERTAIN, "capture-or-ocr-uncertain")
        }

        // A recognized dialog is the topmost candidate. Never allow an
        // actionable underlay such as DECK_SELECTION to hide it.
        val candidateScreen = dialog?.let { dialogName ->
            "DIALOG:$dialogName${modalFingerprint?.let { fingerprint -> ":$fingerprint" }.orEmpty()}"
        } ?: visualModal ?: screen!!
        if (dialog == null && visualModal == null && isHealthyScreen(screen) &&
            !(snapshot.allowHealthyScreenRecovery && !snapshot.currentSessionReady)
        ) {
            return deferProgress(Action.DEFER_HEALTHY_SCREEN, "healthy-screen")
        }

        val currentIdentity = Identity(
            screen = candidateScreen,
            pid = snapshot.pid,
            hwnd = snapshot.hwnd,
            foregroundIdentity = snapshot.foregroundIdentity,
        )
        val previousIdentity = identity
        val semanticIdentityChanged = previousIdentity?.screen != currentIdentity.screen
        val timeWentBackwards = lastObservationAtMs != null && snapshot.nowMs < lastObservationAtMs!!
        val missingSince = missingObservationSinceMs
        if (missingSince != null) {
            val gapMs = snapshot.nowMs - missingSince
            missingObservationSinceMs = null
            if (gapMs > MAX_MISSING_OBSERVATION_GAP_MS || previousIdentity != currentIdentity) {
                identity = currentIdentity
                stableSinceMs = snapshot.nowMs
                lastObservationAtMs = snapshot.nowMs
                positiveObservations = 1
                requireFreshBaseline = false
                return decision(
                    Action.RESET_IDENTITY,
                    if (gapMs > MAX_MISSING_OBSERVATION_GAP_MS) {
                        "missing-observation-gap-expired"
                    } else {
                        "missing-observation-identity-changed"
                    },
                    0L,
                )
            }
        }
        if (semanticIdentityChanged || timeWentBackwards) {
            identity = currentIdentity
            stableSinceMs = snapshot.nowMs
            lastObservationAtMs = snapshot.nowMs
            positiveObservations = 1
            if (previousIdentity == null) {
                restartCount = 0
                lastRestartAtMs = null
                exhausted = false
            }
            requireFreshBaseline = false
            return decision(
                Action.RESET_IDENTITY,
                if (previousIdentity == null) {
                    "candidate-identity-baseline"
                } else {
                    "candidate-blocked-baseline-preserving-budget"
                },
                0L,
            )
        }

        if (previousIdentity != currentIdentity || requireFreshBaseline) {
            identity = currentIdentity
            stableSinceMs = snapshot.nowMs
            lastObservationAtMs = snapshot.nowMs
            positiveObservations = 1
            requireFreshBaseline = false
            return decision(Action.RESET_IDENTITY, "candidate-runtime-baseline-preserving-budget", 0L)
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
            return decision(Action.EXHAUSTED, "restart-budget-exhausted-manual-attention-required", stableForMs)
        }
        if (positiveObservations < MIN_POSITIVE_OBSERVATIONS || stableForMs < stableThresholdMs) {
            return decision(Action.WAIT, "candidate-stable-below-threshold", stableForMs)
        }

        restartCount++
        lastRestartAtMs = snapshot.nowMs
        return decision(Action.RESTART, "candidate-stable-threshold-reached", stableForMs)
    }

    private fun deferProgress(action: Action, reason: String): Decision {
        reset()
        return Decision(action, reason, stableForMs = 0L, positiveObservations = 0, restartCount = 0)
    }

    /** Preserve the semantic restart budget, but require a fresh positive baseline before aging again. */
    private fun deferWithoutEvidence(snapshot: Snapshot, action: Action, reason: String): Decision {
        if (identity != null) {
            stableSinceMs = snapshot.nowMs
            lastObservationAtMs = snapshot.nowMs
            positiveObservations = 0
            requireFreshBaseline = true
        }
        return Decision(
            action = action,
            reason = reason,
            stableForMs = 0L,
            positiveObservations = positiveObservations,
            restartCount = restartCount,
        )
    }

    private fun deferManualPause(snapshot: Snapshot): Decision = Decision(
        action = Action.DEFER_MANUAL_PAUSE,
        reason = "manual-pause",
        stableForMs = (snapshot.nowMs - (stableSinceMs ?: snapshot.nowMs)).coerceAtLeast(0L),
        positiveObservations = positiveObservations,
        restartCount = restartCount,
    )

    /** A brief null capture is uncertainty, not queue evidence; retain one bounded episode gap. */
    private fun deferMissingObservation(snapshot: Snapshot): Decision {
        val firstMissing = missingObservationSinceMs == null
        if (firstMissing) missingObservationSinceMs = snapshot.nowMs
        val gapMs = (snapshot.nowMs - (missingObservationSinceMs ?: snapshot.nowMs)).coerceAtLeast(0L)
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
        if (snapshot.staleObservationAvailable &&
            stableForMs >= stableThresholdMs &&
            positiveObservations >= MIN_POSITIVE_OBSERVATIONS &&
            snapshot.noActiveMatchmakingEvidence &&
            !snapshot.currentSessionReady &&
            !snapshot.inWar &&
            !snapshot.mulligan &&
            !snapshot.terminal &&
            snapshot.working &&
            !snapshot.paused &&
            !exhausted && restartCount < maxRestartsPerIdentity.coerceAtLeast(1)
        ) {
            restartCount++
            lastRestartAtMs = snapshot.nowMs
            return Decision(
                action = Action.RESTART,
                reason = "candidate-stable-threshold-reached-from-missing-capture",
                stableForMs = stableForMs,
                positiveObservations = positiveObservations,
                restartCount = restartCount,
            )
        }
        if (gapMs > MAX_MISSING_OBSERVATION_GAP_MS) {
            stableSinceMs = snapshot.nowMs
            positiveObservations = 0
            requireFreshBaseline = true
        }
        lastObservationAtMs = snapshot.nowMs
        return Decision(
            action = Action.WAIT_UNCERTAIN,
            reason = if (gapMs > MAX_MISSING_OBSERVATION_GAP_MS) {
                "observation-missing-gap-expired"
            } else {
                "observation-missing"
            },
            stableForMs = (snapshot.nowMs - (stableSinceMs ?: snapshot.nowMs)).coerceAtLeast(0L),
            positiveObservations = positiveObservations,
            restartCount = restartCount,
        )
    }

    private fun decision(action: Action, reason: String, nowMs: Long): Decision = Decision(
        action = action,
        reason = reason,
        stableForMs = nowMs,
        positiveObservations = positiveObservations,
        restartCount = restartCount,
    )

    private fun isHealthyScreen(screen: String): Boolean = screen.uppercase() in HEALTHY_SCREENS

    private fun isTerminalScreen(screen: String?): Boolean = screen?.uppercase() in TERMINAL_SCREENS

    companion object {
        const val DEFAULT_STABLE_THRESHOLD_MS = 120_000L
        const val DEFAULT_REPEAT_BACKOFF_MS = 120_000L
        const val DEFAULT_MAX_RESTARTS_PER_IDENTITY = 2
        /**
         * Foreground capture is serialized with the ordinary recovery loop.
         * A null probe is therefore allowed to span the full candidate
         * threshold plus one monitor tick, but never becomes positive evidence itself.  The next
         * classified frame must still match the exact prior PID/HWND/screen.
         */
        const val MAX_MISSING_OBSERVATION_GAP_MS = DEFAULT_STABLE_THRESHOLD_MS + 10_000L
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
            "STARTUP_LAUNCHER",
        )

        private val TERMINAL_SCREENS = setOf(
            "RESULT",
            "RANK_PROGRESS_CONTINUATION",
            // These are post-game season-rank reward stages. They remain
            // terminal-only candidates: a current session and authoritative
            // terminal proof are required before a hard restart is eligible.
            "SEASON_RANK_AWARD",
            "SEASON_RANK_AWARD_CHEST",
            "SEASON_RANK_AWARD_REWARDS",
        )
    }
}
