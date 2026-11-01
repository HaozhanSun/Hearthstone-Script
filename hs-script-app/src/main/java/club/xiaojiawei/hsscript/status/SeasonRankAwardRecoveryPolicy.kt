package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.awt.Rectangle

/** Allows only the visible Continue navigation on a fresh, authorized award frame. */
internal object SeasonRankAwardRecoveryPolicy {
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
}
