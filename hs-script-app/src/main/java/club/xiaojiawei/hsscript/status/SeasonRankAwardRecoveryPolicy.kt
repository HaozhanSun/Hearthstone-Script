package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.awt.Rectangle

/** Allows only the visible Continue navigation on a fresh, authorized award frame. */
internal object SeasonRankAwardRecoveryPolicy {
    const val DISPATCH_ACTION = "recovery.season-rank-award.continue"
    const val OPEN_CHEST_ACTION = "recovery.season-rank-award.open-chest"
    const val REWARDS_CONTINUE_ACTION = "recovery.season-rank-award.rewards-continue"

    /** Current, final-edge facts for the only mouse event the award recovery may send. */
    data class ContinueDispatchContext(
        val action: String,
        val scopedToExactAwardContinue: Boolean,
        val expectedProcessId: Int,
        val currentProcessId: Int?,
        val windowVerified: Boolean,
        val mode: String?,
        val currentGameSessionReady: Boolean,
        val activeGame: Boolean,
        val currentModeGameplay: Boolean,
        val nextModeGameplay: Boolean,
        val terminalOrResult: Boolean,
        val activeQueueScreen: Boolean,
        val activeQueueEntryGuard: Boolean,
        val rankSurrenderPending: Boolean,
        val terminalCleanupPending: Boolean,
        val working: Boolean,
        val paused: Boolean,
        val windowValid: Boolean,
    )

    /** Explains the final revalidation decision without weakening any dispatch fence. */
    fun continueDispatchBlockReason(context: ContinueDispatchContext): String? = when {
        context.action != DISPATCH_ACTION -> "wrong-action"
        !context.scopedToExactAwardContinue -> "exact-award-continue-scope-missing"
        context.expectedProcessId <= 0 || context.currentProcessId != context.expectedProcessId -> "process-changed"
        !context.windowValid -> "window-invalid"
        !context.windowVerified -> "window-owner-mismatch"
        !allowsMenuContext(context.mode, context.currentGameSessionReady) -> "unsafe-menu-or-current-session"
        context.activeGame -> "active-game"
        context.currentModeGameplay -> "current-mode-gameplay"
        context.nextModeGameplay -> "next-mode-gameplay"
        context.terminalOrResult -> "terminal-or-result"
        context.activeQueueScreen -> "active-matchmaking-screen"
        context.activeQueueEntryGuard -> "active-matchmaking-entry-guard"
        context.rankSurrenderPending -> "rank-surrender-pending"
        context.terminalCleanupPending -> "terminal-cleanup-pending"
        !context.working -> "not-working"
        context.paused -> "paused"
        else -> null
    }

    fun allowsDedicatedStartupRecovery(
        screenKind: String?,
        detectionEvidence: String?,
        visualAwardVerified: Boolean,
        purpose: ScreenRecoveryCapturePurpose,
        captureForegroundAuthorized: Boolean,
        processWindowVerified: Boolean,
        mode: String?,
        currentGameSessionReady: Boolean,
        activeGame: Boolean,
        activeQueue: Boolean,
        terminalOrResult: Boolean,
        working: Boolean,
        paused: Boolean,
        stateStillCurrent: Boolean,
    ): Boolean = screenKind == "SEASON_RANK_AWARD" &&
        detectionEvidence == "season-rank-award-structural-visual" && visualAwardVerified &&
        purpose in setOf(
            ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
            ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
        ) && captureForegroundAuthorized && processWindowVerified &&
        allowsMenuContext(mode, currentGameSessionReady) && !activeGame && !activeQueue &&
        !terminalOrResult && working && !paused && stateStillCurrent

    /** Same narrow startup authority for the chest stage; generic readiness never stands in for this permit. */
    fun allowsDedicatedChestOpenStartupRecovery(
        screenKind: String?,
        detectionEvidence: String?,
        visualChestVerified: Boolean,
        purpose: ScreenRecoveryCapturePurpose,
        captureForegroundAuthorized: Boolean,
        processWindowVerified: Boolean,
        mode: String?,
        currentGameSessionReady: Boolean,
        activeGame: Boolean,
        activeQueue: Boolean,
        terminalOrResult: Boolean,
        working: Boolean,
        paused: Boolean,
        stateStillCurrent: Boolean,
    ): Boolean = screenKind == "SEASON_RANK_AWARD_CHEST" &&
        detectionEvidence == "season-rank-award-chest-structural-visual" && visualChestVerified &&
        purpose in setOf(
            ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
            ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
        ) && captureForegroundAuthorized && processWindowVerified &&
        allowsMenuContext(mode, currentGameSessionReady) && !activeGame && !activeQueue &&
        !terminalOrResult && working && !paused && stateStillCurrent

