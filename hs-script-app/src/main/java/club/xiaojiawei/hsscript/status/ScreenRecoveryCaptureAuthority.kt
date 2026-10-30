package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.awt.Rectangle

internal data class CapturedWindowIdentity(
    val rootWindow: Long,
    val processId: Int,
)

/** Pre-session capture is permitted only for a named, narrow probe purpose. */
internal enum class ScreenRecoveryCapturePurpose {
    SCREEN_STATE_RECOVERY,
    /** Read-only stale-screen classification after a long, non-game state stall. */
    STALE_CLIENT_PROBE,
    /** Read-only classification during a freshly requested startup handoff. */
    STARTUP_CLIENT_PROBE,
    /** Narrow pre-session startup capture used to classify online menu, reconnect, or Yogg state. */
    STARTUP_YOGG_PROBE,
    /** Client probe after one minute without progress; input still requires fresh screen proof and the final action gate. */
    IDLE_CLIENT_PROBE,
    /** Fresh SEARCHING observation immediately after a verified queue input. */
    MATCHMAKING_SEARCH,
    MATCHMAKING_ERROR_DIALOG,
}

    /** Pre-session capture and input authorization policies. */
internal object ScreenRecoveryCapturePurposePolicy {
    const val STALE_CLIENT_PROBE_AFTER_MS = 120_000L
    const val IDLE_CLIENT_PROBE_AFTER_MS = 60_000L

    /** Refuse generic pre-session recovery before it can disturb the foreground window. */
    fun preflightRejectionReason(
        currentSessionReady: Boolean,
        preSessionQueueModalAuthorized: Boolean,
        preSessionMatchmakingAuthorized: Boolean,
        preSessionStaleClientProbeAuthorized: Boolean = false,
        preSessionIdleClientProbeAuthorized: Boolean = false,
        preSessionStartupClientProbeAuthorized: Boolean = false,
        preSessionYoggProbeAuthorized: Boolean = false,
    ): String? = if (
        currentSessionReady || preSessionQueueModalAuthorized || preSessionMatchmakingAuthorized ||
        preSessionStaleClientProbeAuthorized || preSessionIdleClientProbeAuthorized ||
        preSessionStartupClientProbeAuthorized || preSessionYoggProbeAuthorized
    ) null else "current-game-session-not-ready"

    /**
     * Generic stale-state recovery may inspect the fresh queue screen before
     * Hearthstone has opened the new Power.log game segment, but only after a
     * recent queue input has established bounded matchmaking intent.
     */
    fun purposeForStateRecovery(
        tournamentMode: Boolean,
        nonGameplayScreen: Boolean = tournamentMode,
        activeGame: Boolean,
        mulligan: Boolean,
        terminal: Boolean,
        recentInputIntent: Boolean,
        stuckForMs: Long = 0L,
        warModelAuthoritative: Boolean = true,
        startupProbe: Boolean = false,
        noActiveQueueEvidence: Boolean = true,
    ): ScreenRecoveryCapturePurpose = if (
        allowsPreSessionMatchmaking(
            purpose = ScreenRecoveryCapturePurpose.MATCHMAKING_SEARCH,
            tournamentMode = tournamentMode,
            activeGame = activeGame,
            mulligan = mulligan,
            terminal = terminal,
            recentInputIntent = recentInputIntent,
        )
    ) {
        ScreenRecoveryCapturePurpose.MATCHMAKING_SEARCH
    } else if (startupProbe && !warModelAuthoritative && !activeGame && noActiveQueueEvidence) {
        ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE
    } else if (allowsPreSessionStaleClientProbe(
            purpose = ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
            nonGameplayScreen = nonGameplayScreen,
            activeGame = activeGame,
            mulligan = mulligan,
            terminal = terminal,
            stuckForMs = stuckForMs,
            warModelAuthoritative = warModelAuthoritative,
        )
    ) {
        ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE
    } else {
        ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY
    }

    /**
     * Grants capture only, never input. A fresh screen classification must
     * still protect MATCHMAKING and confirm any recovery action.
     */
    fun allowsPreSessionStaleClientProbe(
        purpose: ScreenRecoveryCapturePurpose,
        nonGameplayScreen: Boolean,
        activeGame: Boolean,
        mulligan: Boolean,
        terminal: Boolean,
        stuckForMs: Long,
        warModelAuthoritative: Boolean = true,
    ): Boolean = purpose == ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE &&
        stuckForMs >= STALE_CLIENT_PROBE_AFTER_MS && (nonGameplayScreen || !warModelAuthoritative) &&
        !activeGame && (!warModelAuthoritative || (!mulligan && !terminal))

