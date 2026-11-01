package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.awt.Rectangle

/** Allows only the visible Continue navigation on a fresh, authorized award frame. */
internal object SeasonRankAwardRecoveryPolicy {
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
}