    /** The opened-reward presentation has its own exact Continue button and startup authority. */
    fun allowsDedicatedRewardsContinueStartupRecovery(
        screenKind: String?,
        detectionEvidence: String?,
        visualRewardsVerified: Boolean,
        purpose: ScreenRecoveryCapturePurpose,
        captureForegroundAuthorized: Boolean,
        processWindowVerified: Boolean,
        mode: String?,
        currentGameSessionReady: Boolean,
        activeGame: Boolean,
        activeQueue: Boolean,
        terminalOrResult: Boolean,
        working: Boolean,
        paused: Boolean,
        stateStillCurrent: Boolean,
    ): Boolean = screenKind == "SEASON_RANK_AWARD_REWARDS" &&
        detectionEvidence == "season-rank-award-rewards-five-chests-and-continue-structural-visual" &&
        visualRewardsVerified && purpose in setOf(
            ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
            ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
        ) && captureForegroundAuthorized && processWindowVerified &&
        allowsMenuContext(mode, currentGameSessionReady) && !activeGame && !activeQueue &&
        !terminalOrResult && working && !paused && stateStillCurrent

    data class CallsiteDispatchResult(
        val attempted: Boolean,
        val accepted: Boolean,
        val point: Point?,
        val reason: String,
    )

    fun allowsMenuContext(mode: String?, currentGameSessionReady: Boolean): Boolean =
        mode in setOf("STARTUP", "HUB", "TOURNAMENT") && !currentGameSessionReady

    /** Center of the fixture's visible Continue control, in client-window coordinates. */
    fun continueTarget(windowBounds: Rectangle): Point = Point(
        windowBounds.x + (windowBounds.width * .50).toInt(),
        windowBounds.y + (windowBounds.height * .82).toInt(),
    )

    /** Center of the cyan chest on the separately classified second-stage frame. */
    fun chestOpenTarget(windowBounds: Rectangle): Point = Point(
        windowBounds.x + (windowBounds.width * .50).toInt(),
        windowBounds.y + (windowBounds.height * .54).toInt(),
    )

    /** Center of the enabled Continue control on the five-chest reward presentation. */
    fun rewardsContinueTarget(windowBounds: Rectangle): Point = continueTarget(windowBounds)

    fun chestOpenDispatchBlockReason(context: ContinueDispatchContext): String? = when (
        continueDispatchBlockReason(context.copy(action = DISPATCH_ACTION))
    ) {
        "wrong-action" -> "wrong-action"
        "exact-award-continue-scope-missing" -> "exact-award-chest-open-scope-missing"
        null -> null
        else -> continueDispatchBlockReason(context.copy(action = DISPATCH_ACTION))
    }

    fun rewardsContinueDispatchBlockReason(context: ContinueDispatchContext): String? = when {
        context.action != REWARDS_CONTINUE_ACTION -> "wrong-action"
        !context.scopedToExactAwardContinue -> "exact-award-rewards-continue-scope-missing"
        context.expectedProcessId <= 0 || context.currentProcessId != context.expectedProcessId -> "process-changed"
        !context.windowValid -> "window-invalid"
        !context.windowVerified -> "window-owner-mismatch"
        !allowsMenuContext(context.mode, context.currentGameSessionReady) -> "unsafe-menu-or-current-session"
        context.activeGame -> "active-game"
        context.currentModeGameplay -> "current-mode-gameplay"
        context.nextModeGameplay -> "next-mode-gameplay"
        context.terminalOrResult -> "terminal-or-result"
        context.activeQueueScreen -> "active-matchmaking-screen"
        context.activeQueueEntryGuard -> "active-matchmaking-entry-guard"
        context.rankSurrenderPending -> "rank-surrender-pending"
        context.terminalCleanupPending -> "terminal-cleanup-pending"
        !context.working -> "not-working"
        context.paused -> "paused"
        else -> null
    }

    fun allowsContinue(
        exactSeasonRankAward: Boolean,
        freshCaptureAuthorized: Boolean,
        foregroundVerified: Boolean,
        safeMenuContext: Boolean,
        activeGame: Boolean,
        activeQueue: Boolean,
        terminalOrResult: Boolean,
        working: Boolean,
        paused: Boolean,
    ): Boolean = exactSeasonRankAward && freshCaptureAuthorized && foregroundVerified && safeMenuContext &&
        !activeGame && !activeQueue && !terminalOrResult && working && !paused

    fun dispatchContinue(
        windowBounds: Rectangle,
        exactSeasonRankAward: Boolean,
        freshCaptureAuthorized: Boolean,
        foregroundVerified: Boolean,
        safeMenuContext: Boolean,
        activeGame: Boolean,
        activeQueue: Boolean,
        terminalOrResult: Boolean,
        working: Boolean,
        paused: Boolean,
        click: (Point) -> Boolean,
    ): Boolean {
        if (!allowsContinue(
                exactSeasonRankAward, freshCaptureAuthorized, foregroundVerified, safeMenuContext,
                activeGame, activeQueue, terminalOrResult, working, paused,
            )
        ) return false
        return click(continueTarget(windowBounds))
    }