    fun allowsPreSessionStartupClientProbe(
        purpose: ScreenRecoveryCapturePurpose,
        currentSessionReady: Boolean,
    ): Boolean = purpose == ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE && !currentSessionReady

    fun allowsPreSessionYoggProbe(
        purpose: ScreenRecoveryCapturePurpose,
        currentSessionReady: Boolean,
        activeGame: Boolean,
        noActiveQueueEvidence: Boolean,
    ): Boolean = purpose == ScreenRecoveryCapturePurpose.STARTUP_YOGG_PROBE &&
        !currentSessionReady && !activeGame && noActiveQueueEvidence

    fun allowsPreSessionIdleClientProbe(
        purpose: ScreenRecoveryCapturePurpose,
        currentSessionReady: Boolean,
        stuckForMs: Long,
    ): Boolean = purpose == ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE &&
        !currentSessionReady && stuckForMs >= IDLE_CLIENT_PROBE_AFTER_MS

    fun allowsStaleProbeRecovery(screenKind: String): Boolean = screenKind.uppercase() in setOf(
        "MATCHMAKING",
        // Fresh deck-selection recovery re-enters only the configured strategy;
        // its handler still requires confidence-backed fresh evidence and all
        // existing strategy and terminal/rank safeguards.
        "DECK_SELECTION",
        "RECONNECT",
        "RECONNECT_FAILURE",
        "RECONNECT_SPINNER",
        "SERVER_UNAVAILABLE",
    )

    enum class StaleProbeRecoveryDecision {
        CLASSIFICATION_ONLY,
        APPLY_RECOGNIZED_RECOVERY,
        APPLY_CONFIGURED_DECK_SELECTION,
    }

    /** Resolve whether a fresh stale-client classification may enter recovery. */
    fun staleProbeRecoveryDecision(
        purpose: ScreenRecoveryCapturePurpose,
        screenKind: String,
        confidence: Int,
        evidence: String,
        freshObservation: Boolean,
        configuredDeck: Boolean,
        configuredRunMode: Boolean,
        activeGame: Boolean,
        activeMatchmaking: Boolean,
    ): StaleProbeRecoveryDecision {
        val kind = screenKind.uppercase()
        if (purpose != ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE ||
            !allowsStaleProbeRecovery(kind)
        ) return StaleProbeRecoveryDecision.CLASSIFICATION_ONLY

        if (kind != "DECK_SELECTION") return StaleProbeRecoveryDecision.APPLY_RECOGNIZED_RECOVERY

        if (activeGame || activeMatchmaking || !configuredDeck || !configuredRunMode ||
            !freshObservation || confidence < 85 || evidence != "deck-selection-title"
        ) return StaleProbeRecoveryDecision.CLASSIFICATION_ONLY

        return StaleProbeRecoveryDecision.APPLY_CONFIGURED_DECK_SELECTION
    }

    fun allowsStartupOnlineMenu(screenKind: String): Boolean = screenKind.uppercase() in setOf(
        "HOME", "TOURNAMENT", "DECK_SELECTION", "HOME_TASK_OVERLAY", "MATCHMAKING",
        "GAME_MODE", "COLLECTION", "PACK_OPENING", "SHOP_OVERLAY",
    )

    fun allowsPreSessionQueueModal(
        purpose: ScreenRecoveryCapturePurpose,
        tournamentMode: Boolean,
        activeGame: Boolean,
        mulligan: Boolean,
        terminal: Boolean,
    ): Boolean = PreSessionQueueModalCapturePolicy.isAuthorized(
        exactQueueModalPurpose = purpose == ScreenRecoveryCapturePurpose.MATCHMAKING_ERROR_DIALOG,
        tournamentMode = tournamentMode,
        activeGame = activeGame,
        mulligan = mulligan,
        terminal = terminal,
    )

    /**
     * A just-dispatched queue start is the bounded authority for observing a
     * new SEARCHING screen before Hearthstone creates the current Power.log.
     * This authorizes capture only; screen classification still gates recovery.
     */
    fun allowsPreSessionMatchmaking(
        purpose: ScreenRecoveryCapturePurpose,
        tournamentMode: Boolean,
        activeGame: Boolean,
        mulligan: Boolean,
        terminal: Boolean,
        recentInputIntent: Boolean,
    ): Boolean = purpose == ScreenRecoveryCapturePurpose.MATCHMAKING_SEARCH &&
        recentInputIntent && tournamentMode && !activeGame && !mulligan && !terminal
}

