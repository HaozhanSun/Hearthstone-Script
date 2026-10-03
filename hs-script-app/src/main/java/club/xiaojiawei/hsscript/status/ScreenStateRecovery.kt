package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.consts.CHI_SIM_DATA
import club.xiaojiawei.hsscript.consts.TESS_DATA_PATH
import club.xiaojiawei.hsscript.core.Core
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.ocr.OcrRecognition
import club.xiaojiawei.hsscript.strategy.mode.HubModeStrategy
import club.xiaojiawei.hsscript.strategy.mode.LoginModeStrategy
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingDialogRecoveryPolicy
import club.xiaojiawei.hsscript.strategy.mode.StartGameErrorDialogClassifier
import club.xiaojiawei.hsscript.strategy.mode.TournamentModeStrategy
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.MouseUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import net.sourceforge.tess4j.Tesseract

/**
 * Visual fallback for a stale LoadingScreen state.
 *
 * LoadingScreen.log is append-only, so its last line cannot prove that the
 * visible Hearthstone client is still on that screen. This object is only
 * called after LifecycleTrace has observed an unchanged, non-gameplay state
 * for 30 seconds. It captures the current client, optionally runs OCR when
 * the installed tessdata is available, and applies only high-confidence
 * screen mappings.
 */
object ScreenStateRecovery {

    enum class InspectionResult {
        DISABLED,
        APPLIED,
        NO_ACTION,
        DEFERRED_GAME_FOREGROUND,
    }

    internal data class FreshScreenObservation(
        val screen: String,
        val confidence: Int,
        val pid: Long,
        val hwnd: String,
        val screenshot: String?,
    )

    private const val MAX_OCR_TEXT_LENGTH = 500
    private const val OCR_MAX_WIDTH = 1280
    private const val RESULT_CONTINUE_GRAY_LIGHT_MIN = 0.025
    private const val RESULT_BANNER_LOW_SATURATION_MIN = 0.30
    private const val MATCHMAKING_DIALOG_OCR_TIMEOUT_MS = 4_000L
    private const val RECONNECT_RETRY_INTERVAL_MS = 60_000L
    private const val RECOVERY_POSTCHECK_TIMEOUT_MS = 3_000L
    private const val RECOVERY_POSTCHECK_POLL_MS = 300L
    private const val STARTUP_QUEST_OVERLAY_MAX_PROBES = 5
    private const val RECONNECT_SPINNER_CHECK_DELAY_MS = 10_000L
    /**
     * The client displays a distinct slow-connection warning only after a
     * reconnect attempt. A fresh script can attach after that click, so its
     * first observation of the same exact warning also starts this timer.
     * Give it two minutes before using the established client restart path.
     */
    private const val STALLED_RECONNECT_LOADING_RESTART_MS = 120_000L
    private val reconnectAttemptAt = AtomicLong(0L)
    private val reconnectAcceptedAt = AtomicLong(0L)
    private val reconnectProbeGeneration = AtomicLong(0L)
    private val offlineReconnectRecovery = OfflineReconnectRecovery()
    private val reconnectFailureRecoveryPolicy = ReconnectFailureRecoveryPolicy()
    private val slowReconnectWarningObservedAt = AtomicLong(0L)
    /**
     * A visual loading signal can be the offline/reconnect dialog even when
     * OCR misses the dialog text.  Keep an independent anchor for that generic
     * branch so a stale client cannot wait forever on WAIT_FOR_CLIENT.
     */
    private val loadingObservedAt = AtomicLong(0L)

    private enum class ScreenKind(val code: String) {
        DECK_SELECTION("DECK_SELECTION"),
        HOME("HOME"),
        HOME_TASK_OVERLAY("HOME_TASK_OVERLAY"),
        TOURNAMENT("TOURNAMENT"),
        MATCHMAKING("MATCHMAKING"),
        RESULT("RESULT"),
        RECONNECT("RECONNECT"),
        RECONNECT_FAILURE("RECONNECT_FAILURE"),
        RECONNECT_SPINNER("RECONNECT_SPINNER"),
        LOGIN("LOGIN"),
        GAME_MODE("GAME_MODE"),
        COLLECTION("COLLECTION"),
        PACK_OPENING("PACK_OPENING"),
        SHOP_OVERLAY("SHOP_OVERLAY"),
        LOADING("LOADING"),
    }

    private val POST_RESULT_DESTINATIONS = setOf(
        ScreenKind.DECK_SELECTION,
        ScreenKind.HOME,
        ScreenKind.HOME_TASK_OVERLAY,
        ScreenKind.TOURNAMENT,
        ScreenKind.MATCHMAKING,
        ScreenKind.LOGIN,
        ScreenKind.GAME_MODE,
        ScreenKind.COLLECTION,
        ScreenKind.PACK_OPENING,
        ScreenKind.SHOP_OVERLAY,
    )

    private data class Capture(
        val image: BufferedImage,
        val bounds: Rectangle,
        val file: File?,
        val visual: VisualSignature,
        val gameRectKnown: Boolean,
        val gameWindowKnown: Boolean,
    )

    private data class OcrEvidence(
        val text: String,
        val targeted: Map<String, String>,
    )

    private data class VisualSignature(
        val sampleHash: Long,
        val warmRatio: Double,
        val blueRatio: Double,
        val loadingCentralDarkRatio: Double,
        val resultContinueGrayLightRatio: Double,
        val resultBannerLowSaturationRatio: Double,
        val reconnectFailureDialogVisual: Boolean = false,
    ) {
        override fun toString(): String =
            "hash=${java.lang.Long.toUnsignedString(sampleHash, 16)} " +
                "warmRatio=${"%.3f".format(Locale.ROOT, warmRatio)} " +
                "blueRatio=${"%.3f".format(Locale.ROOT, blueRatio)} " +
                "loadingCentralDark=${"%.3f".format(Locale.ROOT, loadingCentralDarkRatio)} " +
                "resultContinueGrayLight=${"%.3f".format(Locale.ROOT, resultContinueGrayLightRatio)} " +
                "resultBannerLowSaturation=${"%.3f".format(Locale.ROOT, resultBannerLowSaturationRatio)} " +
                "reconnectFailureDialog=$reconnectFailureDialogVisual"
    }

    private data class RegionSignal(
        val grayLightRatio: Double,
        val lowSaturationRatio: Double,
        val darkRatio: Double,
        val warmRatio: Double,
    )

    private data class Detection(
        val kind: ScreenKind,
        val mode: ModeEnum,
        val confidence: Int,
        val evidence: String,
    )

    internal data class StartGameErrorDialogProbe(
        val state: MatchmakingDialogRecoveryPolicy.Probe,
        val title: String = "",
        val body: String = "",
        val confirm: String = "",
        val screenshot: String? = null,
        val provider: String = "UNKNOWN",
        val reason: String,
    )

    internal data class RecoveryTransitionForTest(
        val screen: String,
        val mode: ModeEnum,
        val enterStrategy: Boolean,
        val action: String,
    )

    /**
     * Inspect the visible client and, if possible, move the state machine to
     * the detected screen. The result keeps a foreground deferral separate
     * from an OCR/capture failure so LifecycleTrace can back off safely.
     */
    fun inspectAndRecover(
        stuckForMs: Long,
        stateFingerprint: String,
        startupProbe: Boolean = false,
        stateStillCurrent: () -> Boolean = { true },
    ): Boolean {
        if (!ScreenRecoveryRuntime.isEnabled()) {
            return UpstreamScreenStateRecovery.inspectAndRecover(
                stuckForMs = stuckForMs,
                stateFingerprint = stateFingerprint,
                startupProbe = startupProbe,
                stateStillCurrent = stateStillCurrent,
            )
        }
        return inspectBetaAndRecover(stuckForMs, stateFingerprint, startupProbe, stateStillCurrent) ==
            InspectionResult.APPLIED
    }

    /** Read-only, fresh screenshot observation for the Beta no-progress guard. */
    internal fun observeFreshScreenForWatchdog(): FreshScreenObservation? {
        val recoveryToken = ScreenRecoveryRuntime.tokenOrNull() ?: return null
        val initialPid = GameUtil.findGameProcessIdForDiagnostics() ?: return null
        val gameWindow = resolveLiveGameWindow() ?: return null
        val captureResult = MouseUtil.withRecoveryForeground(gameWindow) {
            val currentWindow = resolveLiveGameWindow()
            val currentPid = GameUtil.findGameProcessIdForDiagnostics()
            if (currentWindow == null || currentWindow.toString() != gameWindow.toString() ||
                currentPid != initialPid
            ) {
                null
            } else {
                captureScreen(currentWindow, allowCachedGameRect = false)
            }
        }
        if (!ScreenRecoveryRuntime.isCurrent(recoveryToken) || !captureResult.foregroundConfirmed) return null
        val capture = captureResult.value ?: return null
        if (GameUtil.findGameProcessIdForDiagnostics() != initialPid ||
            !GameUtil.isVerifiedCurrentGameWindow(gameWindow)
        ) return null
        val detection = detect(runOCR(capture), capture.visual)
            ?.takeIf { it.confidence >= 85 }
            ?: return null
        val observation = FreshScreenObservation(
            screen = detection.kind.code,
            confidence = detection.confidence,
            pid = initialPid,
            hwnd = gameWindow.toString(),
            screenshot = capture.file?.absolutePath,
        )
        log.info {
            "SCREEN_RECOVERY_FRESH_OBSERVATION detected=${observation.screen} " +
                "confidence=${observation.confidence} pid=${observation.pid} hwnd=${observation.hwnd} " +
                "screenshot=${observation.screenshot ?: "not-saved"}"
        }
        return observation
    }