    /** Pure adapter used by the UpstreamScreenStateRecovery action branch and its fixture regression. */
    fun dispatchFromUpstreamRecoveryCallsite(
        screenKind: String,
        detectionEvidence: String,
        windowBounds: Rectangle,
        captureAuthorityValid: Boolean,
        foregroundVerified: Boolean,
        mode: String?,
        currentGameSessionReady: Boolean,
        activeGame: Boolean,
        activeQueue: Boolean,
        terminalOrResult: Boolean,
        working: Boolean,
        paused: Boolean,
        click: (Point) -> Boolean,
    ): CallsiteDispatchResult {
        if (screenKind != "SEASON_RANK_AWARD" ||
            detectionEvidence != "season-rank-award-structural-visual"
        ) return CallsiteDispatchResult(false, false, null, "screen-identity-unverified")
        if (!captureAuthorityValid) return CallsiteDispatchResult(false, false, null, "capture-authority-invalid")
        if (!foregroundVerified) return CallsiteDispatchResult(false, false, null, "foreground-unverified")
        if (!allowsMenuContext(mode, currentGameSessionReady)) {
            return CallsiteDispatchResult(false, false, null, "unsafe-menu-or-current-session")
        }
        if (activeGame || activeQueue || terminalOrResult) {
            return CallsiteDispatchResult(false, false, null, "game-queue-or-terminal-fence")
        }
        if (!working || paused) return CallsiteDispatchResult(false, false, null, "not-working-or-paused")
        val point = continueTarget(windowBounds)
        return CallsiteDispatchResult(true, click(point), point, "visible-continue-only")
    }

    fun dispatchChestOpenFromUpstreamRecoveryCallsite(
        screenKind: String,
        detectionEvidence: String,
        windowBounds: Rectangle,
        captureAuthorityValid: Boolean,
        foregroundVerified: Boolean,
        mode: String?,
        currentGameSessionReady: Boolean,
        activeGame: Boolean,
        activeQueue: Boolean,
        terminalOrResult: Boolean,
        working: Boolean,
        paused: Boolean,
        click: (Point) -> Boolean,
    ): CallsiteDispatchResult {
        if (screenKind != "SEASON_RANK_AWARD_CHEST" ||
            detectionEvidence != "season-rank-award-chest-structural-visual"
        ) return CallsiteDispatchResult(false, false, null, "chest-screen-identity-unverified")
        if (!captureAuthorityValid) return CallsiteDispatchResult(false, false, null, "capture-authority-invalid")
        if (!foregroundVerified) return CallsiteDispatchResult(false, false, null, "foreground-unverified")
        if (!allowsMenuContext(mode, currentGameSessionReady)) {
            return CallsiteDispatchResult(false, false, null, "unsafe-menu-or-current-session")
        }
        if (activeGame || activeQueue || terminalOrResult) {
            return CallsiteDispatchResult(false, false, null, "game-queue-or-terminal-fence")
        }
        if (!working || paused) return CallsiteDispatchResult(false, false, null, "not-working-or-paused")
        val point = chestOpenTarget(windowBounds)
        return CallsiteDispatchResult(true, click(point), point, "exact-chest-center-only")
    }

    fun dispatchRewardsContinueFromUpstreamRecoveryCallsite(
        screenKind: String,
        detectionEvidence: String,
        visualRewardsVerified: Boolean,
        windowBounds: Rectangle,
        captureAuthorityValid: Boolean,
        foregroundVerified: Boolean,
        mode: String?,
        currentGameSessionReady: Boolean,
        activeGame: Boolean,
        activeQueue: Boolean,
        terminalOrResult: Boolean,
        working: Boolean,
        paused: Boolean,
        click: (Point) -> Boolean,
    ): CallsiteDispatchResult {
        if (screenKind != "SEASON_RANK_AWARD_REWARDS" ||
            detectionEvidence != "season-rank-award-rewards-five-chests-and-continue-structural-visual" ||
            !visualRewardsVerified
        ) return CallsiteDispatchResult(false, false, null, "reward-screen-identity-unverified")
        if (!captureAuthorityValid) return CallsiteDispatchResult(false, false, null, "capture-authority-invalid")
        if (!foregroundVerified) return CallsiteDispatchResult(false, false, null, "foreground-unverified")
        if (!allowsMenuContext(mode, currentGameSessionReady)) {
            return CallsiteDispatchResult(false, false, null, "unsafe-menu-or-current-session")
        }
        if (activeGame || activeQueue || terminalOrResult) {
            return CallsiteDispatchResult(false, false, null, "game-queue-or-terminal-fence")
        }
        if (!working || paused) return CallsiteDispatchResult(false, false, null, "not-working-or-paused")
        val point = rewardsContinueTarget(windowBounds)
        return CallsiteDispatchResult(true, click(point), point, "exact-reward-continue-only")
    }
}