/** Exact evidence needed to permit only HUB -> Traditional Battle pre-session entry. */
internal object StartupHubModeChooserPolicy {
    fun shouldDeferGenericIdleUnblock(
        screenKind: String?,
        screenMode: String?,
        hubModeChooserVisual: Boolean,
    ): Boolean = screenKind.equals("TOURNAMENT", ignoreCase = true) &&
        screenMode == "HUB" && hubModeChooserVisual

    fun canAuthorizeTraditionalBattleSelection(
        purpose: ScreenRecoveryCapturePurpose,
        currentSessionReady: Boolean,
        screenKind: String?,
        confidence: Int,
        evidence: String?,
        currentMode: String?,
        modalVisible: Boolean,
        activeGame: Boolean,
        activeMatchmaking: Boolean,
        trustedCurrentWindow: Boolean,
    ): Boolean = purpose in setOf(
        ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
        ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
        ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
    ) && !currentSessionReady && screenKind.equals("TOURNAMENT", ignoreCase = true) &&
        confidence >= MIN_CONFIDENCE && evidence in HUB_CHOOSER_EVIDENCE && currentMode == "HUB" &&
        !modalVisible && !activeGame && !activeMatchmaking && trustedCurrentWindow

    private val HUB_CHOOSER_EVIDENCE = setOf(
        "hub-mode-chooser-visual-and-text",
        "hub-mode-chooser-visual",
    )
    private const val MIN_CONFIDENCE = 90
}

/** Game-phase latches are usable as gameplay evidence only with an authoritative current session. */
internal object StartupGamePhaseEvidencePolicy {
    fun hasAuthoritativeActiveGame(warModelAuthoritative: Boolean, warInProgress: Boolean): Boolean =
        warModelAuthoritative && warInProgress
}

/** During the bounded start-click settle window, stale menu OCR cannot move modes. */
internal object MatchmakingEntryRecoveryPolicy {
    private val screensThatProveProgress = setOf(
        "MATCHMAKING", "GAMEPLAY", "RESULT", "RANK_PROGRESS_CONTINUATION",
    )

    fun shouldBlockModeChangingRecovery(entryGuardActive: Boolean, screenKind: String?): Boolean =
        entryGuardActive && screenKind?.uppercase() !in screensThatProveProgress
}

/** Preserve the narrower HUB chooser proof when the notice postcheck sees that screen. */
internal object StartupNoticePostcheckProofPolicy {
    enum class Proof { HUB_MODE_CHOOSER, ONLINE_MENU, NONE }

    fun choose(
        purpose: ScreenRecoveryCapturePurpose,
        currentSessionReady: Boolean,
        screenKind: String?,
        confidence: Int,
        evidence: String?,
        currentMode: String?,
        modalVisible: Boolean,
        activeGame: Boolean,
        activeMatchmaking: Boolean,
        terminalState: Boolean,
        trustedCurrentWindow: Boolean,
    ): Proof {
        if (StartupHubModeChooserPolicy.canAuthorizeTraditionalBattleSelection(
                purpose = purpose,
                currentSessionReady = currentSessionReady,
                screenKind = screenKind,
                confidence = confidence,
                evidence = evidence,
                currentMode = currentMode,
                modalVisible = modalVisible,
                activeGame = activeGame || terminalState,
                activeMatchmaking = activeMatchmaking,
                trustedCurrentWindow = trustedCurrentWindow,
            )
        ) return Proof.HUB_MODE_CHOOSER

        return if (
            !modalVisible && !activeGame && !activeMatchmaking && !terminalState && trustedCurrentWindow &&
            screenKind?.let(ScreenRecoveryCapturePurposePolicy::allowsStartupOnlineMenu) == true
        ) Proof.ONLINE_MENU else Proof.NONE
    }
}

internal data class ScreenRecoveryCaptureEvidence(
    val processAlive: Boolean,
    val currentSessionReady: Boolean,
    val target: CapturedWindowIdentity?,
    val foregroundBefore: CapturedWindowIdentity?,
    val foregroundAfter: CapturedWindowIdentity?,
    val clientBounds: Rectangle?,
    val captureBounds: Rectangle?,
    val imageWidth: Int,
    val imageHeight: Int,
    val visibleOwnersBefore: List<CapturedWindowIdentity?>,
    val visibleOwnersAfter: List<CapturedWindowIdentity?>,
    val purpose: ScreenRecoveryCapturePurpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY,
    val preSessionQueueModalAuthorized: Boolean = false,
    val preSessionMatchmakingAuthorized: Boolean = false,
    val preSessionStaleClientProbeAuthorized: Boolean = false,
    val preSessionIdleClientProbeAuthorized: Boolean = false,
    val preSessionStartupClientProbeAuthorized: Boolean = false,
    val preSessionYoggProbeAuthorized: Boolean = false,
    val currentGameProcessId: Long? = target?.processId?.toLong(),
    val targetWindowVisibleBefore: Boolean = true,
    val targetWindowVisibleAfter: Boolean = true,
)