    /** Optional Beta-specific recovery pipeline; never used for baseline OFF behavior. */
    internal fun inspectBetaAndRecover(
        stuckForMs: Long,
        stateFingerprint: String,
        startupProbe: Boolean = false,
        stateStillCurrent: () -> Boolean = { true },
    ): InspectionResult {
        val recoveryToken = ScreenRecoveryRuntime.tokenOrNull()
            ?: return InspectionResult.DISABLED
        if ((!WorkTimeListener.working && !PauseStatus.isAutomaticPause) ||
            !PauseStatus.canRunAutomaticRecovery() ||
            WarEx.inWar
        ) {
            log.info {
                "SCREEN_RECOVERY_SKIPPED reason=unsafe " +
                    "working=${WorkTimeListener.working} paused=${PauseStatus.isPause} " +
                    "automaticPause=${PauseStatus.isAutomaticPause} inWar=${WarEx.inWar}"
            }
            return InspectionResult.NO_ACTION
        }

        // Never trust a cached HWND after a game restart.  In particular, the
        // E2E coordinate sentinel and a dead Unity HWND can otherwise make the
        // foreground helper report a retry while Robot captures Codex/Kodi.
        val gameWindow = resolveLiveGameWindow()
        if (gameWindow == null) {
            log.warn {
                "SCREEN_RECOVERY_SKIPPED reason=hearthstone-window-missing " +
                    "processAlive=${GameUtil.isAliveOfGame()} state=$stateFingerprint"
            }
            return InspectionResult.NO_ACTION
        }

        // Foreground confirmation and capture must be one atomic recovery
        // operation. Releasing z-order between those steps was the cause of
        // screenshots containing Kodi/Codex while the log claimed that
        // Hearthstone was foreground.
        val captureResult = if (RuntimeSafety.safeNative) {
            MouseUtil.withRecoveryForeground(gameWindow) {
                // The focus helper can refresh ScriptStatus.gameHWND while
                // replacing the startup coordinate sentinel. Resolve it
                // again inside the foreground lease so capture and focus use
                // the same live window.
                captureScreen(ScriptStatus.gameHWND ?: gameWindow, allowCachedGameRect = false)
            }
        } else {
            MouseUtil.RecoveryForegroundResult(true, captureScreen(gameWindow, allowCachedGameRect = false))
        }
        if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return InspectionResult.DISABLED
        val powerLog = PowerLogListener.logFile
        log.info {
            "SCREEN_RECOVERY_WINDOW_READINESS gameWindow=$gameWindow " +
                "handleKnown=${gameWindow != null} foregroundConfirmed=${captureResult.foregroundConfirmed} " +
                "captureReturned=${captureResult.value != null} " +
                "powerLog=${powerLog?.path() ?: "none"} " +
                "powerLogReadable=${(powerLog?.length() ?: 0L) > 0L}"
        }
        if (!captureResult.foregroundConfirmed) {
            log.warn {
                "SCREEN_RECOVERY_DEFERRED reason=game-foreground-unconfirmed " +
                    "gameWindow=$gameWindow"
            }
            return InspectionResult.DEFERRED_GAME_FOREGROUND
        }
        val capture = captureResult.value
        if (capture == null) {
            log.warn {
                "SCREEN_RECOVERY_FAILED reason=capture-null stuckForMs=$stuckForMs " +
                    "state=$stateFingerprint"
            }
            return InspectionResult.NO_ACTION
        }

        log.warn {
            "SCREEN_RECOVERY_TRIGGER stuckForMs=$stuckForMs state=$stateFingerprint " +
                "bounds=${capture.bounds} screenshot=${capture.file?.absolutePath ?: "not-saved"} " +
                "screenshotLink=${capture.file?.toURI()?.toString() ?: "none"} " +
                "visual=${capture.visual}"
        }

        val ocrEvidence = runOCR(capture)
        if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return InspectionResult.DISABLED
        val ocrText = ocrEvidence.text
        if (!stateStillCurrent()) {
            log.info { "SCREEN_RECOVERY_SKIPPED reason=state-changed-during-inspection state=$stateFingerprint" }
            return InspectionResult.NO_ACTION
        }
        val detection = detect(ocrEvidence, capture.visual)
        if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return InspectionResult.DISABLED
        log.info {
            "SCREEN_RECOVERY_OBSERVATION " +
                "provider=LEGACY " +
                "ocr=${ocrText.ifBlank { "<empty>" }.take(MAX_OCR_TEXT_LENGTH)} " +
                "roiEvidence=${formatRoiEvidence(ocrEvidence.targeted)} " +
                "detected=${detection?.kind?.code ?: "UNKNOWN"} " +
                "confidence=${detection?.confidence ?: 0} " +
                "evidence=${detection?.evidence ?: "none"}"
        }

        // Keep one durable, categorized copy for every 30-second recovery
        // inspection.  The existing DebugScreenshotRing remains the compact
        // 60-file timeline; this copy is the post-mortem evidence and is
        // retained per category/day by UnknownStateScreenshot.
        val evidenceCategory = if (detection == null || detection.confidence < 85) {
            UnknownStateScreenshot.CATEGORY_SCREEN_RECOVERY_UNRESOLVED
        } else {
            UnknownStateScreenshot.CATEGORY_STUCK_STATE
        }
        val roiRegions = ScreenStateRoiSelector
            .select(capture.image.width, capture.image.height)
            .map { roi ->
                val value = ocrEvidence.targeted[roi.name].orEmpty()
                UnknownStateScreenshot.UnknownRegion(
                    roi.bounds,
                    "${roi.name.removePrefix("screen-state-")}=${value.ifBlank { "<empty>" }}",
                )
            }
        val annotationLines = buildList {
            add("screen-recovery detected=${detection?.kind?.code ?: "UNKNOWN"}")
            add("confidence=${detection?.confidence ?: 0} evidence=${detection?.evidence ?: "none"}")
            ocrEvidence.targeted.forEach { (name, value) ->
                add("$name=${value.ifBlank { "<empty>" }}")
            }
        }
        val evidence = UnknownStateScreenshot.save(
            image = capture.image,
            regions = roiRegions.ifEmpty {
                listOf(
                    UnknownStateScreenshot.UnknownRegion(
                        Rectangle(0, 0, capture.image.width, capture.image.height),
                        if (evidenceCategory == UnknownStateScreenshot.CATEGORY_STUCK_STATE) {
                            "stuck-state-observation"
                        } else {
                            "unidentified-screen"
                        },
                    ),
                )
            },
            category = evidenceCategory,
            trigger = "screen-recovery-observation",
            state = stateFingerprint,
            phase = "stuck-screen-recovery",
            ocrText = ocrText,
            visual = capture.visual.toString(),
            annotationLines = annotationLines,
        )
        log.warn {
            "SCREEN_RECOVERY_EVIDENCE category=$evidenceCategory " +
                "path=${evidence?.file?.absolutePath ?: "not-saved"} " +
                "link=${evidence?.link ?: "none"}"
        }

        if (detection == null || detection.confidence < 85) {
            log.warn {
                "SCREEN_RECOVERY_UNRESOLVED confidence=${detection?.confidence ?: 0} " +
                    "screenshot=${capture.file?.absolutePath ?: "not-saved"} " +
                    "screenshotLink=${capture.file?.toURI()?.toString() ?: "none"} " +
                    "unknownStateScreenshot=${evidence?.file?.absolutePath ?: "not-saved"} " +
                    "unknownStateScreenshotLink=${evidence?.link ?: "none"}"
            }
            return InspectionResult.NO_ACTION
        }

        if (!stateStillCurrent()) {
            log.info { "SCREEN_RECOVERY_SKIPPED reason=state-changed-before-apply state=$stateFingerprint" }
            return InspectionResult.NO_ACTION
        }
        val sourcePid = GameUtil.findGameProcessIdForDiagnostics()
        if (!apply(detection, recoveryToken)) return InspectionResult.NO_ACTION
        val verified = confirmRecoveryTransition(detection, recoveryToken, sourcePid)
        log.info {
            "SCREEN_RECOVERY_POSTCHECK screen=${detection.kind.code} " +
                "result=${if (verified) "VERIFIED" else "UNCONFIRMED"} " +
                "mode=${Mode.currMode?.name ?: "NONE"} pid=${GameUtil.findGameProcessIdForDiagnostics() ?: "none"}"
        }
        return if (verified) InspectionResult.APPLIED else InspectionResult.NO_ACTION
    }

    /**
     * Observe only the exact start-game error modal. This probe has no state
     * transition or input side effect; missing capture/OCR evidence is UNKNOWN.
     */
    internal fun probeStartGameErrorDialogForMatchmaking(): StartGameErrorDialogProbe {
        val tessData = File(TESS_DATA_PATH)
        val chiSim = File(tessData, "$CHI_SIM_DATA.traineddata")
        if (!chiSim.isFile) {
            return StartGameErrorDialogProbe(
                MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
                reason = "missing-tessdata",
            )
        }
        val gameWindow = resolveLiveGameWindow()
            ?: return StartGameErrorDialogProbe(
                MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
                reason = "game-window-unavailable",
            )
        val captureResult = if (RuntimeSafety.safeNative) {
            MouseUtil.withRecoveryForeground(gameWindow) {
                captureScreen(ScriptStatus.gameHWND ?: gameWindow, allowCachedGameRect = false)
            }
        } else {
            MouseUtil.RecoveryForegroundResult(true, captureScreen(gameWindow, allowCachedGameRect = false))
        }
        if (!captureResult.foregroundConfirmed) {
            return StartGameErrorDialogProbe(
                MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
                reason = "foreground-unconfirmed",
            )
        }
        val capture = captureResult.value
            ?: return StartGameErrorDialogProbe(
                MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
                reason = "capture-unavailable",
            )

        return runCatching {
            probeStartGameErrorDialogForImage(capture.image, capture.file?.absolutePath) { roiImage, roiName ->
                OcrRuntime.recognizeResult(
                    image = roiImage,
                    desc = "matchmaking-start-game-error-$roiName",
                    roi = roiName,
                    timeoutMs = MATCHMAKING_DIALOG_OCR_TIMEOUT_MS,
                ) {
                    // AUTO uses the selected PaddleX provider first and its established legacy fallback.
                    // The compatibility OCR path remains available for LEGACY_ONLY mode and fallback.
                    ocrScreenRoi(roiImage, tessData, targeted = true)
                }.also { result ->
                    log.info {
                        "MATCHMAKING_ERROR_DIALOG_OCR_RESULT name=$roiName " +
                            "provider=${OcrRuntime.lastProviderUsed()} confidence=${result.confidence ?: "unavailable"} " +
                            "chars=${result.text.length}"
                    }
                }
            }
        }.getOrElse { error ->
            log.warn(error) { "MATCHMAKING_ERROR_DIALOG_OCR_FAILED" }
            StartGameErrorDialogProbe(
                MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
                screenshot = capture.file?.absolutePath,
                provider = OcrRuntime.lastProviderUsed().name,
                reason = "ocr-failed-${error.javaClass.simpleName}",
            )
        }
    }

    /** Shared screenshot-to-contract path for production and deterministic offline fixture tests. */
    internal fun probeStartGameErrorDialogForImage(
        image: BufferedImage?,
        screenshot: String? = null,
        recognize: (BufferedImage, String) -> OcrRecognition,
    ): StartGameErrorDialogProbe {
        if (image == null) {
            return StartGameErrorDialogProbe(
                state = MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
                screenshot = screenshot,
                reason = "capture-unavailable",
            )
        }
        val values = ScreenStateRoiSelector
            .selectStartGameError(image.width, image.height)
            .associate { roi ->
                log.info {
                    "MATCHMAKING_ERROR_DIALOG_OCR_ROI name=${roi.name} " +
                        "x=${roi.bounds.x} y=${roi.bounds.y} " +
                        "w=${roi.bounds.width} h=${roi.bounds.height} space=capture-local"
                }
                roi.name to recognize(crop(image, roi.bounds), roi.name)
            }
        val title = values[ScreenStateRoiSelector.START_GAME_ERROR_TITLE_ROI]
        val body = values[ScreenStateRoiSelector.START_GAME_ERROR_BODY_ROI]
        val confirm = values[ScreenStateRoiSelector.START_GAME_ERROR_CONFIRM_ROI]
        val state = StartGameErrorDialogClassifier.classify(
            title = title?.text.orEmpty(),
            body = body?.text.orEmpty(),
            confirm = confirm?.text.orEmpty(),
            confidences = listOf(title?.confidence, body?.confidence, confirm?.confidence),
        )
        return StartGameErrorDialogProbe(
            state = state,
            title = title?.text.orEmpty(),
            body = body?.text.orEmpty(),
            confirm = confirm?.text.orEmpty(),
            screenshot = screenshot,
            provider = OcrRuntime.lastProviderUsed().name,
            reason = if (state == MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN) {
                "ocr-unverified-or-low-confidence"
            } else {
                "ocr-complete"
            },
        )
    }

