package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.consts.GAME_WAR_LOG_NAME
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot

internal data class AuthorizedGameWindowFrame(
    val image: java.awt.image.BufferedImage,
    val bounds: Rectangle,
    val evidence: ScreenRecoveryCaptureEvidence,
)

/** Captures only the current game's client rectangle and rejects foreign pixels/occlusion. */
internal object ScreenRecoveryWindowCapture {
    private const val GA_ROOT = WinUser.GA_ROOT

    private interface CaptureUser32 : StdCallLibrary {
        fun ClientToScreen(hwnd: WinDef.HWND?, point: WinDef.POINT): Boolean
        fun WindowFromPoint(point: WinDef.POINT.ByValue): WinDef.HWND?

        companion object {
            val INSTANCE: CaptureUser32 by lazy {
                Native.load("user32", CaptureUser32::class.java)
            }
        }
    }

    fun capture(
        hwnd: WinDef.HWND?,
        purpose: ScreenRecoveryCapturePurpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY,
    ): AuthorizedGameWindowFrame? {
        if (GraphicsEnvironment.isHeadless()) return reject("headless-environment", hwnd)
        if (hwnd == null || !User32.INSTANCE.IsWindow(hwnd) || !User32.INSTANCE.IsWindowVisible(hwnd)) {
            return reject("game-window-missing-or-hidden", hwnd)
        }
        val targetWindowVisibleBefore = User32.INSTANCE.IsWindowVisible(hwnd)
        if (!GameUtil.isAliveOfGame() || !GameUtil.isVerifiedCurrentGameWindow(hwnd)) {
            return reject("current-game-window-unverified", hwnd)
        }

        val target = identity(hwnd) ?: return reject("target-identity-unavailable", hwnd)
        val currentPidBefore = GameUtil.findGameProcessIdForDiagnostics()
        if (currentPidBefore != target.processId.toLong()) {
            return reject("target-pid-not-current-game", hwnd, "targetPid=${target.processId} currentPid=$currentPidBefore")
        }
        val attachedPowerLog = PowerLogListener.logFile
        val currentPowerLog = GameUtil.getLatestLogDir()?.resolve(GAME_WAR_LOG_NAME)
        val currentPowerLogReady = CurrentGameScreenReadinessPolicy.isReady(
            gameWindowVerified = GameUtil.isVerifiedCurrentGameWindow(hwnd),
            attachedPowerLogPath = attachedPowerLog?.path(),
            currentSessionPowerLogPath = currentPowerLog?.absolutePath,
            powerLogLength = attachedPowerLog?.length() ?: 0L,
        )
        val preSessionQueueModalBefore = preSessionQueueModalContext(purpose)
        val preSessionUiClassificationBefore = preSessionUiClassificationContext(purpose)
        if (!currentPowerLogReady && !preSessionQueueModalBefore && !preSessionUiClassificationBefore) {
            return reject(
                "current-game-session-not-ready",
                hwnd,
                "powerLog=${attachedPowerLog?.path() ?: "none"} " +
                    "currentPowerLog=${currentPowerLog?.absolutePath ?: "none"} " +
                    "powerLogLength=${attachedPowerLog?.length() ?: 0L}",
            )
        }
        if (!currentPowerLogReady) {
            log.info {
                "SCREEN_RECOVERY_CAPTURE_PRESESSION purpose=${purpose.name} " +
                    "authority=current-pid-window-foreground-pixel-ownership " +
                    "powerLog=${attachedPowerLog?.path() ?: "none"} " +
                    "currentPowerLog=${currentPowerLog?.absolutePath ?: "none"} " +
                    "powerLogLength=${attachedPowerLog?.length() ?: 0L}"
            }
        }
        val clientBounds = clientBoundsOnScreen(hwnd)
            ?: return reject("native-client-bounds-unavailable", hwnd)
        val deviceBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .screenDevices.map { it.defaultConfiguration.bounds }
        if (deviceBounds.none { it.contains(clientBounds) }) {
            return reject("client-bounds-not-contained-by-one-display", hwnd, "clientBounds=$clientBounds")
        }

        val foregroundBefore = identity(User32.INSTANCE.GetForegroundWindow())
        val ownersBefore = sampleVisibleOwners(clientBounds)
        val image = runCatching { Robot().createScreenCapture(clientBounds) }.getOrElse { error ->
            log.warn(error) {
                "SCREEN_RECOVERY_CAPTURE_REJECTED reason=robot-capture-failed hwnd=$hwnd " +
                    "clientBounds=$clientBounds"
            }
            return null
        }
        val ownersAfter = sampleVisibleOwners(clientBounds)
        val foregroundAfter = identity(User32.INSTANCE.GetForegroundWindow())
        val currentPidAfter = GameUtil.findGameProcessIdForDiagnostics()
        val processStillAlive = GameUtil.isAliveOfGame() && currentPidAfter == target.processId.toLong()
        val targetWindowVisibleAfter = User32.INSTANCE.IsWindowVisible(hwnd)
        val preSessionQueueModalAfter = preSessionQueueModalContext(purpose)
        val evidence = ScreenRecoveryCaptureEvidence(
            processAlive = processStillAlive,
            currentSessionReady = currentPowerLogReady,
            target = target,
            foregroundBefore = foregroundBefore,
            foregroundAfter = foregroundAfter,
            clientBounds = clientBounds,
            captureBounds = clientBounds,
            imageWidth = image.width,
            imageHeight = image.height,
            visibleOwnersBefore = ownersBefore,
            visibleOwnersAfter = ownersAfter,
            purpose = purpose,
            preSessionQueueModalAuthorized = preSessionQueueModalBefore && preSessionQueueModalAfter,
            preSessionUiClassificationAuthorized = preSessionUiClassificationBefore &&
                preSessionUiClassificationContext(purpose),
            currentGameProcessId = currentPidAfter,
            targetWindowVisibleBefore = targetWindowVisibleBefore,
            targetWindowVisibleAfter = targetWindowVisibleAfter,
        )
        val reason = ScreenRecoveryCaptureAuthority.failureReason(evidence)
        log.info {
            "GAME_WINDOW_PIXEL_AUTHORITY hwnd=$hwnd targetPid=${target.processId} " +
                "clientBounds=$clientBounds source=robot-client-window image=${image.width}x${image.height} " +
                "foregroundBefore=${foregroundBefore?.rootWindow ?: "none"} " +
                "foregroundAfter=${foregroundAfter?.rootWindow ?: "none"} " +
                "targetCoverageBefore=${coverage(ownersBefore, target)} " +
                "targetCoverageAfter=${coverage(ownersAfter, target)} " +
                "purpose=${purpose.name} accepted=${reason == null} " +
                "reason=${reason ?: "exact-client-visible"}"
        }
        if (reason != null) {
            log.warn {
                "SCREEN_RECOVERY_CAPTURE_REJECTED hwnd=$hwnd reason=$reason " +
                    "targetPid=${target.processId} clientBounds=$clientBounds"
            }
            return null
        }
        return AuthorizedGameWindowFrame(image, clientBounds, evidence)
    }