/** Pixel-level authority checks for a desktop capture made while holding a game HWND foreground. */
internal object ScreenRecoveryCaptureAuthority {
    const val MIN_TARGET_VISIBLE_RATIO = 0.80
    private const val GRID_COLUMNS = 9
    private const val GRID_ROWS = 7
    private const val MIN_WIDTH = 400
    private const val MIN_HEIGHT = 300

    fun samplePoints(bounds: Rectangle): List<Point> {
        if (bounds.width < MIN_WIDTH || bounds.height < MIN_HEIGHT) return emptyList()
        return (0 until GRID_ROWS).flatMap { row ->
            (0 until GRID_COLUMNS).map { column ->
                val x = bounds.x + ((column + 0.5) * bounds.width / GRID_COLUMNS).toInt()
                val y = bounds.y + ((row + 0.5) * bounds.height / GRID_ROWS).toInt()
                Point(x, y)
            }
        }
    }

    fun failureReason(evidence: ScreenRecoveryCaptureEvidence): String? {
        val target = evidence.target ?: return "target-window-missing"
        if (!evidence.processAlive) return "game-process-not-alive"
        if (evidence.currentGameProcessId != target.processId.toLong()) return "target-pid-not-current-game"
        if (!evidence.targetWindowVisibleBefore || !evidence.targetWindowVisibleAfter) {
            return "target-window-not-visible-before-and-after"
        }
        if (!evidence.currentSessionReady &&
            !evidence.preSessionQueueModalAuthorized &&
            !evidence.preSessionMatchmakingAuthorized &&
            !evidence.preSessionStaleClientProbeAuthorized &&
            !evidence.preSessionIdleClientProbeAuthorized &&
            !evidence.preSessionStartupClientProbeAuthorized &&
            !evidence.preSessionYoggProbeAuthorized
        ) {
            return "current-game-session-not-ready"
        }
        if (!evidence.currentSessionReady &&
            evidence.purpose != ScreenRecoveryCapturePurpose.MATCHMAKING_ERROR_DIALOG &&
            !evidence.purpose.equals(ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE) &&
            !evidence.purpose.equals(ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE) &&
            !evidence.purpose.equals(ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE) &&
            !evidence.purpose.equals(ScreenRecoveryCapturePurpose.STARTUP_YOGG_PROBE) &&
            !evidence.preSessionMatchmakingAuthorized
        ) return "current-game-session-not-ready"
        if (target.rootWindow == 0L || target.processId <= 0) return "target-window-identity-invalid"
        if (evidence.foregroundBefore != target || evidence.foregroundAfter != target) {
            return "foreground-window-not-exact-target-before-and-after"
        }
        val client = evidence.clientBounds ?: return "client-bounds-unavailable"
        val capture = evidence.captureBounds ?: return "capture-bounds-unavailable"
        if (client.width < MIN_WIDTH || client.height < MIN_HEIGHT) return "client-bounds-too-small"
        if (capture != client) return "capture-bounds-do-not-match-current-client"
        if (evidence.imageWidth != client.width || evidence.imageHeight != client.height) {
            return "captured-image-dimensions-do-not-match-client"
        }
        val expectedSamples = GRID_COLUMNS * GRID_ROWS
        if (evidence.visibleOwnersBefore.size != expectedSamples ||
            evidence.visibleOwnersAfter.size != expectedSamples
        ) return "visible-owner-samples-incomplete"

        val centerIndex = (GRID_ROWS / 2) * GRID_COLUMNS + GRID_COLUMNS / 2
        if (evidence.visibleOwnersBefore[centerIndex] != target ||
            evidence.visibleOwnersAfter[centerIndex] != target
        ) return "client-center-not-owned-by-target-window"

        val beforeRatio = targetRatio(evidence.visibleOwnersBefore, target)
        val afterRatio = targetRatio(evidence.visibleOwnersAfter, target)
        if (beforeRatio < MIN_TARGET_VISIBLE_RATIO) return "target-window-not-visible-before-capture"
        if (afterRatio < MIN_TARGET_VISIBLE_RATIO) return "target-window-not-visible-after-capture"
        return null
    }

    fun isAuthorized(evidence: ScreenRecoveryCaptureEvidence): Boolean = failureReason(evidence) == null

    fun targetRatio(owners: List<CapturedWindowIdentity?>, target: CapturedWindowIdentity): Double =
        if (owners.isEmpty()) 0.0 else owners.count { it == target }.toDouble() / owners.size
}