    private fun captureScreen(
        hwnd: WinDef.HWND? = ScriptStatus.gameHWND,
        allowCachedGameRect: Boolean = true,
    ): Capture? = runCatching {
        if (GraphicsEnvironment.isHeadless()) return null
        val allScreens = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
            .fold(Rectangle()) { all, next -> all.union(next) }
        if (allScreens.width <= 0 || allScreens.height <= 0) return null

        // GAME_RECT is the most useful crop when the game is windowed. If it
        // is not initialized yet, resolve the client window itself. Never use
        // the entire desktop: the script UI and other windows can otherwise
        // be mistaken for Hearthstone and the image is unnecessarily large.
        val gameRect = ScriptStatus.GAME_RECT
        val gameRectKnown = gameRect.right - gameRect.left >= 400 &&
            gameRect.bottom - gameRect.top >= 300

        // Prefer the live HWND rectangle over the cached GAME_RECT. The
        // cached rectangle can describe a previous fullscreen client while a
        // launcher/restart has already produced a new window elsewhere.
        val liveWindowRect = gameWindowBounds(hwnd)
        val candidate = if (liveWindowRect != null) {
            liveWindowRect
        } else if (allowCachedGameRect && gameRectKnown) {
            Rectangle(
                gameRect.left,
                gameRect.top,
                gameRect.right - gameRect.left,
                gameRect.bottom - gameRect.top,
            )
        } else if (allowCachedGameRect) {
            gameWindowBounds(ScriptStatus.gameHWND)
                ?: run {
                    log.info { "SCREEN_RECOVERY_CAPTURE_SKIPPED reason=game-bounds-unknown" }
                    return null
                }
        } else {
            log.warn { "SCREEN_RECOVERY_CAPTURE_SKIPPED reason=live-window-bounds-unknown hwnd=$hwnd" }
            return null
        }
        val bounds = candidate.intersection(allScreens)
        if (bounds.width < 400 || bounds.height < 300) return null

        val foregroundBefore = User32.INSTANCE.GetForegroundWindow()
        val targetPid = hwnd?.let(::windowProcessId) ?: 0
        val currentGamePid = GameUtil.findGameProcessIdForDiagnostics()
        val foregroundPidBefore = foregroundBefore?.let(::windowProcessId) ?: 0
        val foregroundBeforeOk = hwnd != null &&
            targetPid > 0 && currentGamePid == targetPid.toLong() &&
            GameWindowReadiness.sameVisibleGameProcess(
                targetVisible = User32.INSTANCE.IsWindowVisible(hwnd),
                foregroundVisible = foregroundBefore?.let(User32.INSTANCE::IsWindowVisible) ?: false,
                targetPid = targetPid,
                foregroundPid = foregroundPidBefore,
            )
        log.info {
            "SCREEN_RECOVERY_CAPTURE_GATE phase=before hwnd=$hwnd " +
                "foreground=$foregroundBefore targetPid=$targetPid foregroundPid=$foregroundPidBefore " +
                "accepted=$foregroundBeforeOk bounds=$bounds"
        }
        if (!foregroundBeforeOk) {
            log.warn { "SCREEN_RECOVERY_CAPTURE_REJECTED phase=before reason=foreground-mismatch hwnd=$hwnd" }
            return null
        }
        val image = Robot().createScreenCapture(bounds)
        val foregroundAfter = User32.INSTANCE.GetForegroundWindow()
        val foregroundPidAfter = foregroundAfter?.let(::windowProcessId) ?: 0
        val foregroundAfterOk = hwnd != null &&
            targetPid > 0 && currentGamePid == targetPid.toLong() &&
            GameUtil.findGameProcessIdForDiagnostics() == targetPid.toLong() &&
            GameWindowReadiness.sameVisibleGameProcess(
                targetVisible = User32.INSTANCE.IsWindowVisible(hwnd),
                foregroundVisible = foregroundAfter?.let(User32.INSTANCE::IsWindowVisible) ?: false,
                targetPid = targetPid,
                foregroundPid = foregroundPidAfter,
            )
        val captureAccepted = targetPid > 0 && currentGamePid == targetPid.toLong() &&
            GameUtil.findGameProcessIdForDiagnostics() == targetPid.toLong() &&
            GameWindowReadiness.captureRemainsOnGame(
                targetVisible = User32.INSTANCE.IsWindowVisible(hwnd),
                foregroundVisibleBefore = foregroundBefore?.let(User32.INSTANCE::IsWindowVisible) ?: false,
                foregroundVisibleAfter = foregroundAfter?.let(User32.INSTANCE::IsWindowVisible) ?: false,
                targetPid = targetPid,
                foregroundPidBefore = foregroundPidBefore,
                foregroundPidAfter = foregroundPidAfter,
            )
        log.info {
            "SCREEN_RECOVERY_CAPTURE_GATE phase=after hwnd=$hwnd " +
                "foreground=$foregroundAfter targetPid=$targetPid foregroundPid=$foregroundPidAfter " +
                "accepted=$captureAccepted beforeAccepted=$foregroundBeforeOk bounds=$bounds"
        }
        if (!captureAccepted) {
            log.warn { "SCREEN_RECOVERY_CAPTURE_REJECTED phase=after reason=foreground-changed hwnd=$hwnd" }
            return null
        }
        val saved = DebugScreenshotRing.save(image, "screen-recovery", "stale-screen")
        val file = saved?.file
        Capture(
            image,
            bounds,
            file,
            visualSignature(image),
            gameRectKnown,
            ScriptStatus.gameHWND != null,
        )
    }.getOrElse { error ->
        log.warn(error) { "SCREEN_RECOVERY_FAILED reason=capture-exception" }
        null
    }

    private fun runOCR(capture: Capture): OcrEvidence {
        val tessData = File(TESS_DATA_PATH)
        val chiSim = File(tessData, "$CHI_SIM_DATA.traineddata")
        if (!chiSim.isFile) {
            log.info {
                "SCREEN_RECOVERY_OCR_SKIPPED provider=LEGACY reason=missing-tessdata " +
                    "path=${chiSim.absolutePath}"
            }
            return OcrEvidence("", emptyMap())
        }
        return runCatching {
            // Probe the screen-specific labels first. These are small,
            // explicit crops and are sufficient to identify the special
            // screens without OCR-ing the full client. Only when neither
            // target matches do we use the smaller secondary bands below.
            val targeted = ScreenStateRoiSelector
                .selectTargeted(capture.image.width, capture.image.height)
                .associate { roi ->
                    log.info {
                        "SCREEN_RECOVERY_OCR_ROI name=${roi.name} " +
                            "x=${roi.bounds.x} y=${roi.bounds.y} " +
                            "w=${roi.bounds.width} h=${roi.bounds.height} space=capture-local"
                    }
                    roi.name to ocrScreenRoi(crop(capture.image, roi.bounds), tessData, targeted = true)
                }
            if (targetedScreenDetection(targeted, capture.visual) != null) {
                return@runCatching OcrEvidence(targeted.values.joinToString(separator = ""), targeted)
            }

            // Do not fall back to OCR-ing most of the client. These smaller
            // secondary bands preserve compatibility for screens without a
            // dedicated anchor while keeping the recovery path bounded and
            // excluding the script window on the right.
            val secondary = ScreenStateRoiSelector
                .selectSecondary(capture.image.width, capture.image.height)
                .associate { roi ->
                    log.info {
                        "SCREEN_RECOVERY_OCR_ROI name=${roi.name} " +
                            "x=${roi.bounds.x} y=${roi.bounds.y} " +
                            "w=${roi.bounds.width} h=${roi.bounds.height} space=capture-local"
                    }
                    roi.name to ocrScreenRoi(crop(capture.image, roi.bounds), tessData, targeted = false)
                }
            OcrEvidence(secondary.values.joinToString(separator = ""), targeted + secondary)
        }.getOrElse { error ->
            log.warn(error) { "SCREEN_RECOVERY_OCR_FAILED" }
            OcrEvidence("", emptyMap())
        }
    }

    private fun ocrScreenRoi(image: BufferedImage, tessData: File, targeted: Boolean): String {
        val ocrImage = if (targeted) enlargeTargetedOcr(image) else resizeForOcr(image)
        return Tesseract().apply {
            setDatapath(tessData.absolutePath)
            setLanguage(CHI_SIM_DATA)
            setPageSegMode(if (targeted) 7 else 11)
            setVariable("user_defined_dpi", "180")
        }.doOCR(ocrImage).replace(Regex("\\s+"), "")
    }

    private fun formatRoiEvidence(values: Map<String, String>): String =
        values.entries.joinToString(separator = ";") { (name, value) ->
            "$name=${value.ifBlank { "<empty>" }.take(MAX_OCR_TEXT_LENGTH)}"
        }.ifBlank { "<none>" }