    private fun preSessionQueueModalContext(purpose: ScreenRecoveryCapturePurpose): Boolean =
        ScreenRecoveryCapturePurposePolicy.allowsPreSessionQueueModal(
            purpose = purpose,
            tournamentMode = Mode.currMode === ModeEnum.TOURNAMENT,
            activeGame = WarEx.inWar,
            mulligan = WarEx.war.currentPhase == WarPhaseEnum.REPLACE_CARD,
            terminal = GameUtil.isTerminalGameState(),
        )

    private fun preSessionUiClassificationContext(purpose: ScreenRecoveryCapturePurpose): Boolean =
        ScreenRecoveryCapturePurposePolicy.allowsPreSessionScreenClassification(
            purpose = purpose,
            tournamentMode = Mode.currMode === ModeEnum.TOURNAMENT,
            activeGame = WarEx.inWar,
            mulligan = WarEx.war.currentPhase == WarPhaseEnum.REPLACE_CARD,
            terminal = GameUtil.isTerminalGameState(),
        )

    private fun clientBoundsOnScreen(hwnd: WinDef.HWND): Rectangle? = runCatching {
        val client = WinDef.RECT()
        if (!User32.INSTANCE.GetClientRect(hwnd, client)) return null
        val topLeft = WinDef.POINT().apply { x = client.left; y = client.top }
        val bottomRight = WinDef.POINT().apply { x = client.right; y = client.bottom }
        if (!CaptureUser32.INSTANCE.ClientToScreen(hwnd, topLeft) ||
            !CaptureUser32.INSTANCE.ClientToScreen(hwnd, bottomRight)
        ) return null
        val width = bottomRight.x - topLeft.x
        val height = bottomRight.y - topLeft.y
        if (width <= 0 || height <= 0) return null
        Rectangle(topLeft.x, topLeft.y, width, height)
    }.getOrNull()

    private fun sampleVisibleOwners(bounds: Rectangle): List<CapturedWindowIdentity?> =
        ScreenRecoveryCaptureAuthority.samplePoints(bounds).map { point ->
            val hit = runCatching {
                CaptureUser32.INSTANCE.WindowFromPoint(WinDef.POINT.ByValue(point.x, point.y))
            }.getOrNull()
            identity(hit)
        }

    private fun identity(hwnd: WinDef.HWND?): CapturedWindowIdentity? = runCatching {
        if (hwnd == null || !User32.INSTANCE.IsWindow(hwnd)) return null
        val root = User32.INSTANCE.GetAncestor(hwnd, GA_ROOT) ?: hwnd
        val pid = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(root, pid)
        CapturedWindowIdentity(Pointer.nativeValue(root.pointer), pid.value)
    }.getOrNull()

    private fun coverage(owners: List<CapturedWindowIdentity?>, target: CapturedWindowIdentity): String =
        "%.2f".format(ScreenRecoveryCaptureAuthority.targetRatio(owners, target))

    private fun reject(reason: String, hwnd: WinDef.HWND?, detail: String = ""): Nothing? {
        log.warn {
            "SCREEN_RECOVERY_CAPTURE_REJECTED hwnd=${hwnd ?: "none"} reason=$reason " +
                detail.takeIf(String::isNotBlank).orEmpty()
        }
        return null
    }
}
