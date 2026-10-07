package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.awt.Rectangle

internal data class CapturedWindowIdentity(
    val rootWindow: Long,
    val processId: Int,
)

/** Capture purposes distinguish read-only pre-session classification from modal handling. */
internal enum class ScreenRecoveryCapturePurpose {
    SCREEN_STATE_RECOVERY,
    MATCHMAKING_ERROR_DIALOG,
}

/** Purpose-specific policy for captures made before the current Power.log is ready. */
internal object ScreenRecoveryCapturePurposePolicy {
    fun allowsPreSessionScreenClassification(
        purpose: ScreenRecoveryCapturePurpose,
        tournamentMode: Boolean,
        activeGame: Boolean,
        mulligan: Boolean,
        terminal: Boolean,
    ): Boolean = purpose == ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY &&
        tournamentMode && !activeGame && !mulligan && !terminal

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
}

/** Pre-session visual evidence can classify a screen but cannot mutate recovery state or dispatch input. */
internal object ScreenRecoverySessionPolicy {
    fun mayApplyRecoveryInput(currentSessionReady: Boolean): Boolean = currentSessionReady
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
    val preSessionUiClassificationAuthorized: Boolean = false,
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
        if (!evidence.currentSessionReady) {
            val authorizedForPurpose = when (evidence.purpose) {
                ScreenRecoveryCapturePurpose.MATCHMAKING_ERROR_DIALOG -> evidence.preSessionQueueModalAuthorized
                ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY -> evidence.preSessionUiClassificationAuthorized
            }
            if (!authorizedForPurpose) return "current-game-session-not-ready"
        }
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