    private fun enlargeTargetedOcr(image: BufferedImage): BufferedImage {
        val scale = 4
        val enlarged = BufferedImage(
            (image.width * scale).coerceAtLeast(1),
            (image.height * scale).coerceAtLeast(1),
            BufferedImage.TYPE_INT_RGB,
        )
        val graphics = enlarged.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(image, 0, 0, enlarged.width, enlarged.height, null)
        } finally {
            graphics.dispose()
        }
        return enlarged
    }

    private fun looksLikeHearthstoneVisual(visual: VisualSignature): Boolean =
        visual.warmRatio >= 0.005 || visual.blueRatio >= 0.005

    private fun crop(image: BufferedImage, bounds: Rectangle): BufferedImage {
        val safe = bounds.intersection(Rectangle(0, 0, image.width, image.height))
        return image.getSubimage(safe.x, safe.y, safe.width.coerceAtLeast(1), safe.height.coerceAtLeast(1))
    }

    private fun gameWindowBounds(hwnd: WinDef.HWND?): Rectangle? {
        if (hwnd == null) return null
        val windowRect = WinDef.RECT()
        if (!User32.INSTANCE.GetWindowRect(hwnd, windowRect)) return null
        val width = windowRect.right - windowRect.left
        val height = windowRect.bottom - windowRect.top
        return if (width >= 400 && height >= 300) {
            Rectangle(windowRect.left, windowRect.top, width, height)
        } else {
            null
        }
    }

    private fun resizeForOcr(image: BufferedImage): BufferedImage {
        if (image.width <= OCR_MAX_WIDTH) return image
        val scale = OCR_MAX_WIDTH.toDouble() / image.width
        val width = OCR_MAX_WIDTH
        val height = (image.height * scale).toInt().coerceAtLeast(1)
        val resized = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = resized.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
            graphics.drawImage(image, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return resized
    }

    private fun visualSignature(image: BufferedImage): VisualSignature {
        var hash = 1125899906842597L
        var warm = 0
        var blue = 0
        var samples = 0
        val stepX = (image.width / 80).coerceAtLeast(1)
        val stepY = (image.height / 45).coerceAtLeast(1)
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val rgb = image.getRGB(x, y)
                val r = rgb shr 16 and 0xff
                val g = rgb shr 8 and 0xff
                val b = rgb and 0xff
                hash = hash * 31 + rgb.toLong()
                if (r > 70 && r > g * 1.12 && r > b * 1.12) warm++
                if (b > 80 && b > r * 1.18 && b > g * 1.05) blue++
                samples++
                x += stepX
            }
            y += stepY
        }
        // GameRect.GAME_END_CONTINUE_RECT maps to this stable lower-center
        // band on the 16:9 Hearthstone client.  The result page renders a
        // pale, low-saturation "点击继续" label there even when OCR returns
        // no text.  The banner signal is a second independent check: result
        // pages dim and desaturate the central outcome panel, while ordinary
        // gameplay and mulligan controls do not.
        val continueSignal = sampleRegion(image, 0.41, 0.59, 0.91, 0.98)
        val bannerSignal = sampleRegion(image, 0.38, 0.62, 0.52, 0.72)
        val loadingCenterSignal = sampleRegion(image, 0.18, 0.82, 0.08, 0.92)
        val reconnectDialogPanelSignal = sampleRegion(image, 0.32, 0.68, 0.39, 0.62)
        return VisualSignature(
            sampleHash = hash,
            warmRatio = if (samples == 0) 0.0 else warm.toDouble() / samples,
            blueRatio = if (samples == 0) 0.0 else blue.toDouble() / samples,
            loadingCentralDarkRatio = loadingCenterSignal.darkRatio,
            resultContinueGrayLightRatio = continueSignal.grayLightRatio,
            resultBannerLowSaturationRatio = bannerSignal.lowSaturationRatio,
            reconnectFailureDialogVisual = looksLikeReconnectFailureDialogVisual(
                lowSaturationRatio = reconnectDialogPanelSignal.lowSaturationRatio,
                darkRatio = reconnectDialogPanelSignal.darkRatio,
                warmRatio = reconnectDialogPanelSignal.warmRatio,
            ),
        )
    }

    private fun sampleRegion(
        image: BufferedImage,
        left: Double,
        right: Double,
        top: Double,
        bottom: Double,
    ): RegionSignal {
        val x0 = (image.width * left).toInt().coerceIn(0, image.width)
        val x1 = (image.width * right).toInt().coerceIn(x0, image.width)
        val y0 = (image.height * top).toInt().coerceIn(0, image.height)
        val y1 = (image.height * bottom).toInt().coerceIn(y0, image.height)
        var samples = 0
        var grayLight = 0
        var lowSaturation = 0
        var dark = 0
        var warm = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val rgb = image.getRGB(x, y)
                val r = rgb shr 16 and 0xff
                val g = rgb shr 8 and 0xff
                val b = rgb and 0xff
                val maximum = maxOf(r, g, b)
                val minimum = minOf(r, g, b)
                val average = (r + g + b) / 3.0
                if (maximum - minimum <= 35) lowSaturation++
                if (maximum - minimum <= 35 && average >= 180) grayLight++
                if (average < 70) dark++
                if (r > 70 && r > g * 1.12 && r > b * 1.12) warm++
                samples++
                x += 2
            }
            y += 2
        }
        if (samples == 0) return RegionSignal(0.0, 0.0, 0.0, 0.0)
        return RegionSignal(
            grayLightRatio = grayLight.toDouble() / samples,
            lowSaturationRatio = lowSaturation.toDouble() / samples,
            darkRatio = dark.toDouble() / samples,
            warmRatio = warm.toDouble() / samples,
        )
    }

    private fun detect(ocrText: String, visual: VisualSignature): Detection? =
        detect(OcrEvidence(ocrText, emptyMap()), visual)

    private fun detect(evidence: OcrEvidence, visual: VisualSignature): Detection? {
        targetedScreenDetection(evidence.targeted, visual)?.let { return it }

        val ocrText = evidence.text
        val text = ocrText.lowercase(Locale.ROOT)
        fun has(vararg terms: String): Boolean = terms.all { text.contains(it) }

        // More specific screens must win before generic home/login words.
        // A reconnect/error dialog can leave the deck-selection title visible
        // behind it. The dialog must therefore win before DECK_SELECTION.
        if (looksLikeReconnectFailureText(text)) {
            return Detection(ScreenKind.RECONNECT_FAILURE, ModeEnum.LOGIN, 97, "reconnect-failure-text")
        }
        if (looksLikeReconnectText(text)) {
            return Detection(ScreenKind.RECONNECT, ModeEnum.LOGIN, 96, "reconnect-disconnected-text")
        }
        if (looksLikeReconnectSpinnerText(text)) {
            return Detection(ScreenKind.RECONNECT_SPINNER, ModeEnum.STARTUP, 96, "reconnect-spinner-text")
        }
        if (text.contains("选择套牌") || has("套牌", "狂野对战")) {
            return Detection(ScreenKind.DECK_SELECTION, ModeEnum.TOURNAMENT, 100, "deck-selection-title")
        }
        if (looksLikeResultText(text)) {
            return Detection(ScreenKind.RESULT, ModeEnum.GAMEPLAY, 95, "result-text")
        }
        if (looksLikeResultVisual(visual.resultContinueGrayLightRatio, visual.resultBannerLowSaturationRatio)) {
            return Detection(ScreenKind.RESULT, ModeEnum.GAMEPLAY, 92, "result-fixed-continue-visual")
        }
        if (looksLikeShopOverlayText(text) && looksLikeShopOverlayVisual(visual)) {
            return Detection(ScreenKind.SHOP_OVERLAY, ModeEnum.HUB, 94, "shop-overlay-text-and-visual")
        }
        if (looksLikeStartupQuestOverlayText(text)) {
            return Detection(ScreenKind.HOME_TASK_OVERLAY, ModeEnum.HUB, 96, "startup-your-quests-overlay")
        }
        // The live client uses "搜寻对手" while some localized/client builds
        // use "寻找对手". OCR also commonly separates the cancel label, so
        // accept both forms but require a matchmaking-specific phrase.
        if (looksLikeMatchmakingText(text)) {
            return Detection(ScreenKind.MATCHMAKING, ModeEnum.TOURNAMENT, 95, "matchmaking-text")
        }
        val hubLabels = hubNavigationLabels(text)
        if (hubLabels.size >= 2) {
            return Detection(
                ScreenKind.HOME,
                ModeEnum.HUB,
                95,
                "HOME/HUB：识别到${hubLabels.joinToString("、")}",
            )
        }
        val collectionLabels = collectionPageLabels(text)
        if (collectionLabels.isNotEmpty()) {
            return Detection(
                ScreenKind.COLLECTION,
                ModeEnum.COLLECTIONMANAGER,
                95,
                "收藏页：识别到${collectionLabels.joinToString("、")}",
            )
        }
        // Reward pages advertise unopened packs too.  A bare "卡牌包" is
        // therefore not evidence that the pack-opening scene is visible.
        if (looksLikePackOpeningText(text)) {
            return Detection(ScreenKind.PACK_OPENING, ModeEnum.PACKOPENING, 95, "pack-opening-text")
        }
        if (looksLikeBlackMarketText(text)) {
            return Detection(ScreenKind.HOME, ModeEnum.HUB, 96, "black-market-text")
        }
        if (looksLikeStalledReconnectLoadingText(text)) {
            return Detection(ScreenKind.LOADING, ModeEnum.STARTUP, 95, "reconnect-slow-loading-text")
        }
        if (looksLikeLoadingText(text)) {
            return Detection(ScreenKind.LOADING, ModeEnum.STARTUP, 88, "loading-text")
        }
        // A dark/warm Hearthstone overlay is not positive loading evidence:
        // shop, reward, collection, and other modal pages can share the same
        // palette. Keep the visual signature in the diagnostic trail, but
        // never change authoritative state to LOADING from visual ratios
        // alone. Loading requires loading/reconnect OCR or an explicit phase
        // event.
        if (text.contains("登录") || text.contains("重新连接")) {
            return Detection(ScreenKind.LOGIN, ModeEnum.LOGIN, 90, "login-text")
        }
        if (text.contains("狂野对战") || text.contains("标准对战") || text.contains("传统对战")) {
            return Detection(ScreenKind.TOURNAMENT, ModeEnum.TOURNAMENT, 90, "tournament-text")
        }
        if (text.contains("选择模式") || text.contains("冒险模式")) {
            return Detection(ScreenKind.GAME_MODE, ModeEnum.GAME_MODE, 90, "game-mode-text")
        }
        if (text.contains("任务") && (text.contains("商店") || text.contains("对战"))) {
            return Detection(ScreenKind.HOME, ModeEnum.HUB, 88, "home-text")
        }

        // OCR is optional in existing installations. Keep the visual signal
        // in the diagnostic trail, but do not turn a generic Hearthstone
        // palette into an automatic click; an uncertain screen is safer than
        // a false recovery while the user is looking at the client.
        if (visual.warmRatio > 0.0 || visual.blueRatio > 0.0) return null
        return null
    }

    private fun targetedScreenDetection(targeted: Map<String, String>, visual: VisualSignature): Detection? {
        val reconnectDialogText = listOf(
            targeted[ScreenStateRoiSelector.RECONNECT_DIALOG_TITLE_ROI].orEmpty(),
            targeted[ScreenStateRoiSelector.RECONNECT_DIALOG_STATUS_ROI].orEmpty(),
            targeted[ScreenStateRoiSelector.RECONNECT_DIALOG_MESSAGE_ROI].orEmpty(),
        ).joinToString(separator = "")
        if (looksLikeReconnectFailureDialogRoiText(reconnectDialogText)) {
            return Detection(ScreenKind.RECONNECT_FAILURE, ModeEnum.LOGIN, 100, "reconnect-failure-dialog-roi")
        }
        if (looksLikeReconnectDialogRoiText(reconnectDialogText)) {
            return Detection(ScreenKind.RECONNECT, ModeEnum.LOGIN, 100, "reconnect-dialog-roi")
        }

        // A label is only authoritative when it came from its own ROI. This
        // prevents an OCR spill from the deck title into the traditional-mode
        // branch (or vice versa) from changing the recovery state.
        val deckTitle = targeted[ScreenStateRoiSelector.DECK_SELECTION_TITLE_ROI].orEmpty()
        // The deployed 2026-09-26 capture had the exact deck title behind a
        // centered, dark-gray non-reconnectable modal. OCR garbled the dialog
        // ROIs, so the underlying deck title incorrectly won. Require both
        // that screen-specific title anchor and the distinctive modal panel.
        if (looksLikeDeckSelectionTitleText(deckTitle) && visual.reconnectFailureDialogVisual) {
            return Detection(
                ScreenKind.RECONNECT_FAILURE,
                ModeEnum.LOGIN,
                96,
                "deck-underlay-centered-reconnect-failure-visual",
            )
        }
        if (looksLikeDeckSelectionTitleText(deckTitle)) {
            return Detection(
                ScreenKind.DECK_SELECTION,
                ModeEnum.TOURNAMENT,
                100,
                "deck-selection-title-roi",
            )
        }
        val traditionalBattle = targeted[ScreenStateRoiSelector.TRADITIONAL_BATTLE_ROI].orEmpty()
        if (looksLikeTraditionalBattleText(traditionalBattle)) {
            // This is the hub's central mode selector. The tournament strategy
            // has not entered yet, so recover to HUB and let its normal
            // want-enter click choose traditional battle.
            return Detection(
                ScreenKind.HOME,
                ModeEnum.HUB,
                100,
                "traditional-battle-roi",
            )
        }
        return null
    }

    private fun windowProcessId(hwnd: WinDef.HWND): Int {
        val pid = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid)
        return pid.value
    }

    internal fun looksLikeReconnectDialogRoiText(ocrText: String): Boolean {
        val text = normalizedScreenText(ocrText)
        return text.contains("离线状态") ||
            text.contains("离线太久") ||
            text.contains("游戏连接中断") ||
            text.contains("连接中断") ||
            text.contains("对手无法连接") ||
            text.contains("游戏无法继续") ||
            text.contains("请再试") ||
            text.contains("请重试")
    }

    /**
     * The seasonal Black Market is rendered as a hub overlay. It has a
     * dedicated bottom-right "前往传统对战" action, which is already handled
     * by HubModeStrategy's promo/back click. Require a second market-specific
     * label so a stray OCR read of "黑市" cannot redirect an unrelated page.
     */
    internal fun looksLikeBlackMarketText(ocrText: String): Boolean {
        val text = normalizedScreenText(ocrText)
        val market = text.contains("黑市") || text.contains("blackmarket")
        val detail = text.contains("价格更新") ||
            text.contains("活动剩余时间") ||
            text.contains("库存") ||
            text.contains("流浪者")
        return market && detail
    }

    /** The startup modal title is distinct from the persistent Home task button. */
    internal fun looksLikeStartupQuestOverlayText(ocrText: String): Boolean {
        val normalized = ocrText.lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{Punct}，。、“”‘’：:！!？?]"), "")
        return normalized.contains("你的任务") || normalized.contains("yourquests")
    }

    internal fun looksLikeReconnectFailureDialogRoiText(ocrText: String): Boolean {
        val text = normalizedScreenText(ocrText)
        return text.contains("发生错误") ||
            text.contains("对手无法连接") ||
            text.contains("游戏无法继续") ||
            text.contains("重新连接失败") ||
            text.contains("请再试") ||
            text.contains("请重试")
    }

    /** OCR-free modal fallback, gated by the dedicated deck-title anchor. */
    internal fun looksLikeReconnectFailureDialogVisual(
        lowSaturationRatio: Double,
        darkRatio: Double,
        warmRatio: Double,
    ): Boolean = lowSaturationRatio >= 0.82 && darkRatio >= 0.74 && warmRatio <= 0.10

    private fun normalizedScreenText(ocrText: String): String =
        ocrText.lowercase(Locale.ROOT).replace(Regex("[\\s，。、“”‘’：:！!？?]"), "")

    internal fun looksLikeTraditionalBattleText(ocrText: String): Boolean {
        val text = normalizedScreenText(ocrText)
        if (text.contains("传统对战") || text.contains("传统對戰")) return true

        // This is a dedicated 传统对战 ROI, not whole-screen OCR. PaddleX/Tesseract
        // can preserve the first two glyphs while misreading the latter two (the
        // live home screenshot produced "传统X寺虐"). Treat that short, anchored
        // prefix as the same home-screen label so the generic loading visual does
        // not steal the transition. Keep the tolerance scoped to this ROI and
        // require a plausible trailing label glyph to avoid broad OCR guesses.
        return text.startsWith("传统") &&
            text.length in 4..10 &&
            text.any { it in "对對战戰虐寺" }
    }

    internal fun looksLikeDeckSelectionTitleText(ocrText: String): Boolean {
        val text = normalizedScreenText(ocrText)
        return text.contains("选择套牌") || text.contains("選擇套牌")
    }

    /**
     * The hub exposes several primary mode buttons at once. The persistent
     * bottom navigation label "我的收藏" is intentionally excluded: it is
     * present on the hub and must not override the primary mode cluster.
     */
    private fun hubNavigationLabels(ocrText: String): List<String> {
        val text = ocrText.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return listOf("传统对战", "酒馆战棋", "竞技模式", "其他模式")
            .filter(text::contains)
    }

    /** Collection-page labels are specific to the opened collection view. */
    private fun collectionPageLabels(ocrText: String): List<String> {
        val text = ocrText.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return listOf("收藏管理", "我的套牌", "卡牌制作")
            .filter(text::contains)
    }

    internal fun looksLikeHubText(ocrText: String): Boolean = hubNavigationLabels(ocrText).size >= 2

    internal fun looksLikeCollectionText(ocrText: String): Boolean = collectionPageLabels(ocrText).isNotEmpty()

    /**
     * The hero-skin shop overlay is drawn over the Hub. OCR commonly keeps
     * only the stable word "皮肤" while the title and price are garbled; the
     * visual gate prevents ordinary mentions from becoming an overlay signal.
     */
    internal fun looksLikeShopOverlayText(ocrText: String): Boolean {
        val text = ocrText.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return text.contains("皮肤")
    }

    private fun looksLikeShopOverlayVisual(visual: VisualSignature): Boolean =
        visual.loadingCentralDarkRatio >= 0.45 &&
            visual.warmRatio >= 0.15 &&
            visual.blueRatio <= 0.05

    internal fun looksLikeMatchmakingText(ocrText: String): Boolean {
        val text = ocrText.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return text.contains("寻找对手") ||
            text.contains("搜寻对手") ||
            text.contains("搜索对手") ||
            text.contains("正在匹配") ||
            text.contains("取消匹配") ||
            text.contains("取消") && text.contains("匹配")
    }

    internal fun looksLikePackOpeningText(ocrText: String): Boolean {
        val text = ocrText.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        val rewardPage = text.contains("未领取的奖励") ||
            text.contains("未领取奖励") ||
            text.contains("领取奖励") ||
            text.contains("奖励") && text.contains("确定")
        if (rewardPage) return false
        return text.contains("开包") ||
            text.contains("打开卡牌包") ||
            text.contains("点击打开") ||
            text.contains("翻开卡牌包")
    }

    internal fun looksLikeReconnectFailureText(ocrText: String): Boolean {
        val text = ocrText.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return text.contains("重新连接失败") ||
            text.contains("无法重新连接") ||
            text.contains("请重新启动炉石传说") ||
            text.contains("重新启动《炉石传说》") ||
            text.contains("无法通过暴雪战网服务进行登录") ||
            text.contains("无法通过暴雪战网服务进行登入") ||
            text.contains("unabletologintobattlenetservice") ||
            text.contains("unabletologintoblizzardbattle.netservice") ||
            text.contains("unabletoconnecttoblizzardbattlenet") ||
            text.contains("cannotlogintobattlenetservice") ||
            text.contains("cannotlogintoblizzardbattle.netservice")
    }

    internal fun looksLikeLoadingText(ocrText: String): Boolean {
        val text = ocrText.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return text.contains("正在加载") ||
            text.contains("加载中") ||
            text.contains("读取中") ||
            text.contains("请稍候")
    }

    /**
     * Keep the post-reconnect warning separate from ordinary loading so
     * matchmaking and normal scene transitions never trigger a restart.
     */
    internal fun looksLikeStalledReconnectLoadingText(ocrText: String): Boolean {
        val text = ocrText.replace(Regex("\\s+"), "")
        return text.contains("本次连接较平常花费了更多时间") &&
            text.contains("检查你的网络连接")
    }

    internal fun shouldRestartStalledReconnectForTest(reconnectStartedAt: Long, now: Long): Boolean =
        shouldRestartStalledReconnect(reconnectStartedAt, now)

    internal fun shouldRestartStalledLoadingForTest(loadingStartedAt: Long, now: Long): Boolean =
        shouldRestartStalledLoading(loadingStartedAt, now)

    internal fun stalledReconnectAnchorForTest(
        acceptedReconnectAt: Long,
        observedWarningAt: Long,
        now: Long,
    ): Long = stalledReconnectAnchor(acceptedReconnectAt, observedWarningAt, now)

    private fun shouldRestartStalledReconnect(reconnectStartedAt: Long, now: Long): Boolean =
        reconnectStartedAt > 0L && now >= reconnectStartedAt &&
            now - reconnectStartedAt >= STALLED_RECONNECT_LOADING_RESTART_MS

    private fun shouldRestartStalledLoading(loadingStartedAt: Long, now: Long): Boolean =
        loadingStartedAt > 0L && now >= loadingStartedAt &&
            now - loadingStartedAt >= STALLED_RECONNECT_LOADING_RESTART_MS

    private fun stalledReconnectAnchor(
        acceptedReconnectAt: Long,
        observedWarningAt: Long,
        now: Long,
    ): Long = acceptedReconnectAt.takeIf { it > 0L } ?: observedWarningAt.takeIf { it > 0L } ?: now

    private fun firstSlowReconnectWarningAt(now: Long): Long {
        while (true) {
            val previous = slowReconnectWarningObservedAt.get()
            if (previous > 0L) return previous
            if (slowReconnectWarningObservedAt.compareAndSet(0L, now)) return now
        }
    }

    private fun firstLoadingObservedAt(now: Long): Long {
        while (true) {
            val previous = loadingObservedAt.get()
            if (previous > 0L) return previous
            if (loadingObservedAt.compareAndSet(0L, now)) return now
        }
    }

    private fun consumeStalledLoadingAnchor(startedAt: Long): Boolean =
        loadingObservedAt.compareAndSet(startedAt, 0L)

    private fun consumeStalledReconnectAnchor(acceptedReconnectAt: Long, startedAt: Long): Boolean {
        return if (acceptedReconnectAt > 0L) {
            reconnectAcceptedAt.compareAndSet(acceptedReconnectAt, 0L)
        } else {
            slowReconnectWarningObservedAt.compareAndSet(startedAt, 0L)
        }
    }

    /** Re-inspect after reconnect so the offline prompt and spinner are not conflated. */
    private fun scheduleReconnectSpinnerProbe(parentToken: Long) {
        if (!ScreenRecoveryRuntime.isCurrent(parentToken)) return
        val generation = reconnectProbeGeneration.incrementAndGet()
        EXTRA_THREAD_POOL.schedule({
            if (!ScreenRecoveryRuntime.isCurrent(parentToken) ||
                generation != reconnectProbeGeneration.get() || !WorkTimeListener.working ||
                PauseStatus.isPause || WarEx.inWar
            ) {
                return@schedule
            }
            log.info { "SCREEN_RECOVERY_RECONNECT_PROBE_STARTED waitMs=$RECONNECT_SPINNER_CHECK_DELAY_MS" }
            inspectAndRecover(
                stuckForMs = RECONNECT_SPINNER_CHECK_DELAY_MS,
                stateFingerprint = "reconnect-spinner-probe",
            )
        }, RECONNECT_SPINNER_CHECK_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    internal fun looksLikeLoadingVisual(
        centralDarkRatio: Double,
        warmRatio: Double,
        blueRatio: Double,
    ): Boolean = centralDarkRatio >= 0.35 && warmRatio >= 0.10 && blueRatio <= 0.15

    /** Test-only classification seam that keeps the production detector private. */
    internal fun classifyForTest(ocrText: String): String? = detect(
        ocrText,
        VisualSignature(
            sampleHash = 0L,
            warmRatio = 0.0,
            blueRatio = 0.0,
            loadingCentralDarkRatio = 0.0,
            resultContinueGrayLightRatio = 0.0,
            resultBannerLowSaturationRatio = 0.0,
        ),
    )?.kind?.code

    internal fun classifyWithVisualForTest(
        ocrText: String,
        centralDarkRatio: Double,
        warmRatio: Double,
        blueRatio: Double,
    ): String? = detect(
        ocrText,
        VisualSignature(
            sampleHash = 0L,
            warmRatio = warmRatio,
            blueRatio = blueRatio,
            loadingCentralDarkRatio = centralDarkRatio,
            resultContinueGrayLightRatio = 0.0,
            resultBannerLowSaturationRatio = 0.0,
        ),
    )?.kind?.code

    internal fun classificationEvidenceForTest(ocrText: String): String? = detect(
        ocrText,
        VisualSignature(
            sampleHash = 0L,
            warmRatio = 0.0,
            blueRatio = 0.0,
            loadingCentralDarkRatio = 0.0,
            resultContinueGrayLightRatio = 0.0,
            resultBannerLowSaturationRatio = 0.0,
        ),
    )?.evidence

    internal fun recoveryTransitionForTest(
        ocrText: String,
        targeted: Map<String, String> = emptyMap(),
    ): RecoveryTransitionForTest? = detect(
        OcrEvidence(ocrText, targeted),
        VisualSignature(
            sampleHash = 0L,
            warmRatio = 0.0,
            blueRatio = 0.0,
            loadingCentralDarkRatio = 0.0,
            resultContinueGrayLightRatio = 0.0,
            resultBannerLowSaturationRatio = 0.0,
        ),
    )?.let { detection ->
        RecoveryTransitionForTest(
            screen = detection.kind.code,
            mode = detection.mode,
            enterStrategy = detection.kind != ScreenKind.DECK_SELECTION &&
                detection.kind != ScreenKind.HOME_TASK_OVERLAY &&
                detection.kind != ScreenKind.RESULT &&
                detection.kind != ScreenKind.MATCHMAKING &&
                detection.kind != ScreenKind.RECONNECT &&
                detection.kind != ScreenKind.RECONNECT_FAILURE &&
                detection.kind != ScreenKind.RECONNECT_SPINNER &&
                detection.kind != ScreenKind.LOADING,
            action = when (detection.kind) {
                ScreenKind.HOME_TASK_OVERLAY -> "DISMISS_HOME_TASK_OVERLAY"
                ScreenKind.DECK_SELECTION -> "START_MATCHING"
                ScreenKind.HOME,
                ScreenKind.TOURNAMENT,
                ScreenKind.GAME_MODE,
                ScreenKind.COLLECTION,
                ScreenKind.PACK_OPENING,
                ScreenKind.SHOP_OVERLAY,
                ScreenKind.LOGIN,
                -> "ENTER_MODE_STRATEGY"
                ScreenKind.RESULT -> "DISMISS_STALE_RESULT"
                ScreenKind.MATCHMAKING -> "WAIT_FOR_GAMEPLAY"
                ScreenKind.RECONNECT -> "CLICK_RECONNECT"
                ScreenKind.RECONNECT_FAILURE -> "RESTART_CLIENT"
                ScreenKind.RECONNECT_SPINNER -> "WAIT_OR_CANCEL_RECONNECT"
                ScreenKind.LOADING -> "WAIT_FOR_CLIENT"
            },
        )
    }

    internal fun recoveryTransitionForImageForTest(
        image: BufferedImage,
        ocrText: String,
        targeted: Map<String, String>,
    ): RecoveryTransitionForTest? = detect(
        OcrEvidence(ocrText, targeted),
        visualSignature(image),
    )?.let { detection ->
        RecoveryTransitionForTest(
            screen = detection.kind.code,
            mode = detection.mode,
            enterStrategy = detection.kind != ScreenKind.DECK_SELECTION &&
                detection.kind != ScreenKind.HOME_TASK_OVERLAY &&
                detection.kind != ScreenKind.RECONNECT_FAILURE,
            action = when (detection.kind) {
                ScreenKind.HOME_TASK_OVERLAY -> "DISMISS_HOME_TASK_OVERLAY"
                ScreenKind.DECK_SELECTION -> "START_MATCHING"
                ScreenKind.RECONNECT_FAILURE -> "RESTART_CLIENT"
                else -> "OTHER"
            },
        )
    }

    /**
     * Result pages have a stable action label even when the outcome title is
     * rendered with decorative glyphs or OCR misreads (for example, 败北 may
     * be dropped while 点击继续 is still recognized). The upstream result
     * path treats the page as actionable from its phase event; this fallback
     * uses the same action label for a stale-screen recovery path.
     */
    internal fun looksLikeResultText(ocrText: String): Boolean {
        val text = ocrText.lowercase(Locale.ROOT)
            .replace("写击继续", "点击继续")
            .replace("击继续", "点击继续")
        return text.contains("点击继续") ||
            text.contains("胜利") && text.contains("继续") ||
            text.contains("失败") && text.contains("继续") ||
            text.contains("对战结束") && text.contains("继续")
    }

    /**
     * OCR-free result-page fallback.  A single bright pixel cluster is not
     * enough because the live board has many highlights; require the fixed
     * lower-center continue label and the dimmed/desaturated result banner.
     */
    internal fun looksLikeResultVisual(
        resultContinueGrayLightRatio: Double,
        resultBannerLowSaturationRatio: Double,
    ): Boolean =
        resultContinueGrayLightRatio >= RESULT_CONTINUE_GRAY_LIGHT_MIN &&
            resultBannerLowSaturationRatio >= RESULT_BANNER_LOW_SATURATION_MIN

    /**
     * Re-check the actual desktop after a result-page input was sent.
     *
     * `MouseUtil` can report that an event was queued even when the client did
     * not consume it. Return false only for an explicitly recognized
     * post-result destination; a live board, transitional screen, or
     * inconclusive capture returns null and cannot release the rank barrier.
     */
    internal fun isResultVisibleForRecovery(): Boolean? {
        val token = ScreenRecoveryRuntime.tokenOrNull()
            ?: return UpstreamScreenStateRecovery.isResultVisibleForRecovery()
        return runCatching {
            val liveWindow = resolveLiveGameWindow() ?: return@runCatching null
            val capture = captureScreen(liveWindow, allowCachedGameRect = false) ?: return@runCatching null
            if (!ScreenRecoveryRuntime.isCurrent(token)) return@runCatching null
            val detection = detect(runOCR(capture), capture.visual)
            if (!ScreenRecoveryRuntime.isCurrent(token)) return@runCatching null
            resultPageVisibility(detection)
        }.getOrElse { error ->
            if (ScreenRecoveryRuntime.isCurrent(token)) {
                log.warn(error) { "SCREEN_RECOVERY_RESULT_POSTCHECK_FAILED" }
            }
            null
        }
    }

    private fun resultPageVisibility(detection: Detection?): Boolean? =
        resultVisibilityForTest(detection?.kind?.code, detection?.confidence ?: 0)

    /** Contract seam shared by the live postcheck and deterministic mapping tests. */
    internal fun resultVisibilityForTest(screenKind: String?, confidence: Int): Boolean? = when {
        confidence < 85 -> null
        screenKind == ScreenKind.RESULT.code -> true
        POST_RESULT_DESTINATIONS.any { it.code == screenKind } -> false
        else -> null
    }

    /**
     * Resolve a real, visible Hearthstone window for recovery.  The E2E
     * discovery path may return a coordinate-only sentinel while the process
     * exists but has no window; that sentinel is never safe for OCR or Robot.
     */
    private fun resolveLiveGameWindow(): WinDef.HWND? {
        val cached = ScriptStatus.gameHWND
        if (!GameUtil.isAliveOfGame()) {
            if (cached != null) {
                log.warn { "SCREEN_RECOVERY_STALE_WINDOW cleared=$cached reason=game-process-missing" }
            }
            ScriptStatus.gameHWND = null
            return null
        }
        val discovered = GameUtil.findGameHWND()
        val live = discovered?.takeIf(GameUtil::isVerifiedCurrentGameWindow)
        if (live == null) {
            log.warn {
                "SCREEN_RECOVERY_WINDOW_UNAVAILABLE cached=${cached ?: "none"} " +
                    "discovered=${discovered ?: "none"} reason=no-visible-game-window"
            }
            ScriptStatus.gameHWND = null
            return null
        }
        if (cached == null || cached.toString() != live.toString()) {
            log.info { "SCREEN_RECOVERY_WINDOW_REDISCOVERED old=${cached ?: "none"} new=$live" }
        }
        ScriptStatus.gameHWND = live
        GameUtil.updateGameRect(live)
        return live
    }

    /**
     * The reconnect page is not a normal login page.  In particular, the
     * current client often OCRs the button as "重新接..." while retaining
     * the exact "连接中断" message.  Require both the disconnected message
     * and a recovery/offline hint so a normal login prompt cannot cause a
     * click in the game window.
     */
    internal fun looksLikeReconnectText(ocrText: String): Boolean {
        val text = ocrText.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        val disconnected = text.contains("连接中断") ||
            text.contains("连接断开") ||
            text.contains("离线状态") ||
            text.contains("离线太久") ||
            text.contains("offlinefortoolong") ||
            text.contains("youhavebeenoffline")
        val recoveryHint = text.contains("重新连接") ||
            text.contains("重新接") ||
            text.contains("离线") ||
            text.contains("reconnect") ||
            text.contains("needtoreconnect")
        val offlineTooLong = text.contains("离线太久") ||
            text.contains("offlinefortoolong") ||
            text.contains("youhavebeenofflinetoolong")
        return (disconnected && recoveryHint) || offlineTooLong
    }

    /** Loading screen shown after the offline dialog accepts reconnect. */
    internal fun looksLikeReconnectSpinnerText(ocrText: String): Boolean {
        val text = ocrText.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return text.contains("正在重新连接") ||
            text.contains("重新连接中") ||
            text.contains("正在连接服务器") ||
            text.contains("连接服务器中") ||
            text.contains("reconnecting") ||
            text.contains("connectingtoserver")
    }

    private fun shouldAttemptReconnect(now: Long): Boolean {
        while (true) {
            val previous = reconnectAttemptAt.get()
            if (previous > 0L && now - previous < RECONNECT_RETRY_INTERVAL_MS) return false
            if (reconnectAttemptAt.compareAndSet(previous, now)) return true
        }
    }

    /**
     * Recovery is not complete when an input was queued or Mode.recover was
     * called. Require a fresh PID-verified Hearthstone screenshot and state
     * consistent with the requested workflow transition.
     */
    private fun confirmRecoveryTransition(
        detection: Detection,
        recoveryToken: Long,
        sourcePid: Long?,
    ): Boolean {
        val deadline = System.currentTimeMillis() + RECOVERY_POSTCHECK_TIMEOUT_MS
        do {
            if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return false
            val liveWindow = resolveLiveGameWindow()
            if (liveWindow != null) {
                val capture = captureScreen(liveWindow, allowCachedGameRect = false)
                if (capture != null) {
                    val observed = detect(runOCR(capture), capture.visual)
                    val mode = Mode.currMode
                    val confirmed = when (detection.kind) {
                        ScreenKind.HOME -> observed?.kind == ScreenKind.HOME && mode == ModeEnum.HUB
                        ScreenKind.HOME_TASK_OVERLAY ->
                            (observed?.kind == ScreenKind.HOME && mode == ModeEnum.HUB) ||
                                (observed?.kind in setOf(
                                    ScreenKind.TOURNAMENT, ScreenKind.DECK_SELECTION, ScreenKind.MATCHMAKING,
                                ) && mode == ModeEnum.TOURNAMENT) || WarEx.inWar
                        ScreenKind.TOURNAMENT -> observed?.kind in setOf(
                            ScreenKind.TOURNAMENT, ScreenKind.DECK_SELECTION, ScreenKind.MATCHMAKING,
                        ) && mode == ModeEnum.TOURNAMENT
                        ScreenKind.DECK_SELECTION -> observed?.kind == ScreenKind.MATCHMAKING || WarEx.inWar
                        ScreenKind.MATCHMAKING -> observed?.kind == ScreenKind.MATCHMAKING && mode == ModeEnum.TOURNAMENT
                        ScreenKind.SHOP_OVERLAY -> observed?.kind != ScreenKind.SHOP_OVERLAY && mode == ModeEnum.HUB
                        ScreenKind.LOADING -> observed?.kind == ScreenKind.LOADING && mode == ModeEnum.STARTUP
                        ScreenKind.RECONNECT -> observed?.kind != ScreenKind.RECONNECT && mode == ModeEnum.LOGIN
                        ScreenKind.RECONNECT_SPINNER -> observed?.kind != null &&
                            observed.kind != ScreenKind.RECONNECT_SPINNER && mode != ModeEnum.STARTUP
                        ScreenKind.RECONNECT_FAILURE -> observed?.kind != null &&
                            observed.kind != ScreenKind.RECONNECT_FAILURE &&
                            GameUtil.findGameProcessIdForDiagnostics()?.let { it != sourcePid } == true
                        ScreenKind.RESULT -> observed?.kind != ScreenKind.RESULT || GameUtil.isTerminalGameState()
                        else -> observed?.kind == detection.kind && mode == detection.mode
                    }
                    log.info {
                        "SCREEN_RECOVERY_POSTCHECK_OBSERVATION expected=${detection.kind.code} " +
                            "observed=${observed?.kind?.code ?: "UNKNOWN"} mode=${mode?.name ?: "NONE"} " +
                            "pid=${GameUtil.findGameProcessIdForDiagnostics() ?: "none"} hwnd=$liveWindow " +
                            "screenshot=${capture.file?.absolutePath ?: "not-saved"} confirmed=$confirmed"
                    }
                    if (confirmed) return true
                }
            }
            if (System.currentTimeMillis() < deadline) Thread.sleep(RECOVERY_POSTCHECK_POLL_MS)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun scheduleHomeTaskOverlayRecovery(
        recoveryToken: Long,
        dismissDispatches: Int,
        probeAttempts: Int,
        delayMs: Long,
    ) {
        val task = EXTRA_THREAD_POOL.schedule({
            if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return@schedule
            if ((!WorkTimeListener.working && !PauseStatus.isAutomaticPause) ||
                !PauseStatus.canRunAutomaticRecovery() ||
                PauseStatus.pauseOrigin == PauseStatus.Origin.MANUAL ||
                WarEx.inWar || Mode.currMode != ModeEnum.HUB
            ) {
                log.info {
                    "SCREEN_RECOVERY_HOME_TASK_OVERLAY_SKIPPED reason=state-changed " +
                        "working=${WorkTimeListener.working} pauseOrigin=${PauseStatus.pauseOrigin} " +
                        "mode=${Mode.currMode?.name ?: "NONE"} inWar=${WarEx.inWar}"
                }
                return@schedule
            }
            if (probeAttempts >= STARTUP_QUEST_OVERLAY_MAX_PROBES) {
                PauseStatus.setAutomaticPause(true)
                log.error {
                    "SCREEN_RECOVERY_HOME_TASK_OVERLAY_PAUSED reason=probe-limit " +
                        "probes=$probeAttempts dispatches=$dismissDispatches action=NO_INPUT"
                }
                return@schedule
            }

            val sourcePid = GameUtil.findGameProcessIdForDiagnostics()
            val hwnd = resolveLiveGameWindow()
            if (sourcePid == null || hwnd == null) {
                PauseStatus.setAutomaticPause(true)
                log.warn { "SCREEN_RECOVERY_HOME_TASK_OVERLAY_BLOCKED reason=game-window-unavailable action=PAUSE" }
                return@schedule
            }
            val frameResult = MouseUtil.withRecoveryForeground(hwnd) {
                val currentWindow = resolveLiveGameWindow()
                val currentPid = GameUtil.findGameProcessIdForDiagnostics()
                if (currentWindow == null || currentWindow.toString() != hwnd.toString() || currentPid != sourcePid) {
                    null
                } else {
                    captureScreen(currentWindow, allowCachedGameRect = false)
                }
            }
            val capture = frameResult.value
            val captureTrusted = frameResult.foregroundConfirmed && capture != null &&
                GameUtil.findGameProcessIdForDiagnostics() == sourcePid &&
                GameUtil.isVerifiedCurrentGameWindow(hwnd)
            if (!captureTrusted) {
                PauseStatus.setAutomaticPause(true)
                log.warn {
                    "SCREEN_RECOVERY_HOME_TASK_OVERLAY_BLOCKED reason=trusted-capture-unavailable " +
                        "foregroundConfirmed=${frameResult.foregroundConfirmed} capture=${capture != null} " +
                        "probe=${probeAttempts + 1} action=PAUSE"
                }
                return@schedule
            }

            val freshCapture = requireNotNull(capture)
            val evidence = runOCR(freshCapture)
            val captureStillTrusted = GameUtil.findGameProcessIdForDiagnostics() == sourcePid &&
                GameUtil.isVerifiedCurrentGameWindow(hwnd)
            val detection = if (captureStillTrusted) detect(evidence, freshCapture.visual) else null
            val observation = when (detection?.kind) {
                ScreenKind.HOME_TASK_OVERLAY -> StartupQuestOverlayPolicy.Observation.QUEST_OVERLAY
                ScreenKind.HOME -> StartupQuestOverlayPolicy.Observation.HUB
                else -> StartupQuestOverlayPolicy.Observation.UNKNOWN
            }
            val action = StartupQuestOverlayPolicy.decide(
                observation = observation,
                captureTrusted = captureStillTrusted,
                dismissDispatches = dismissDispatches,
            )
            log.info {
                "SCREEN_RECOVERY_HOME_TASK_OVERLAY_PROBE observation=$observation " +
                    "detected=${detection?.kind?.code ?: "UNKNOWN"} confidence=${detection?.confidence ?: 0} " +
                    "action=$action dismissDispatches=$dismissDispatches probe=${probeAttempts + 1} " +
                    "pid=$sourcePid hwnd=$hwnd screenshot=${freshCapture.file?.absolutePath ?: "none"}"
            }
            when (action) {
                StartupQuestOverlayPolicy.Action.DISMISS_OVERLAY -> {
                    val accepted = MouseUtil.leftButtonClickForRecovery(
                        HubModeStrategy.HIDE_TASK_RECT.getCenterClickPos(),
                    )
                    log.warn {
                        "SCREEN_RECOVERY_HOME_TASK_OVERLAY_DISMISS dispatched=$accepted " +
                            "uiAccepted=unverified attempt=${dismissDispatches + 1}"
                    }
                    scheduleHomeTaskOverlayRecovery(
                        recoveryToken,
                        dismissDispatches + if (accepted) 1 else 0,
                        probeAttempts + 1,
                        900L,
                    )
                }
                StartupQuestOverlayPolicy.Action.ENTER_HUB -> {
                    if (PauseStatus.resumeAutomaticPause("verified-hub-after-quest-overlay")) {
                        log.warn { "SCREEN_RECOVERY_AUTO_RESUME reason=verified-hub-after-quest-overlay" }
                    }
                    Mode.recover(ModeEnum.HUB, "verified-hub-after-quest-overlay", enterStrategy = true)
                    log.warn { "SCREEN_RECOVERY_HOME_TASK_OVERLAY_CONFIRMED state=HUB strategyStarted=true" }
                }
                StartupQuestOverlayPolicy.Action.WAIT_FOR_TRUSTED_CAPTURE -> {
                    log.warn { "SCREEN_RECOVERY_HOME_TASK_OVERLAY_WAIT reason=screen-unclassified action=NO_INPUT" }
                    scheduleHomeTaskOverlayRecovery(recoveryToken, dismissDispatches, probeAttempts + 1, 900L)
                }
                StartupQuestOverlayPolicy.Action.BLOCK_UNTRUSTED -> {
                    PauseStatus.setAutomaticPause(true)
                    log.warn { "SCREEN_RECOVERY_HOME_TASK_OVERLAY_BLOCKED reason=provenance-changed action=PAUSE" }
                }
                StartupQuestOverlayPolicy.Action.EXHAUSTED -> {
                    PauseStatus.setAutomaticPause(true)
                    log.error {
                        "SCREEN_RECOVERY_HOME_TASK_OVERLAY_EXHAUSTED dismissDispatches=$dismissDispatches " +
                            "state=HUB strategyStarted=false action=PAUSE"
                    }
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
        ScreenRecoveryRuntime.track(recoveryToken, task)
    }

    private fun apply(detection: Detection, recoveryToken: Long): Boolean {
        if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return false
        // A strongly identified cannot-reconnect modal is authoritative over a
        // stale in-game flag: that match can no longer be resumed in-place.
        if (WarEx.inWar && detection.kind != ScreenKind.RECONNECT_FAILURE) {
            log.warn { "SCREEN_RECOVERY_SKIPPED reason=war-started detected=${detection.kind.code}" }
            return false
        }
        if (detection.kind in setOf(
                ScreenKind.HOME,
                ScreenKind.TOURNAMENT,
                ScreenKind.DECK_SELECTION,
                ScreenKind.HOME_TASK_OVERLAY,
                ScreenKind.MATCHMAKING,
                ScreenKind.RESULT,
                ScreenKind.GAME_MODE,
                ScreenKind.COLLECTION,
                ScreenKind.PACK_OPENING,
                ScreenKind.SHOP_OVERLAY,
            )
        ) {
            reconnectFailureRecoveryPolicy.onStartupConfirmed()
        }
        if (detection.kind != ScreenKind.LOADING) {
            slowReconnectWarningObservedAt.set(0L)
            loadingObservedAt.set(0L)
        }

        when (detection.kind) {
            ScreenKind.HOME_TASK_OVERLAY -> {
                Mode.recover(ModeEnum.HUB, "visible-home-task-overlay", enterStrategy = false)
                log.warn { "SCREEN_RECOVERY_ACTION_REQUESTED screen=HOME_TASK_OVERLAY action=PROBE_THEN_DISMISS" }
                scheduleHomeTaskOverlayRecovery(
                    recoveryToken = recoveryToken,
                    dismissDispatches = 0,
                    probeAttempts = 0,
                    delayMs = 300L,
                )
            }
            ScreenKind.DECK_SELECTION -> {
                if (DeckStrategyManager.currentDeckStrategy == null ||
                    DeckStrategyManager.currentRunMode == null
                ) {
                    log.warn { "SCREEN_RECOVERY_DECK_SELECTION_SKIPPED reason=no-selected-strategy" }
                    return false
                }
                Mode.recover(ModeEnum.TOURNAMENT, "visible-deck-selection", enterStrategy = false)
                log.warn {
                    "SCREEN_RECOVERY_ACTION_REQUESTED screen=DECK_SELECTION next=START_MATCHING " +
                        "deck=${DeckStrategyManager.currentDeckStrategy?.name()} " +
                        "deckSlot=${DeckStrategyManager.currentDeckStrategy?.let(TournamentModeStrategy::expectedDeckSlot)}"
                }
                EXTRA_THREAD_POOL.schedule({
                        if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return@schedule
                        if (LifecycleTrace.recoveryCascadeSuppressed()) {
                            log.info { "SCREEN_RECOVERY_DECK_SELECTION_SKIPPED reason=terminal-pause-fence" }
                        } else if ((WorkTimeListener.working || PauseStatus.isAutomaticPause) &&
                            PauseStatus.canRunAutomaticRecovery() && !WarEx.inWar
                    ) {
                        // A known deck-selection screen is an actionable recovery
                        // result.  An earlier automatic safety pause (for example,
                        // a transient mode-title OCR miss) must not leave the
                        // recovery clicks behind ActionDispatchGate.  Never clear
                        // a manual F2/UI pause here.
                        if (PauseStatus.resumeAutomaticPause("deck-selection-recovery")) {
                            log.warn { "SCREEN_RECOVERY_AUTO_RESUME reason=deck-selection-recovery" }
                        }
                        TournamentModeStrategy.recoverDeckSelectionAndStart()
                    }
                }, 300, TimeUnit.MILLISECONDS)
            }

            ScreenKind.RESULT -> {
                Mode.recover(ModeEnum.GAMEPLAY, "visible-result-screen", enterStrategy = false)
                log.warn { "SCREEN_RECOVERY_ACTION_REQUESTED screen=RESULT next=DISMISS_STALE_RESULT" }
                val cleanupCapability = club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
                    .authorizeTerminalCleanup("SCREEN_TERMINAL")
                GameUtil.dismissStaleGameEndScreen(
                    resultAlreadyObserved = true,
                    terminalCleanupCapability = cleanupCapability,
                )
            }

            ScreenKind.MATCHMAKING -> {
                Mode.recover(ModeEnum.TOURNAMENT, "visible-matchmaking-screen", enterStrategy = false)
                log.info { "SCREEN_RECOVERY_ACTION_REQUESTED screen=MATCHMAKING action=WAIT_FOR_GAMEPLAY" }
            }

            ScreenKind.SHOP_OVERLAY -> {
                if (Mode.currMode != ModeEnum.HUB) {
                    log.warn {
                        "SCREEN_RECOVERY_SHOP_OVERLAY_SKIPPED reason=mode-not-hub " +
                            "mode=${Mode.currMode?.name ?: "NONE"}"
                    }
                    return false
                }
                // The overlay obscures the tournament entry button, so stop
                // Hub polling before sending the close click.
                Mode.recover(ModeEnum.HUB, "visible-shop-overlay", enterStrategy = false)
                EXTRA_THREAD_POOL.schedule({
                    if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return@schedule
                    if (WorkTimeListener.working && !PauseStatus.isPause && !WarEx.inWar &&
                        Mode.currMode == ModeEnum.HUB
                    ) {
                        val accepted = HubModeStrategy.closeShopOverlayForRecovery()
                        log.warn {
                            "SCREEN_RECOVERY_ACTION_REQUESTED screen=SHOP_OVERLAY " +
                                "action=CLOSE_OVERLAY accepted=$accepted"
                        }
                        if (accepted) {
                            EXTRA_THREAD_POOL.schedule({
                                if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return@schedule
                                if (WorkTimeListener.working && !PauseStatus.isPause &&
                                    !WarEx.inWar && Mode.currMode == ModeEnum.HUB
                                ) {
                                    Mode.recover(
                                        ModeEnum.HUB,
                                        "shop-overlay-close-dispatched",
                                        enterStrategy = true,
                                    )
                                }
                            }, 800, TimeUnit.MILLISECONDS)
                        }
                    } else {
                        log.info { "SCREEN_RECOVERY_SHOP_OVERLAY_CLOSE_SKIPPED reason=state-changed" }
                    }
                }, 300, TimeUnit.MILLISECONDS)
            }

            ScreenKind.RECONNECT -> {
                Mode.recover(ModeEnum.LOGIN, "visible-reconnect-screen", enterStrategy = false)
                val now = System.currentTimeMillis()
                val reconnectDecision = offlineReconnectRecovery.observe(
                    OfflineReconnectRecovery.Screen.OFFLINE_PROMPT,
                    now,
                )
                log.warn {
                    "SCREEN_RECOVERY_RECONNECT_STATE state=${reconnectDecision.state} " +
                        "action=${reconnectDecision.action} attempt=${reconnectDecision.attempt} " +
                        "reason=${reconnectDecision.reason} result=prompt-detected"
                }
                val acceptedReconnectAt = reconnectAcceptedAt.get()
                if (acceptedReconnectAt > 0L &&
                    shouldRestartStalledReconnect(acceptedReconnectAt, now) &&
                    reconnectAcceptedAt.compareAndSet(acceptedReconnectAt, 0L)
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_ACTION_REQUESTED screen=RECONNECT action=RESTART_CLIENT " +
                            "reason=stalled-reconnect elapsedMs=${now - acceptedReconnectAt} " +
                            "thresholdMs=$STALLED_RECONNECT_LOADING_RESTART_MS"
                    }
                    // Some client builds leave the original disconnect dialog
                    // visible after consuming the reconnect click.  Do not
                    // wait for the optional slow-loading warning: once the
                    // confirmed reconnect attempt has been stuck for the
                    // recovery threshold, restart the client directly.
                    if (ScreenRecoveryRuntime.isCurrent(recoveryToken)) Core.restart()
                } else if (acceptedReconnectAt <= 0L &&
                    reconnectDecision.action == OfflineReconnectRecovery.Action.FOCUS_AND_CLICK_RECONNECT &&
                    shouldAttemptReconnect(now)
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_ACTION_REQUESTED screen=RECONNECT mode=LOGIN " +
                            "action=CLICK_RECONNECT retryIntervalMs=$RECONNECT_RETRY_INTERVAL_MS"
                    }
                    EXTRA_THREAD_POOL.schedule({
                        if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return@schedule
                        if (LifecycleTrace.recoveryCascadeSuppressed()) {
                            log.info { "SCREEN_RECOVERY_RECONNECT_SKIPPED reason=terminal-pause-fence" }
                        } else if ((WorkTimeListener.working || PauseStatus.isAutomaticPause) &&
                            PauseStatus.canRunAutomaticRecovery() &&
                            !WarEx.inWar && Mode.currMode == ModeEnum.LOGIN
                        ) {
                            // This is the upstream reconnect input primitive;
                            // skip the pre-click right-click because the
                            // offline page is not a card/targeting state.
                            ScriptStatus.gameHWND?.let(MouseUtil::focusWindowForInput)
                            val accepted = MouseUtil.leftButtonClickForRecovery(
                                GameUtil.RECONNECT_RECT.getCenterClickPos(),
                            )
                            val result = offlineReconnectRecovery.reportReconnectDispatched(
                                System.currentTimeMillis(),
                                accepted,
                            )
                            log.info {
                                "SCREEN_RECOVERY_RECONNECT_DISPATCHED input=recovery-sendinput " +
                                    "accepted=$accepted state=${result.state} action=${result.action}"
                            }
                            if (accepted) {
                                reconnectAcceptedAt.set(System.currentTimeMillis())
                                scheduleReconnectSpinnerProbe(recoveryToken)
                                if (PauseStatus.resumeAutomaticPause("reconnect-click-accepted")) {
                                    log.warn {
                                        "SCREEN_RECOVERY_AUTO_RESUME reason=reconnect-click-accepted"
                                    }
                                }
                            }
                        } else {
                            log.info { "SCREEN_RECOVERY_RECONNECT_SKIPPED reason=state-changed" }
                        }
                    }, 300, TimeUnit.MILLISECONDS)
                } else {
                    log.info {
                        "SCREEN_RECOVERY_RECONNECT_THROTTLED " +
                            "retryIntervalMs=$RECONNECT_RETRY_INTERVAL_MS"
                    }
                }
            }

            ScreenKind.RECONNECT_FAILURE -> {
                // This dialog explicitly says the game cannot reconnect. It
                // is not a dismiss-and-continue state: hard-restart the client
                // through the established Core path, then let GameStarter do
                // the normal process handoff and bounded startup screen probe.
                Mode.recover(ModeEnum.LOGIN, "visible-reconnect-failure", enterStrategy = false)
                EXTRA_THREAD_POOL.schedule({
                    if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return@schedule
                    if (LifecycleTrace.recoveryCascadeSuppressed()) {
                        log.info { "SCREEN_RECOVERY_RECONNECT_FAILURE_SKIPPED reason=terminal-pause-fence" }
                    } else if ((WorkTimeListener.working || PauseStatus.isAutomaticPause) &&
                        PauseStatus.canRunAutomaticRecovery()
                    ) {
                        val recovery = reconnectFailureRecoveryPolicy.observeFailure(System.currentTimeMillis())
                        when (recovery.decision) {
                            ReconnectFailureRecoveryPolicy.Decision.RESTART_CLIENT -> {
                                log.warn {
                                    "SCREEN_RECOVERY_ACTION_REQUESTED screen=RECONNECT_FAILURE action=RESTART_CLIENT " +
                                        "reason=cannot-reconnect-dialog attempt=${recovery.attempt} " +
                                        "maxAttempts=${ReconnectFailureRecoveryPolicy.DEFAULT_MAX_RESTARTS}"
                                }
                                if (ScreenRecoveryRuntime.isCurrent(recoveryToken)) Core.restart()
                            }
                            ReconnectFailureRecoveryPolicy.Decision.WAIT_FOR_RESTART -> log.info {
                                "SCREEN_RECOVERY_RECONNECT_FAILURE_WAIT reason=restart-cooldown " +
                                    "attempt=${recovery.attempt} " +
                                    "cooldownMs=${ReconnectFailureRecoveryPolicy.DEFAULT_RETRY_COOLDOWN_MS}"
                            }
                            ReconnectFailureRecoveryPolicy.Decision.PAUSE_AUTOMATION -> {
                                PauseStatus.setAutomaticPause(true)
                                log.error {
                                    "SCREEN_RECOVERY_RECONNECT_FAILURE_PAUSED " +
                                        "reason=restart-attempts-exhausted " +
                                        "attempts=${recovery.attempt} " +
                                        "windowMs=${ReconnectFailureRecoveryPolicy.DEFAULT_ATTEMPT_WINDOW_MS}"
                                }
                            }
                        }
                    } else {
                        log.info { "SCREEN_RECOVERY_RECONNECT_FAILURE_SKIPPED reason=state-changed" }
                    }
                }, 300, TimeUnit.MILLISECONDS)
            }

            ScreenKind.RECONNECT_SPINNER -> {
                Mode.recover(ModeEnum.STARTUP, "visible-reconnect-spinner", enterStrategy = false)
                val now = System.currentTimeMillis()
                val decision = offlineReconnectRecovery.observe(
                    OfflineReconnectRecovery.Screen.RECONNECT_SPINNER,
                    now,
                )
                log.warn {
                    "SCREEN_RECOVERY_RECONNECT_STATE state=${decision.state} action=${decision.action} " +
                        "attempt=${decision.attempt} reason=${decision.reason} result=spinner-detected"
                }
                when (decision.action) {
                    OfflineReconnectRecovery.Action.FOCUS_AND_CLICK_CANCEL -> EXTRA_THREAD_POOL.schedule({
                        if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return@schedule
                        if (LifecycleTrace.recoveryCascadeSuppressed()) {
                            log.info { "SCREEN_RECOVERY_RECONNECT_CANCEL_SKIPPED reason=terminal-pause-fence" }
                        } else if ((WorkTimeListener.working || PauseStatus.isAutomaticPause) &&
                            PauseStatus.canRunAutomaticRecovery() && !WarEx.inWar
                        ) {
                            ScriptStatus.gameHWND?.let(MouseUtil::focusWindowForInput)
                            val accepted = MouseUtil.leftButtonClickForRecovery(
                                GameUtil.CANCEL_CONNECT_RECT.getCenterClickPos(),
                            )
                            val result = offlineReconnectRecovery.reportCancelDispatched(
                                System.currentTimeMillis(),
                                accepted,
                            )
                            log.warn {
                                "SCREEN_RECOVERY_RECONNECT_CANCEL_DISPATCHED accepted=$accepted " +
                                    "state=${result.state} action=${result.action}"
                            }
                            if (accepted) scheduleReconnectSpinnerProbe(recoveryToken)
                            if (result.action == OfflineReconnectRecovery.Action.ESCALATE) {
                if (ScreenRecoveryRuntime.isCurrent(recoveryToken)) PauseStatus.isPause = true
                            }
                        } else {
                            log.info { "SCREEN_RECOVERY_RECONNECT_CANCEL_SKIPPED reason=state-changed" }
                        }
                    }, 300, TimeUnit.MILLISECONDS)

                    OfflineReconnectRecovery.Action.ESCALATE -> {
                        log.error {
                            "SCREEN_RECOVERY_RECONNECT_ESCALATED state=${decision.state} " +
                                "reason=${decision.reason} result=spinner-still-visible"
                        }
                        if (ScreenRecoveryRuntime.isCurrent(recoveryToken)) PauseStatus.isPause = true
                    }

                    else -> Unit
                }
            }

            ScreenKind.LOADING -> {
                Mode.recover(ModeEnum.STARTUP, "visible-loading-screen", enterStrategy = false)
                val now = System.currentTimeMillis()
                val loadingStartedAt = firstLoadingObservedAt(now)
                val acceptedReconnectAt = reconnectAcceptedAt.get()
                val observedWarningAt = if (acceptedReconnectAt <= 0L &&
                    detection.evidence == "reconnect-slow-loading-text"
                ) {
                    firstSlowReconnectWarningAt(now)
                } else 0L
                val reconnectStartedAt = stalledReconnectAnchor(
                    acceptedReconnectAt,
                    observedWarningAt,
                    now,
                )
                val slowReconnectStalled = detection.evidence == "reconnect-slow-loading-text" &&
                    shouldRestartStalledReconnect(reconnectStartedAt, now) &&
                    consumeStalledReconnectAnchor(acceptedReconnectAt, reconnectStartedAt)
                val genericLoadingStalled = !slowReconnectStalled &&
                    shouldRestartStalledLoading(loadingStartedAt, now) &&
                    consumeStalledLoadingAnchor(loadingStartedAt)
                if (slowReconnectStalled || genericLoadingStalled) {
                    log.warn {
                        "SCREEN_RECOVERY_ACTION_REQUESTED screen=LOADING action=RESTART_CLIENT " +
                            "reason=${if (slowReconnectStalled) "stalled-reconnect-loading" else "stale-loading"} " +
                            "elapsedMs=${now - if (slowReconnectStalled) reconnectStartedAt else loadingStartedAt} " +
                            "thresholdMs=$STALLED_RECONNECT_LOADING_RESTART_MS"
                    }
                    // Reuse the fatal-error recovery primitive. It pauses
                    // input, launches a fresh client, then lets the starter
                    // rediscover that window. This also covers a reconnect
                    // dialog whose text OCR was missed and was classified
                    // only by the loading visual.
                    if (ScreenRecoveryRuntime.isCurrent(recoveryToken)) Core.restart()
                } else {
                    log.info {
                        "SCREEN_RECOVERY_ACTION_REQUESTED screen=LOADING action=WAIT_FOR_CLIENT " +
                            "loadingObservedForMs=${now - loadingStartedAt} " +
                            "restartThresholdMs=$STALLED_RECONNECT_LOADING_RESTART_MS"
                    }
                }
            }

            else -> {
                if (detection.kind == ScreenKind.HOME ||
                    detection.kind == ScreenKind.TOURNAMENT ||
                    detection.kind == ScreenKind.DECK_SELECTION
                ) {
                    val reconnectState = offlineReconnectRecovery.observe(
                        OfflineReconnectRecovery.Screen.CONNECTED,
                        System.currentTimeMillis(),
                    )
                    if (reconnectState.action == OfflineReconnectRecovery.Action.MARK_RECONNECTED) {
                        reconnectAcceptedAt.set(0L)
                        log.info {
                            "SCREEN_RECOVERY_RECONNECT_STATE state=${reconnectState.state} " +
                                "action=${reconnectState.action} reason=${reconnectState.reason} result=connected"
                        }
                    }
                }
                Mode.recover(detection.mode, "visible-${detection.kind.code.lowercase(Locale.ROOT)}", enterStrategy = true)
                log.warn {
                    "SCREEN_RECOVERY_ACTION_REQUESTED screen=${detection.kind.code} mode=${detection.mode.name} " +
                        "action=ENTER_MODE_STRATEGY"
                }
            }
        }
        return true
    }
}
