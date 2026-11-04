package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.consts.CHI_SIM_DATA
import club.xiaojiawei.hsscript.consts.GAME_WAR_LOG_NAME
import club.xiaojiawei.hsscript.enums.GameLogModeEnum
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
import club.xiaojiawei.hsscriptbase.const.BuildChannel
import club.xiaojiawei.hsscriptbase.const.BuildInfo
import com.sun.jna.platform.win32.WinDef
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import java.awt.Rectangle
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
        val perceptualFingerprint: Long,
        val currentSessionReady: Boolean,
        val noActiveMatchmakingEvidence: Boolean,
    )

    /** Use the configured PaddleX deadline so cold pipeline initialization isn't cut off at 4 seconds. */
    internal fun matchmakingDialogOcrTimeoutMs(): Long = OcrRuntime.paddleXRequestTimeoutMs()

    private const val MAX_OCR_TEXT_LENGTH = 500
    private const val OCR_MAX_WIDTH = 1280
    private const val RECONNECT_RETRY_INTERVAL_MS = 60_000L
    private const val RECOVERY_POSTCHECK_TIMEOUT_MS = 3_000L
    private const val RECOVERY_POSTCHECK_POLL_MS = 300L
    private const val STARTUP_QUEST_OVERLAY_MAX_PROBES = StartupQuestOverlayRecoveryFence.MAX_REOBSERVATIONS
    private const val RECONNECT_SPINNER_CHECK_DELAY_MS = 10_000L
    /**
     * The client displays a distinct slow-connection warning only after a
     * reconnect attempt. A fresh script can attach after that click, so its
     * first observation of the same exact warning also starts this timer.
     * Give it one minute before using the established client restart path.
     */
    private const val STALLED_RECONNECT_RESTART_MS = IndefiniteReconnectRestartPolicy.DEFAULT_THRESHOLD_MS
    private const val STALLED_LOADING_RESTART_MS = 120_000L
    private val reconnectAttemptAt = AtomicLong(0L)
    private val reconnectAcceptedAt = AtomicLong(0L)
    private val reconnectProbeGeneration = AtomicLong(0L)
    private val offlineReconnectRecovery = OfflineReconnectRecovery()
    private val indefiniteReconnectRestartPolicy = IndefiniteReconnectRestartPolicy()
    private val reconnectFailureRecoveryPolicy = ReconnectFailureRecoveryPolicy()
    private val slowReconnectWarningObservedAt = AtomicLong(0L)
    /**
     * A visual loading signal can be the offline/reconnect dialog even when
     * OCR misses the dialog text.  Keep an independent anchor for that generic
     * branch so a stale client cannot wait forever on WAIT_FOR_CLIENT.
     */
    private val loadingObservedAt = AtomicLong(0L)
    private val startGameErrorProbeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val startupMenuReprobeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val startupMenuReprobePending = java.util.concurrent.atomic.AtomicBoolean(false)

    private enum class ScreenKind(val code: String) {
        DECK_SELECTION("DECK_SELECTION"),
        HOME("HOME"),
        HOME_TASK_OVERLAY("HOME_TASK_OVERLAY"),
        TOURNAMENT("TOURNAMENT"),
        MATCHMAKING("MATCHMAKING"),
        RESULT("RESULT"),
        RANK_PROGRESS_CONTINUATION("RANK_PROGRESS_CONTINUATION"),
        RECONNECT("RECONNECT"),
        RECONNECT_FAILURE("RECONNECT_FAILURE"),
        RECONNECT_SPINNER("RECONNECT_SPINNER"),
        SERVER_UNAVAILABLE("SERVER_UNAVAILABLE"),
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
        val purpose: ScreenRecoveryCapturePurpose,
        val evidence: ScreenRecoveryCaptureEvidence,
        val capturedAtNanos: Long,
        val resultProbeTimings: ResultProbeCaptureTimings = ResultProbeCaptureTimings(),
    )

    private data class OcrEvidence(
        val text: String,
        val targeted: Map<String, String>,
        val resultProbeTimings: ResultProbeOcrTimings = ResultProbeOcrTimings(),
    )

    private data class VisualSignature(
        val sampleHash: Long,
        val warmRatio: Double,
        val blueRatio: Double,
        val loadingCentralDarkRatio: Double,
        val resultContinueGrayLightRatio: Double,
        val resultBannerLowSaturationRatio: Double,
        val resultBannerWarmRatio: Double = 0.0,
        val centeredModalPanelVisual: Boolean = false,
        val startupQuestOverlayVisual: Boolean = false,
        val reconnectDialogVisual: Boolean = false,
        val matchmakingSearchVisual: Boolean = false,
        val battleNetLauncherVisual: Boolean = false,
    ) {
        override fun toString(): String =
            "hash=${java.lang.Long.toUnsignedString(sampleHash, 16)} " +
                "warmRatio=${"%.3f".format(Locale.ROOT, warmRatio)} " +
                "blueRatio=${"%.3f".format(Locale.ROOT, blueRatio)} " +
                "loadingCentralDark=${"%.3f".format(Locale.ROOT, loadingCentralDarkRatio)} " +
                "resultContinueGrayLight=${"%.3f".format(Locale.ROOT, resultContinueGrayLightRatio)} " +
                "resultBannerLowSaturation=${"%.3f".format(Locale.ROOT, resultBannerLowSaturationRatio)} " +
                "resultBannerWarm=${"%.3f".format(Locale.ROOT, resultBannerWarmRatio)} " +
                "centeredModalPanel=$centeredModalPanelVisual " +
                "reconnectDialogVisual=$reconnectDialogVisual " +
                "matchmakingSearchVisual=$matchmakingSearchVisual " +
                "battleNetLauncherVisual=$battleNetLauncherVisual"
    }

    private data class RegionSignal(
        val grayLightRatio: Double,
        val lowSaturationRatio: Double,
        val darkRatio: Double,
        val warmRatio: Double,
        val blueRatio: Double = 0.0,
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
    internal fun observeFreshScreenForWatchdog(stuckForMs: Long = 0L): FreshScreenObservation? {
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
                val currentSessionReady = currentSessionModelReady()
                val purpose = if (
                    !currentSessionReady &&
                    stuckForMs >= ScreenRecoveryCapturePurposePolicy.IDLE_CLIENT_PROBE_AFTER_MS
                ) {
                    ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE
                } else {
                    stateRecoveryCapturePurpose(stuckForMs)
                }
                captureScreen(currentWindow, purpose = purpose, staleForMs = stuckForMs)
            }
        }
        if (!ScreenRecoveryRuntime.isCurrent(recoveryToken) || !captureResult.foregroundConfirmed) return null
        val capture = captureResult.value ?: return null
        if (GameUtil.findGameProcessIdForDiagnostics() != initialPid ||
            !GameUtil.isVerifiedCurrentGameWindow(gameWindow)
        ) return null
        val ocrEvidence = runOCR(capture)
        val detection = detect(ocrEvidence, capture.visual)?.takeIf { it.confidence >= 85 }
        val currentSessionReady = capture.evidence.currentSessionReady
        val queueVisible = capture.visual.matchmakingSearchVisual || looksLikeMatchmakingText(ocrEvidence.text)
        val queueLifecycleActive = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
            LifecycleTrace.hasRecentMatchmakingInputIntent()
        val noActiveMatchmakingEvidence = !queueVisible && !queueLifecycleActive
        val observation = FreshScreenObservation(
            screen = detection?.kind?.code ?: "UNKNOWN",
            confidence = detection?.confidence ?: 0,
            pid = initialPid,
            hwnd = gameWindow.toString(),
            screenshot = capture.file?.absolutePath,
            perceptualFingerprint = perceptualFingerprint(capture.image),
            currentSessionReady = currentSessionReady,
            noActiveMatchmakingEvidence = noActiveMatchmakingEvidence,
        )
        log.info {
            "SCREEN_RECOVERY_FRESH_OBSERVATION detected=${observation.screen} " +
                "confidence=${observation.confidence} pid=${observation.pid} hwnd=${observation.hwnd} " +
                "perceptualFingerprint=${java.lang.Long.toUnsignedString(observation.perceptualFingerprint, 16)} " +
                "currentSessionReady=${observation.currentSessionReady} " +
                "noActiveMatchmakingEvidence=${observation.noActiveMatchmakingEvidence} " +
                "screenshot=${observation.screenshot ?: "not-saved"}"
        }
        return observation
    }

    private fun perceptualFingerprint(image: BufferedImage): Long {
        var fingerprint = 0L
        var bit = 0
        for (row in 0 until 8) {
            val y = ((row + 0.5) * image.height / 8.0).toInt().coerceIn(0, image.height - 1)
            for (column in 0 until 8) {
                val leftX = ((column + 0.5) * image.width / 9.0).toInt().coerceIn(0, image.width - 1)
                val rightX = ((column + 1.5) * image.width / 9.0).toInt().coerceIn(0, image.width - 1)
                val left = image.getRGB(leftX, y)
                val right = image.getRGB(rightX, y)
                val leftLuma = 299 * (left shr 16 and 0xff) + 587 * (left shr 8 and 0xff) + 114 * (left and 0xff)
                val rightLuma = 299 * (right shr 16 and 0xff) + 587 * (right shr 8 and 0xff) + 114 * (right and 0xff)
                if (leftLuma > rightLuma) fingerprint = fingerprint or (1L shl bit)
                bit++
            }
        }
        return fingerprint
    }

    /** Read-only process/log boundary used to distinguish an explicit rearm from a healthy heartbeat. */
    internal fun isCurrentGameSessionReadyForStart(): Boolean = currentSessionModelReady()

    /** Exact empty Power.log lineage for a freshly started Hearthstone process. */
    internal fun hasVerifiedEmptyCurrentSessionPowerLog(): Boolean = runCatching {
        if (ScriptStatus.gameLogMode != GameLogModeEnum.DISK) return@runCatching false
        val pid = GameUtil.findGameProcessIdForDiagnostics() ?: return@runCatching false
        val processStartedAt = GameUtil.findProcessStartedAtForDiagnostics(pid) ?: return@runCatching false
        val latest = GameUtil.getLatestLogDir()?.resolve(GAME_WAR_LOG_NAME) ?: return@runCatching false
        latest.isFile && latest.canRead() && latest.length() == 0L &&
            PowerLogSessionBindingPolicy.isEmptySessionFileForProcess(
                powerLogPath = latest.absolutePath,
                gameLogsRoot = latest.parentFile?.parent,
                length = latest.length(),
                lastModifiedMs = latest.lastModified(),
                processStartedAtMs = processStartedAt,
            )
    }.getOrDefault(false)

    /** A stale WarEx latch is not game evidence when the live process has no current-session log. */
    internal fun hasAuthoritativeCurrentGameEvidenceForReconnect(): Boolean {
        if (!WarEx.inWar) return false
        val pid = GameUtil.findGameProcessIdForDiagnostics() ?: return false
        val startedAt = runCatching { GameUtil.findProcessStartedAtForDiagnostics(pid) }.getOrNull()
            ?: return false
        val attached = PowerLogListener.logFile?.let { File(it.path()) } ?: return false
        val latest = GameUtil.getLatestLogDir()?.resolve(GAME_WAR_LOG_NAME) ?: return false
        val currentSessionPowerLog = runCatching {
            attached.isFile && attached.canRead() && attached.length() > 0L &&
                attached.canonicalFile == latest.canonicalFile &&
                PowerLogSessionBindingPolicy.isCurrentSession(
                    powerLogPath = attached.absolutePath,
                    gameLogsRoot = latest.parentFile?.parent,
                    length = attached.length(),
                    lastModifiedMs = attached.lastModified(),
                    processStartedAtMs = startedAt,
                )
        }.getOrDefault(false)
        return ReconnectActiveGameEvidencePolicy.isAuthoritative(
            warLatch = WarEx.inWar,
            currentSessionPowerLog = currentSessionPowerLog,
        )
    }

    /** Re-observe the same verified client after the single authorized HUB entry click. */
    fun scheduleStartupMenuReprobeAfterModeSelection() {
        scheduleFreshStartupScreenReprobe()
    }

    /** Re-observe the same client after a one-use startup recovery input. */
    fun scheduleFreshStartupScreenReprobe() {
        if (!startupMenuReprobeInFlight.compareAndSet(false, true)) {
            startupMenuReprobePending.set(true)
            return
        }
        EXTRA_THREAD_POOL.schedule({
            try {
                if (!WorkTimeListener.working || PauseStatus.isPause || WarEx.inWar) return@schedule
                inspectAndRecover(
                    stuckForMs = 0L,
                    stateFingerprint = "STARTUP_RECOVERY_POSTCHECK",
                    startupProbe = true,
                )
            } finally {
                startupMenuReprobeInFlight.set(false)
                if (startupMenuReprobePending.getAndSet(false) &&
                    WorkTimeListener.working && !PauseStatus.isPause && !WarEx.inWar
                ) {
                    scheduleFreshStartupScreenReprobe()
                }
            }
        }, STARTUP_MODE_ENTRY_REPROBE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private const val STARTUP_MODE_ENTRY_REPROBE_DELAY_MS = 800L

    /** Optional Beta-specific recovery pipeline; never used for baseline OFF behavior. */
    internal fun inspectBetaAndRecover(
        stuckForMs: Long,
        stateFingerprint: String,
        startupProbe: Boolean = false,
        stateStillCurrent: () -> Boolean = { true },
    ): InspectionResult {
        val recoveryToken = ScreenRecoveryRuntime.tokenOrNull()
            ?: return InspectionResult.DISABLED
        val sessionReadyBeforeCapture = currentSessionModelReady()
        if ((!WorkTimeListener.working && !PauseStatus.isAutomaticPause) ||
            !PauseStatus.canRunAutomaticRecovery() ||
            (WarEx.inWar && sessionReadyBeforeCapture)
        ) {
            log.info {
                "SCREEN_RECOVERY_SKIPPED reason=unsafe " +
                    "working=${WorkTimeListener.working} paused=${PauseStatus.isPause} " +
                    "automaticPause=${PauseStatus.isAutomaticPause} inWar=${WarEx.inWar} " +
                    "currentSessionReady=$sessionReadyBeforeCapture"
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
                captureScreen(
                    ScriptStatus.gameHWND ?: gameWindow,
                    purpose = stateRecoveryCapturePurpose(stuckForMs, startupProbe),
                    staleForMs = stuckForMs,
                )
            }
        } else {
            MouseUtil.RecoveryForegroundResult(
                true,
                captureScreen(
                    gameWindow,
                    purpose = stateRecoveryCapturePurpose(stuckForMs, startupProbe),
                    staleForMs = stuckForMs,
                ),
            )
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
        if (detection?.kind in setOf(
                ScreenKind.RECONNECT,
                ScreenKind.RECONNECT_FAILURE,
                ScreenKind.RECONNECT_SPINNER,
                ScreenKind.SERVER_UNAVAILABLE,
                ScreenKind.LOGIN,
                ScreenKind.LOADING,
            )
        ) {
            StartupInputReadiness.invalidate(
                capture.evidence.target?.processId,
                ScriptStatus.gameHWND?.toString(),
            )
        }
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
        if (capture.purpose == ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE) {
            val captureTarget = capture.evidence.target
            val currentHwnd = ScriptStatus.gameHWND?.toString().orEmpty()
            val cleanOnlineMenu = captureTarget != null && currentHwnd.isNotBlank() && !WarEx.inWar &&
                !capture.visual.centeredModalPanelVisual &&
                ScreenRecoveryCapturePurposePolicy.allowsStartupOnlineMenu(detection.kind.code)
            var readinessRecorded = false
            if (cleanOnlineMenu) {
                val deckModeObservation = if (detection.kind == ScreenKind.DECK_SELECTION) {
                    TournamentModeConfirmation.observeImage(capture.image)
                } else null
                val expectedDeckMode = if (detection.kind == ScreenKind.DECK_SELECTION) {
                    DeckStrategyManager.currentRunMode?.name
                } else null
                readinessRecorded = StartupInputReadiness.recordStartupOnlineScreen(
                    pid = captureTarget!!.processId,
                    hwnd = currentHwnd,
                    screen = detection.kind.code,
                    confidence = detection.confidence,
                    evidence = detection.evidence,
                    expectedRunMode = expectedDeckMode,
                    observedRunMode = deckModeObservation?.observedMode?.name,
                    enabledStartButtonVisible = detection.kind == ScreenKind.DECK_SELECTION &&
                        ScreenWatchdog.hasEnabledDeckSelectionStartButton(capture.image),
                    modalVisible = false,
                )
                log.warn {
                    "STARTUP_INPUT_READINESS_CONFIRMED screen=${detection.kind.code} " +
                        "pid=${captureTarget.processId} hwnd=$currentHwnd " +
                        "evidence=${detection.evidence} recorded=$readinessRecorded " +
                        "expectedRunMode=${expectedDeckMode ?: "n/a"} " +
                        "observedRunMode=${deckModeObservation?.observedMode?.name ?: "n/a"} " +
                        "proof=${if (detection.kind == ScreenKind.DECK_SELECTION) "MATCHMAKING_START_ONLY" else "ONLINE_MENU"}"
                }
                if (detection.kind == ScreenKind.DECK_SELECTION && !readinessRecorded) {
                    TournamentModeConfirmation.reportUnconfirmedDeckSelection(
                        expectedMode = expectedDeckMode,
                        observedMode = deckModeObservation?.observedMode?.name,
                        reason = deckModeObservation?.evidence ?: "fresh-mode-proof-missing",
                    )
                }
            } else {
                StartupInputReadiness.invalidate(captureTarget?.processId, currentHwnd)
            }
            val startupRecoveryScreen = (cleanOnlineMenu &&
                (detection.kind != ScreenKind.DECK_SELECTION || readinessRecorded)) || detection.kind in setOf(
                ScreenKind.RECONNECT,
                ScreenKind.RECONNECT_FAILURE,
                ScreenKind.RECONNECT_SPINNER,
                ScreenKind.SERVER_UNAVAILABLE,
            )
            if (!startupRecoveryScreen) {
                log.warn {
                    "SCREEN_RECOVERY_STARTUP_CLASSIFICATION_ONLY screen=${detection.kind.code} " +
                        "modalVisible=${capture.visual.centeredModalPanelVisual} inWar=${WarEx.inWar} " +
                        "action=NO_INPUT_NO_READINESS"
                }
                return InspectionResult.NO_ACTION
            }
        } else if (capture.purpose == ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE) {
            val staleProbeDecision = ScreenRecoveryCapturePurposePolicy.staleProbeRecoveryDecision(
                purpose = capture.purpose,
                screenKind = detection.kind.code,
                confidence = detection.confidence,
                evidence = detection.evidence,
                freshObservation = true,
                configuredDeck = DeckStrategyManager.currentDeckStrategy != null,
                configuredRunMode = DeckStrategyManager.currentRunMode != null,
                activeGame = WarEx.inWar,
                activeMatchmaking = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
                    LifecycleTrace.hasRecentMatchmakingInputIntent(),
            )
            if (staleProbeDecision == ScreenRecoveryCapturePurposePolicy.StaleProbeRecoveryDecision.CLASSIFICATION_ONLY) {
                log.warn {
                    "SCREEN_RECOVERY_STALE_PROBE_CLASSIFICATION_ONLY screen=${detection.kind.code} " +
                        "action=NO_INPUT reason=stale-probe-policy"
                }
                return InspectionResult.NO_ACTION
            }
        }
        if (capture.purpose == ScreenRecoveryCapturePurpose.STARTUP_YOGG_PROBE &&
            detection.kind != ScreenKind.SERVER_UNAVAILABLE
        ) {
            log.warn {
                "SCREEN_RECOVERY_STARTUP_YOGG_CLASSIFICATION_ONLY screen=${detection.kind.code} " +
                    "action=NO_INPUT reason=purpose-allows-server-unavailable-only"
            }
            return InspectionResult.NO_ACTION
        }
        val sourcePid = GameUtil.findGameProcessIdForDiagnostics()
        val resultPageEvidenceVisible = detection.kind in setOf(
            ScreenKind.RESULT,
            ScreenKind.RANK_PROGRESS_CONTINUATION,
        ) || ResultPageEvidencePolicy.continueControlVisible(capture.visual.resultContinueGrayLightRatio)
        if (!apply(
                detection,
                recoveryToken,
                sessionReadyBeforeCapture,
                resultPageEvidenceVisible,
            )
        ) return InspectionResult.NO_ACTION
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
        if (!startGameErrorProbeInFlight.compareAndSet(false, true)) {
            log.info { "MATCHMAKING_ERROR_DIALOG_PROBE_SKIPPED reason=probe-in-flight" }
            return StartGameErrorDialogProbe(
                MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
                reason = "probe-in-flight",
            )
        }
        return try {
            probeStartGameErrorDialogForMatchmakingSerial()
        } finally {
            startGameErrorProbeInFlight.set(false)
        }
    }

    private fun probeStartGameErrorDialogForMatchmakingSerial(): StartGameErrorDialogProbe {
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
                captureScreen(
                    ScriptStatus.gameHWND ?: gameWindow,
                    purpose = ScreenRecoveryCapturePurpose.MATCHMAKING_ERROR_DIALOG,
                )
            }
        } else {
            MouseUtil.RecoveryForegroundResult(
                true,
                captureScreen(gameWindow, purpose = ScreenRecoveryCapturePurpose.MATCHMAKING_ERROR_DIALOG),
            )
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

        val probe = runCatching {
            probeStartGameErrorDialogForImage(capture.image, capture.file?.absolutePath) { roiImage, roiName ->
                OcrRuntime.recognizeResult(
                    image = roiImage,
                    desc = "matchmaking-start-game-error-$roiName",
                    roi = roiName,
                    timeoutMs = matchmakingDialogOcrTimeoutMs(),
                ) {
                    // AUTO uses the selected PaddleX provider first and its established legacy fallback.
                    // The modal is multi-line, so use the compatibility engine's block mode rather
                    // than three tiny single-line crops (the confirm crop was frequently empty).
                    ocrScreenRoi(roiImage, tessData, targeted = false)
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
        val powerLogLength = PowerLogListener.logFile?.length() ?: 0L
        if (MatchmakingQueueLifecycleEvidencePolicy.propagateIfAuthoritative(
                probe = probe,
                captureAuthorized = captureResult.foregroundConfirmed,
                powerLogLength = powerLogLength,
                observedAt = System.currentTimeMillis(),
            )
        ) {
            log.info {
                "MATCHMAKING_LIFECYCLE_EVIDENCE source=error-dialog-probe screen=SEARCHING " +
                    "provider=${probe.provider} powerLogLength=$powerLogLength " +
                    "captureAuthorized=true lifecycleSuppressionMs=120000 input=none"
            }
        }
        return probe
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
        if (MatchmakingRewardNoticeVisualPolicy.isVisible(image)) {
            return StartGameErrorDialogProbe(
                state = MatchmakingDialogRecoveryPolicy.Probe.KNOWN_FOUR_CARD_NOTICE_VISIBLE,
                title = "",
                body = "four-card-notice",
                confirm = "visually-confirmed-lower-center-button",
                screenshot = screenshot,
                provider = "VISUAL_SIGNATURE",
                reason = "four-card-notice-footer-button-visually-confirmed",
            )
        }
        if (MatchmakingSearchVisualPolicy.isVisible(image)) {
            return StartGameErrorDialogProbe(
                state = MatchmakingDialogRecoveryPolicy.Probe.NO_ERROR_DIALOG,
                title = "",
                body = "matchmaking-search-screen",
                confirm = "",
                screenshot = screenshot,
                provider = "VISUAL_SIGNATURE",
                reason = "matchmaking-search-screen-visible",
            )
        }
        val roi = ScreenStateRoiSelector
            .selectStartGameError(image.width, image.height)
            .singleOrNull()
            ?: return StartGameErrorDialogProbe(
                state = MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
                screenshot = screenshot,
                reason = "modal-roi-unavailable",
            )
        log.info {
            "MATCHMAKING_ERROR_DIALOG_OCR_ROI name=${roi.name} " +
                "x=${roi.bounds.x} y=${roi.bounds.y} " +
                "w=${roi.bounds.width} h=${roi.bounds.height} space=capture-local"
        }
        val recognition = recognize(crop(image, roi.bounds), roi.name)
        val text = recognition.text
        val modalVisible = visualSignature(image).centeredModalPanelVisual
        val deckSelectionStartVisible = ScreenWatchdog.hasEnabledDeckSelectionStartButton(image)
        val state = StartGameErrorDialogClassifier.classifyModalText(
            text = text,
            confidence = recognition.confidence,
            modalVisible = modalVisible,
            deckSelectionStartVisible = deckSelectionStartVisible,
        )
        val normalized = text.replace(Regex("\\s+"), "")
        return StartGameErrorDialogProbe(
            state = state,
            title = if (normalized.contains("发生错误")) "发生错误" else "",
            body = text,
            confirm = if (normalized.contains("确定")) "确定" else "",
            screenshot = screenshot,
            provider = OcrRuntime.lastProviderUsed().name,
            reason = if (state == MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN) {
                "ocr-unverified-or-low-confidence"
            } else if (state == MatchmakingDialogRecoveryPolicy.Probe.WRONG_MODE_DECK_NOTICE_VISIBLE) {
                "high-confidence-wild-deck-standard-mode-notice-with-deck-selection-start"
            } else {
                "ocr-complete"
            },
        )
    }

    private fun captureScreen(
        hwnd: WinDef.HWND?,
        purpose: ScreenRecoveryCapturePurpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY,
        staleForMs: Long = 0L,
    ): Capture? {
        val authorizedCaptureStartedAt = System.nanoTime()
        val authorizedFrame = ScreenRecoveryWindowCapture.capture(hwnd, purpose, staleForMs) ?: return null
        val authorizedCaptureMs = ResultScreenProbeTelemetry.elapsedMs(authorizedCaptureStartedAt)
        val image = authorizedFrame.image
        val bounds = authorizedFrame.bounds
        val screenshotSaveStartedAt = System.nanoTime()
        val saved = DebugScreenshotRing.save(image, "screen-recovery", "stale-screen")
        val screenshotSaveMs = ResultScreenProbeTelemetry.elapsedMs(screenshotSaveStartedAt)
        val file = saved?.file
        val visualAnalysisStartedAt = System.nanoTime()
        val visual = visualSignature(image)
        val visualAnalysisMs = ResultScreenProbeTelemetry.elapsedMs(visualAnalysisStartedAt)
        return Capture(
            image,
            bounds,
            file,
            visual,
            gameRectKnown = true,
            gameWindowKnown = true,
            purpose = purpose,
            evidence = authorizedFrame.evidence,
            capturedAtNanos = System.nanoTime(),
            resultProbeTimings = ResultProbeCaptureTimings(
                authorizedCaptureMs = authorizedCaptureMs,
                screenshotSaveMs = screenshotSaveMs,
                visualAnalysisMs = visualAnalysisMs,
            ),
        )
    }

    private fun stateRecoveryCapturePurpose(
        stuckForMs: Long,
        startupProbe: Boolean = false,
    ): ScreenRecoveryCapturePurpose {
        val warModelAuthoritative = currentSessionModelReady()
        return ScreenRecoveryCapturePurposePolicy.purposeForStateRecovery(
            tournamentMode = Mode.currMode === ModeEnum.TOURNAMENT,
            // Mode/WAR can remain stale after the current Power.log is lost or
            // empty. Such data may fence input only after a fresh session is
            // ready; the pre-session path is capture-only until classified.
            nonGameplayScreen = true,
            activeGame = StartupGamePhaseEvidencePolicy.hasAuthoritativeActiveGame(
                warModelAuthoritative = warModelAuthoritative,
                warInProgress = WarEx.inWar,
            ),
            mulligan = warModelAuthoritative && WarEx.war.currentPhase == WarPhaseEnum.REPLACE_CARD,
            terminal = warModelAuthoritative && GameUtil.isTerminalGameState(),
            recentInputIntent = LifecycleTrace.hasRecentMatchmakingInputIntent(),
            stuckForMs = stuckForMs,
            warModelAuthoritative = warModelAuthoritative,
            startupProbe = startupProbe,
            noActiveQueueEvidence = !LifecycleTrace.hasRecentActiveMatchmakingScreen() &&
                !LifecycleTrace.hasRecentMatchmakingInputIntent(),
        )
    }

    private fun currentSessionModelReady(): Boolean {
        val gameWindow = resolveLiveGameWindow() ?: return false
        val attachedPowerLog = PowerLogListener.logFile
        val currentPowerLog = GameUtil.getLatestLogDir()?.resolve(GAME_WAR_LOG_NAME)
        return CurrentGameScreenReadinessPolicy.isReady(
            gameWindowVerified = GameUtil.isVerifiedCurrentGameWindow(gameWindow),
            attachedPowerLogPath = attachedPowerLog?.path(),
            currentSessionPowerLogPath = currentPowerLog?.absolutePath,
            powerLogLength = attachedPowerLog?.length() ?: 0L,
        )
    }

    /** Re-capture a pre-session reconnect prompt immediately before granting its single button click. */
    private fun confirmFreshReconnectPrompt(
        recoveryToken: Long,
        expectedPid: Int,
        expectedHwnd: com.sun.jna.platform.win32.WinDef.HWND,
    ): Boolean {
        if (!ScreenRecoveryRuntime.isCurrent(recoveryToken) || WarEx.inWar ||
            GameUtil.isTerminalGameState() || LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
            LifecycleTrace.hasRecentMatchmakingInputIntent()
        ) return false
        val currentHwnd = resolveLiveGameWindow() ?: return false
        if (currentHwnd.toString() != expectedHwnd.toString() ||
            ScriptStatus.gameHWND?.toString() != expectedHwnd.toString() ||
            GameUtil.findGameProcessIdForDiagnostics() != expectedPid.toLong() ||
            !GameUtil.isVerifiedCurrentGameWindow(expectedHwnd)
        ) return false

        val result = MouseUtil.withRecoveryForeground(expectedHwnd) {
            val recheckedWindow = resolveLiveGameWindow()
            if (recheckedWindow == null || recheckedWindow.toString() != expectedHwnd.toString() ||
                GameUtil.findGameProcessIdForDiagnostics() != expectedPid.toLong()
            ) {
                null
            } else {
                captureScreen(expectedHwnd, purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE)
            }
        }
        if (!result.foregroundConfirmed || !ScreenRecoveryRuntime.isCurrent(recoveryToken)) return false
        val capture = result.value ?: return false
        if (capture.evidence.target?.processId != expectedPid ||
            !GameUtil.isVerifiedCurrentGameWindow(expectedHwnd) ||
            GameUtil.findGameProcessIdForDiagnostics() != expectedPid.toLong()
        ) return false
        val detection = detect(runOCR(capture), capture.visual)
        val freshReconnectPrompt = detection?.kind == ScreenKind.RECONNECT &&
            detection.confidence >= 94 && detection.evidence in setOf(
                "reconnect-dialog-roi",
                "reconnect-disconnected-text",
                "reconnect-dialog-structural-visual",
            )
        val positivelyIdentified = StartupInputAuthorizationPolicy.mayIssuePreSessionReconnectPermit(
            screenKind = if (freshReconnectPrompt) "RECONNECT" else detection?.kind?.code.orEmpty(),
            currentSessionReady = currentSessionModelReady(),
            activeGame = WarEx.inWar || GameUtil.isTerminalGameState(),
            activeMatchmaking = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
                LifecycleTrace.hasRecentMatchmakingInputIntent(),
            freshCapture = true,
            exactWindowAndForeground = result.foregroundConfirmed &&
                capture.evidence.target?.processId == expectedPid &&
                GameUtil.findGameProcessIdForDiagnostics() == expectedPid.toLong() &&
                GameUtil.isVerifiedCurrentGameWindow(expectedHwnd),
        )
        if (!positivelyIdentified) {
            log.warn {
                "SCREEN_RECOVERY_PRESESSION_RECONNECT_BLOCKED reason=fresh-modal-not-confirmed " +
                    "screen=${detection?.kind?.code ?: "UNKNOWN"} confidence=${detection?.confidence ?: 0} " +
                    "evidence=${detection?.evidence ?: "none"} pid=$expectedPid hwnd=$expectedHwnd " +
                    "action=NO_INPUT"
            }
            return false
        }
        log.warn {
            "SCREEN_RECOVERY_PRESESSION_RECONNECT_CONFIRMED screen=RECONNECT " +
                "evidence=${detection?.evidence ?: "none"} pid=$expectedPid hwnd=$expectedHwnd " +
                "screenshot=${capture.file?.absolutePath ?: "not-saved"}"
        }
        return true
    }

    private fun runOCR(capture: Capture): OcrEvidence = runOCR(capture.image, File(TESS_DATA_PATH), capture.visual)

    private fun runOCR(
        image: BufferedImage,
        tessData: File,
        visual: VisualSignature = visualSignature(image),
    ): OcrEvidence {
        var targetedMs = 0L
        var secondaryMs = 0L
        var path = "FAILED"
        val chiSim = File(tessData, "$CHI_SIM_DATA.traineddata")
        if (!chiSim.isFile) {
            log.info {
                "SCREEN_RECOVERY_OCR_SKIPPED provider=LEGACY reason=missing-tessdata " +
                    "path=${chiSim.absolutePath}"
            }
            return OcrEvidence("", emptyMap(), ResultProbeOcrTimings(path = "SKIPPED_MISSING_TESSDATA"))
        }
        return runCatching {
            // Probe the screen-specific labels first. These are small,
            // explicit crops and are sufficient to identify the special
            // screens without OCR-ing the full client. Only when neither
            // target matches do we use the smaller secondary bands below.
            val targetedStartedAt = System.nanoTime()
            val targeted = ScreenStateRoiSelector
                .selectTargeted(image.width, image.height)
                .associate { roi ->
                    log.info {
                        "SCREEN_RECOVERY_OCR_ROI name=${roi.name} " +
                            "x=${roi.bounds.x} y=${roi.bounds.y} " +
                            "w=${roi.bounds.width} h=${roi.bounds.height} space=capture-local"
                    }
                    val language = if (roi.name == ScreenStateRoiSelector.YOGG_SERVER_UNAVAILABLE_ROI) {
                        "eng"
                    } else {
                        CHI_SIM_DATA
                    }
                    roi.name to ocrScreenRoi(
                        crop(image, roi.bounds),
                        tessData,
                        targeted = true,
                        language = language,
                    )
                }
            targetedMs = ResultScreenProbeTelemetry.elapsedMs(targetedStartedAt)
            if (targetedScreenDetection(targeted, visual) != null) {
                path = "TARGETED_ONLY"
                return@runCatching OcrEvidence(
                    targeted.values.joinToString(separator = ""),
                    targeted,
                    ResultProbeOcrTimings(
                        targetedMs = targetedMs,
                        provider = "LEGACY_TESSERACT",
                        path = path,
                        totalMs = targetedMs,
                    ),
                )
            }

            // Do not fall back to OCR-ing most of the client. These smaller
            // secondary bands preserve compatibility for screens without a
            // dedicated anchor while keeping the recovery path bounded and
            // excluding the script window on the right.
            val secondaryStartedAt = System.nanoTime()
            val secondary = ScreenStateRoiSelector
                .selectSecondary(image.width, image.height)
                .associate { roi ->
                    log.info {
                        "SCREEN_RECOVERY_OCR_ROI name=${roi.name} " +
                            "x=${roi.bounds.x} y=${roi.bounds.y} " +
                            "w=${roi.bounds.width} h=${roi.bounds.height} space=capture-local"
                    }
                    roi.name to ocrScreenRoi(crop(image, roi.bounds), tessData, targeted = false)
                }
            secondaryMs = ResultScreenProbeTelemetry.elapsedMs(secondaryStartedAt)
            path = "TARGETED_PLUS_SECONDARY"
            OcrEvidence(
                secondary.values.joinToString(separator = ""),
                targeted + secondary,
                ResultProbeOcrTimings(
                    targetedMs = targetedMs,
                    secondaryMs = secondaryMs,
                    provider = "LEGACY_TESSERACT",
                    path = path,
                    totalMs = targetedMs + secondaryMs,
                ),
            )
        }.getOrElse { error ->
            log.warn(error) { "SCREEN_RECOVERY_OCR_FAILED" }
            OcrEvidence(
                "",
                emptyMap(),
                ResultProbeOcrTimings(
                    targetedMs = targetedMs,
                    secondaryMs = secondaryMs,
                    provider = "LEGACY_TESSERACT",
                    path = path,
                    totalMs = targetedMs + secondaryMs,
                ),
            )
        }
    }

    private fun ocrScreenRoi(
        image: BufferedImage,
        tessData: File,
        targeted: Boolean,
        language: String = CHI_SIM_DATA,
    ): String {
        val ocrImage = if (targeted) enlargeTargetedOcr(image) else resizeForOcr(image)
        return Tesseract().apply {
            setDatapath(tessData.absolutePath)
            setLanguage(language)
            setPageSegMode(if (targeted && language == "eng") 6 else if (targeted) 7 else 11)
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
            resultBannerWarmRatio = bannerSignal.warmRatio,
            centeredModalPanelVisual = looksLikeCenteredModalPanelVisual(
                lowSaturationRatio = reconnectDialogPanelSignal.lowSaturationRatio,
                darkRatio = reconnectDialogPanelSignal.darkRatio,
                warmRatio = reconnectDialogPanelSignal.warmRatio,
            ),
            startupQuestOverlayVisual = StartupQuestOverlayVisualClassifier.isQuestOverlay(image),
            battleNetLauncherVisual = BattleNetLauncherVisualPolicy.isVisible(image),
            reconnectDialogVisual = looksLikeReconnectDialogVisual(image),
            matchmakingSearchVisual = MatchmakingSearchVisualPolicy.isVisible(image),
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
        var blue = 0
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
                if (b > 80 && b > r * 1.18 && b > g * 1.05) blue++
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
            blueRatio = blue.toDouble() / samples,
            warmRatio = warm.toDouble() / samples,
        )
    }

    private fun detect(ocrText: String, visual: VisualSignature): Detection? =
        detect(OcrEvidence(ocrText, emptyMap()), visual)

    private fun detect(evidence: OcrEvidence, visual: VisualSignature): Detection? {
        if (visual.battleNetLauncherVisual) return null
        val targetedDetection = targetedScreenDetection(evidence.targeted, visual)
        if (targetedDetection?.kind in setOf(
                ScreenKind.SERVER_UNAVAILABLE,
                ScreenKind.RECONNECT,
                ScreenKind.RECONNECT_FAILURE,
            )
        ) return targetedDetection
        // The recorded exact quest-modal visual must preempt an underlay deck
        // or Hub label from a targeted ROI, but never a reconnect/server dialog.
        if (visual.startupQuestOverlayVisual) {
            return Detection(ScreenKind.HOME_TASK_OVERLAY, ModeEnum.HUB, 96, "startup-your-quests-overlay")
        }
        // A fresh result banner/Continue signature must outrank a noisy
        // targeted deck or Hub chooser ROI from the underlying game board.
        if (looksLikeResultVisual(
                visual.resultContinueGrayLightRatio,
                visual.resultBannerLowSaturationRatio,
                visual.loadingCentralDarkRatio,
                visual.resultBannerWarmRatio,
            )
        ) {
            return Detection(ScreenKind.RESULT, ModeEnum.GAMEPLAY, 92, "result-fixed-continue-visual")
        }
        targetedDetection?.let { return it }

        val ocrText = evidence.text
        val text = ocrText.lowercase(Locale.ROOT)
        fun has(vararg terms: String): Boolean = terms.all { text.contains(it) }

        // More specific screens must win before generic home/login words.
        // A reconnect/error dialog can leave the deck-selection title visible
        // behind it. The dialog must therefore win before DECK_SELECTION.
        if (looksLikeServerUnavailableText(text) &&
            !visual.matchmakingSearchVisual &&
            !looksLikeMatchmakingText(text)
        ) {
            return Detection(ScreenKind.SERVER_UNAVAILABLE, ModeEnum.STARTUP, 100, "server-unavailable-text")
        }
        if (looksLikeReconnectFailureText(text)) {
            return Detection(ScreenKind.RECONNECT_FAILURE, ModeEnum.LOGIN, 97, "reconnect-failure-text")
        }
        if (looksLikeReconnectText(text)) {
            return Detection(ScreenKind.RECONNECT, ModeEnum.LOGIN, 96, "reconnect-disconnected-text")
        }
        if (visual.reconnectDialogVisual) {
            return Detection(ScreenKind.RECONNECT, ModeEnum.LOGIN, 94, "reconnect-dialog-structural-visual")
        }
        if (looksLikeReconnectSpinnerText(text)) {
            return Detection(ScreenKind.RECONNECT_SPINNER, ModeEnum.STARTUP, 96, "reconnect-spinner-text")
        }
        // The same centered panel can cover the deck title while a distinct
        // matchmaking error is visible. If its message was not recognized as
        // a known reconnect state, do not act on the background screen. The
        // dedicated matchmaking probe may click only after its exact OCR
        // contract succeeds; otherwise this remains an unresolved observation.
        if (ResultPageEvidencePolicy.looksLikeRankProgressContinuationVisual(
                visual.resultContinueGrayLightRatio,
                visual.resultBannerLowSaturationRatio,
                visual.loadingCentralDarkRatio,
                visual.resultBannerWarmRatio,
            )
        ) {
            return Detection(
                ScreenKind.RANK_PROGRESS_CONTINUATION,
                ModeEnum.GAMEPLAY,
                93,
                "post-result-rank-progress-visual",
            )
        }
        if (ResultPageEvidencePolicy.looksLikeResultText(text)) {
            return Detection(ScreenKind.RESULT, ModeEnum.GAMEPLAY, 95, "result-text")
        }
        // A real result panel may also satisfy the generic centered-modal
        // guard. Let only the stronger terminal evidence above take priority;
        // all other unrecognized modal overlays remain unresolved.
        if (visual.startupQuestOverlayVisual ||
            (!visual.centeredModalPanelVisual && looksLikeStartupQuestOverlayText(text))
        ) {
            return Detection(ScreenKind.HOME_TASK_OVERLAY, ModeEnum.HUB, 96, "startup-your-quests-overlay")
        }
        if (visual.matchmakingSearchVisual) {
            return Detection(ScreenKind.MATCHMAKING, ModeEnum.TOURNAMENT, 98, "matchmaking-search-visual")
        }
        if (visual.centeredModalPanelVisual) return null
        if (text.contains("选择套牌") || has("套牌", "狂野对战")) {
            return Detection(ScreenKind.DECK_SELECTION, ModeEnum.TOURNAMENT, 100, "deck-selection-title")
        }
        if (looksLikeShopOverlayText(text) && looksLikeShopOverlayVisual(visual)) {
            return Detection(ScreenKind.SHOP_OVERLAY, ModeEnum.HUB, 94, "shop-overlay-text-and-visual")
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
        val yoggMessage = targeted[ScreenStateRoiSelector.YOGG_SERVER_UNAVAILABLE_ROI].orEmpty()
        if (looksLikeServerUnavailableText(yoggMessage) && !visual.matchmakingSearchVisual) {
            return Detection(ScreenKind.SERVER_UNAVAILABLE, ModeEnum.STARTUP, 100, "yogg-message-roi-eng")
        }
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
        if (visual.reconnectDialogVisual) {
            return Detection(ScreenKind.RECONNECT, ModeEnum.LOGIN, 94, "reconnect-dialog-structural-visual")
        }

        // A label is only authoritative when it came from its own ROI. This
        // prevents an OCR spill from the deck title into the traditional-mode
        // branch (or vice versa) from changing the recovery state.
        val deckTitle = targeted[ScreenStateRoiSelector.DECK_SELECTION_TITLE_ROI].orEmpty()
        // The deployed 2026-09-26 capture had the exact deck title behind a
        // centered, dark-gray non-reconnectable modal. OCR garbled the dialog
        // ROIs, so the underlying deck title incorrectly won. Require both
        // that screen-specific title anchor and the distinctive modal panel.
        if (looksLikeDeckSelectionTitleText(deckTitle) && visual.centeredModalPanelVisual) return null
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
        return normalized.contains("你的活动任务") || normalized.contains("你的活動任務") ||
            normalized.contains("yourquests")
    }

    internal fun looksLikeReconnectFailureDialogRoiText(ocrText: String): Boolean {
        val text = normalizedScreenText(ocrText)
        return text.contains("重新连接失败") ||
            text.contains("无法重新连接") ||
            text.contains("请重试") ||
            text.contains("请重新启动炉石传说") ||
            text.contains("无法通过暴雪战网服务")
    }

    /** OCR-free panel-shape guard; it blocks underlay recovery but never identifies a modal action. */
    internal fun looksLikeCenteredModalPanelVisual(
        lowSaturationRatio: Double,
        darkRatio: Double,
        warmRatio: Double,
    ): Boolean = lowSaturationRatio >= 0.82 && darkRatio >= 0.74 && warmRatio <= 0.10

    /** OCR-independent structural signature for the fixed offline reconnect dialog. */
    internal fun looksLikeReconnectDialogVisual(image: BufferedImage): Boolean {
        val panel = sampleRegion(image, 0.32, 0.68, 0.39, 0.62)
        val header = sampleRegion(image, 0.37, 0.63, 0.19, 0.28)
        val emblem = sampleRegion(image, 0.43, 0.57, 0.30, 0.51)
        val leftButton = sampleRegion(image, 0.34, 0.50, 0.70, 0.81)
        val rightButton = sampleRegion(image, 0.50, 0.67, 0.70, 0.81)
        return looksLikeCenteredModalPanelVisual(panel.lowSaturationRatio, panel.darkRatio, panel.warmRatio) &&
            header.lowSaturationRatio >= 0.78 && header.darkRatio >= 0.55 &&
            emblem.warmRatio >= 0.18 && leftButton.warmRatio >= 0.36 && rightButton.warmRatio >= 0.36
    }

    /** OCR-independent signature for the centered Amazon Appstore sunset notice. */
    internal fun looksLikeAmazonAppstoreSunsetNoticeVisual(image: BufferedImage): Boolean {
        if (image.width < 800 || image.height < 450) return false
        val card = sampleRegion(image, 0.29, 0.71, 0.20, 0.79)
        val emblem = sampleRegion(image, 0.32, 0.44, 0.37, 0.57)
        val closeButton = sampleRegion(image, 0.47, 0.55, 0.69, 0.77)
        val moreButton = sampleRegion(image, 0.59, 0.72, 0.57, 0.69)
        return card.warmRatio >= 0.12 &&
            emblem.blueRatio >= 0.08 &&
            emblem.warmRatio >= 0.55 &&
            closeButton.warmRatio >= 0.12 &&
            moreButton.warmRatio >= 0.60 &&
            !looksLikeReconnectDialogVisual(image)
    }

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

    internal fun looksLikeServerUnavailableText(ocrText: String): Boolean {
        // The English game font is decorative, and the deployed incident OCR
        // substituted two glyphs (`Yog¢` / `checR`). Keep this tolerant check
        // scoped to the dedicated outage phrase: all semantic anchors must be
        // present in the same bounded text ROI.
        val text = ocrText.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        return text.contains("yog") && text.contains("devoured") && text.contains("server") &&
            text.contains("again") && text.contains("please") && text.contains("chec") &&
            text.contains("backlater")
    }

    internal fun classifyCapturedImageForTest(image: BufferedImage, tessData: File): String? =
        detect(runOCR(image, tessData), visualSignature(image))?.kind?.code

    internal fun hasCenteredModalPanel(image: BufferedImage): Boolean =
        visualSignature(image).centeredModalPanelVisual

    /**
     * The Standard chooser shows a small fixed Wild-deck incompatibility tooltip
     * above the selected deck. Its dark border and gray body overlap the broad
     * centered-panel ROI, but it is a hover tooltip, not a blocking modal.
     * Require the Standard-banner and tooltip footprint before suppressing the
     * generic modal veto. Start is disabled for the very Wild-deck mismatch this
     * tooltip explains, so Start visibility cannot be part of this proof.
     */
    internal fun isWrongModeDeckTooltip(image: BufferedImage): Boolean {
        if (image.width < 1000 || image.height < 560) return false
        val tooltip = sampleRegion(image, 0.445, 0.56, 0.31, 0.40)
        val standardBanner = sampleRegion(image, 0.32, 0.48, 0.015, 0.065)
        return tooltip.lowSaturationRatio >= 0.30 && tooltip.darkRatio in 0.08..0.72 &&
            standardBanner.lowSaturationRatio >= 0.18
    }

    internal fun wrongModeTooltipVisualForTest(image: BufferedImage): Boolean = isWrongModeDeckTooltip(image)

    internal fun hasExactStartupQuestOverlayVisual(image: BufferedImage): Boolean =
        visualSignature(image).startupQuestOverlayVisual

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
            now - reconnectStartedAt >= STALLED_RECONNECT_RESTART_MS

    private fun shouldRestartStalledLoading(loadingStartedAt: Long, now: Long): Boolean =
        loadingStartedAt > 0L && now >= loadingStartedAt &&
            now - loadingStartedAt >= STALLED_LOADING_RESTART_MS

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
        centeredModalPanelVisual: Boolean = false,
    ): RecoveryTransitionForTest? = detect(
        OcrEvidence(ocrText, targeted),
        VisualSignature(
            sampleHash = 0L,
            warmRatio = 0.0,
            blueRatio = 0.0,
            loadingCentralDarkRatio = 0.0,
            resultContinueGrayLightRatio = 0.0,
            resultBannerLowSaturationRatio = 0.0,
            centeredModalPanelVisual = centeredModalPanelVisual,
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
                detection.kind != ScreenKind.SERVER_UNAVAILABLE &&
                detection.kind != ScreenKind.RANK_PROGRESS_CONTINUATION &&
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
                ScreenKind.RANK_PROGRESS_CONTINUATION -> "CONTINUE_WITH_TERMINAL_CAPABILITY"
                ScreenKind.MATCHMAKING -> "WAIT_FOR_GAMEPLAY"
                ScreenKind.RECONNECT -> "CLICK_RECONNECT"
                ScreenKind.RECONNECT_FAILURE -> "RESTART_CLIENT"
                ScreenKind.RECONNECT_SPINNER -> "WAIT_OR_CANCEL_RECONNECT"
                ScreenKind.SERVER_UNAVAILABLE -> "CLASSIFY_NON_GAME_NO_INPUT_NO_RESTART"
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
                detection.kind != ScreenKind.RECONNECT_FAILURE &&
                detection.kind != ScreenKind.SERVER_UNAVAILABLE &&
                detection.kind != ScreenKind.RANK_PROGRESS_CONTINUATION,
            action = when (detection.kind) {
                ScreenKind.HOME_TASK_OVERLAY -> "DISMISS_HOME_TASK_OVERLAY"
                ScreenKind.DECK_SELECTION -> "START_MATCHING"
                ScreenKind.RECONNECT_FAILURE -> "RESTART_CLIENT"
                ScreenKind.RANK_PROGRESS_CONTINUATION -> "CONTINUE_WITH_TERMINAL_CAPABILITY"
                ScreenKind.MATCHMAKING -> "WAIT_FOR_GAMEPLAY"
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
    internal fun looksLikeResultText(ocrText: String): Boolean =
        ResultPageEvidencePolicy.looksLikeResultText(ocrText)

    /**
     * OCR-free result-page fallback.  A single bright pixel cluster is not
     * enough because the live board has many highlights; require the fixed
     * lower-center continue label and the dimmed/desaturated result banner.
     */
    internal fun looksLikeResultVisual(
        resultContinueGrayLightRatio: Double,
        resultBannerLowSaturationRatio: Double,
        loadingCentralDarkRatio: Double,
        resultBannerWarmRatio: Double,
    ): Boolean = ResultPageEvidencePolicy.looksLikeResultVisual(
        resultContinueGrayLightRatio,
        resultBannerLowSaturationRatio,
        loadingCentralDarkRatio,
        resultBannerWarmRatio,
    )

    /** Replay fixture pixels through the production visual/modal classifier without desktop capture or OCR. */
    internal fun classifyImageForResultFixture(image: BufferedImage): String? =
        detect(OcrEvidence("", emptyMap()), visualSignature(image))?.kind?.code

    internal fun resultVisualEvidenceForFixture(image: BufferedImage): String {
        val visual = visualSignature(image)
        return "continue=${visual.resultContinueGrayLightRatio} " +
            "banner=${visual.resultBannerLowSaturationRatio} " +
            "bannerWarm=${visual.resultBannerWarmRatio} " +
            "centerDark=${visual.loadingCentralDarkRatio} modal=${visual.centeredModalPanelVisual}"
    }

    internal fun continueControlVisibleForFixture(image: BufferedImage): Boolean =
        ResultPageEvidencePolicy.continueControlVisible(visualSignature(image).resultContinueGrayLightRatio)

    private fun fastResultOverlayKind(image: BufferedImage, visual: VisualSignature): FastResultOverlayKind? =
        if (ResultPageEvidencePolicy.looksLikeRankProgressContinuationVisual(
                visual.resultContinueGrayLightRatio,
                visual.resultBannerLowSaturationRatio,
                visual.loadingCentralDarkRatio,
                visual.resultBannerWarmRatio,
            )
        ) null else FastResultOverlayRecoveryPolicy.classify(
            continueControlVisible = ResultPageEvidencePolicy.continueControlVisible(
                visual.resultContinueGrayLightRatio,
            ),
            startupQuestOverlayVisual = visual.startupQuestOverlayVisual,
            image = image,
        )

    private fun fastResultOverlayObservation(
        kind: FastResultOverlayKind?,
        visual: VisualSignature,
        capturedAtNanos: Long,
    ): ResultScreenObservation? = FastResultOverlayRecoveryPolicy.observation(
        kind = kind,
        continueControlVisible = ResultPageEvidencePolicy.continueControlVisible(
            visual.resultContinueGrayLightRatio,
        ),
        capturedAtNanos = capturedAtNanos,
    )

    /** Offline seam for the same shared result-observation path used in production. */
    internal fun classifyFastResultOverlayForTest(image: BufferedImage): String? =
        fastResultOverlayKind(image, visualSignature(image))?.name

    internal fun observeFastResultOverlayForTest(
        image: BufferedImage,
        capturedAtNanos: Long = System.nanoTime(),
    ): ResultScreenObservation? {
        val visual = visualSignature(image)
        return fastResultOverlayObservation(fastResultOverlayKind(image, visual), visual, capturedAtNanos)
    }

    /**
     * Re-check the actual desktop after a result-page input was sent.
     *
     * `MouseUtil` can report that an event was queued even when the client did
     * not consume it. Return false only for an explicitly recognized
     * post-result destination; a live board, transitional screen, or
     * inconclusive capture returns null and cannot release the rank barrier.
     */
    internal fun observeResultScreenForRecovery(): ResultScreenObservation {
        val token = ScreenRecoveryRuntime.tokenOrNull()
            ?: return UpstreamScreenStateRecovery.observeResultScreenForRecovery()
        val probeId = ResultScreenProbeTelemetry.nextProbeId()
        val probeStartedAt = System.nanoTime()
        var windowResolveMs = 0L
        var captureTimings = ResultProbeCaptureTimings()
        var ocrTimings = ResultProbeOcrTimings()
        var decisionMs = 0L
        var decisionPath = "CAPTURE_UNAVAILABLE"
        fun report(observation: ResultScreenObservation): ResultScreenObservation {
            log.info {
                "SCREEN_RECOVERY_RESULT_PROBE_TIMING " +
                    ResultScreenProbeTelemetry.format(
                        probeId = probeId,
                        windowResolveMs = windowResolveMs,
                        capture = captureTimings,
                        ocr = ocrTimings,
                        decisionMs = decisionMs,
                        totalMs = ResultScreenProbeTelemetry.elapsedMs(probeStartedAt),
                        resultVisible = observation.resultVisible,
                        rankProgressVisible = observation.rankProgressVisible,
                        destinationScreen = observation.destinationScreen,
                        captureAuthorized = observation.captureAuthorized,
                        continueControlVisible = observation.continueControlVisible,
                    ) + " path=$decisionPath"
            }
            return observation
        }
        return runCatching {
            val windowResolveStartedAt = System.nanoTime()
            val liveWindow = resolveLiveGameWindow()
            windowResolveMs = ResultScreenProbeTelemetry.elapsedMs(windowResolveStartedAt)
            if (liveWindow == null) return@runCatching report(ResultScreenObservation(null, captureAuthorized = false))
            val capture = captureScreen(liveWindow)
            if (capture == null) return@runCatching report(ResultScreenObservation(null, captureAuthorized = false))
            captureTimings = capture.resultProbeTimings
            if (!ScreenRecoveryRuntime.isCurrent(token)) {
                decisionPath = "RUNTIME_TOKEN_INVALID"
                return@runCatching report(ResultScreenObservation(null, captureAuthorized = false))
            }
            if (ScreenWatchdog.hasVerifiedGameMenuOverlay(capture.image)) {
                decisionPath = "VERIFIED_GAME_MENU_OVERLAY"
                val continueVisible = ResultPageEvidencePolicy.continueControlVisible(
                    capture.visual.resultContinueGrayLightRatio,
                )
                log.info {
                    "SCREEN_RECOVERY_RESULT_MENU_OBSERVATION continueBand=$continueVisible " +
                        "action=${if (continueVisible) "FRESH_CONTINUE_CLICK" else "NO_INPUT"}"
                }
                return@runCatching report(ResultScreenObservation(
                    resultVisible = null,
                    captureAuthorized = true,
                    continueControlVisible = continueVisible,
                    gameMenuOverlayVisible = true,
                    capturedAtNanos = capture.capturedAtNanos,
                ))
            }
            val fastOverlayKind = fastResultOverlayKind(capture.image, capture.visual)
            when (fastOverlayKind) {
                FastResultOverlayKind.CARD_BACK_REWARD -> {
                    decisionPath = "CARD_BACK_REWARD_VISUAL"
                    val observation = requireNotNull(fastResultOverlayObservation(
                        fastOverlayKind,
                        capture.visual,
                        capture.capturedAtNanos,
                    ))
                    log.info {
                        "SCREEN_RECOVERY_RESULT_FAST_OVERLAY kind=CARD_BACK_REWARD result=RESULT " +
                            "continueBand=${observation.continueControlVisible} ocr=skipped"
                    }
                    return@runCatching report(observation)
                }
                FastResultOverlayKind.EVENT_QUEST -> {
                    decisionPath = "EXACT_EVENT_QUEST_VISUAL"
                    log.info { "SCREEN_RECOVERY_RESULT_FAST_OVERLAY kind=EVENT_QUEST_OVERLAY ocr=skipped" }
                    return@runCatching report(requireNotNull(fastResultOverlayObservation(
                        fastOverlayKind,
                        capture.visual,
                        capture.capturedAtNanos,
                    )))
                }
                null -> Unit
            }
            val visualDecisionStartedAt = System.nanoTime()
            val fastVisual = ResultPageEvidencePolicy.fastVisualObservation(
                capture.visual.resultContinueGrayLightRatio,
                capture.visual.resultBannerLowSaturationRatio,
                capture.visual.loadingCentralDarkRatio,
                capture.visual.resultBannerWarmRatio,
            )
            if (fastVisual != null) {
                decisionMs = ResultScreenProbeTelemetry.elapsedMs(visualDecisionStartedAt)
                decisionPath = "FAST_VISUAL"
                log.info {
                    "SCREEN_RECOVERY_RESULT_FAST_VISUAL result=${fastVisual.resultVisible ?: "UNKNOWN"} " +
                        "rankProgress=${fastVisual.rankProgressVisible} continueBand=true ocr=skipped"
                }
                val observation = ResultScreenObservation(
                    resultVisible = fastVisual.resultVisible,
                    captureAuthorized = true,
                    visualOnlyResultEvidence = fastVisual.resultVisible == true && !fastVisual.rankProgressVisible,
                    rankProgressVisible = fastVisual.rankProgressVisible,
                    continueControlVisible = fastVisual.continueControlVisible,
                    capturedAtNanos = capture.capturedAtNanos,
                )
                return@runCatching report(observation)
            }
            val ocrEvidence = runOCR(capture)
            ocrTimings = ocrEvidence.resultProbeTimings
            val screenDecisionStartedAt = System.nanoTime()
            val detection = detect(ocrEvidence, capture.visual)
            decisionPath = when {
                detection == null -> "OCR_UNKNOWN"
                detection.kind in POST_RESULT_DESTINATIONS -> "OCR_DESTINATION"
                detection.kind == ScreenKind.RESULT || detection.kind == ScreenKind.RANK_PROGRESS_CONTINUATION -> "OCR_RESULT"
                else -> "OCR_OTHER"
            }
            if (!ScreenRecoveryRuntime.isCurrent(token)) {
                decisionPath = "RUNTIME_TOKEN_INVALID_AFTER_OCR"
                return@runCatching report(ResultScreenObservation(null, captureAuthorized = false))
            }
            if (detection?.kind == ScreenKind.DECK_SELECTION) {
                recordResultDeckSelectionStartProof(
                    detection = detection,
                    capture = capture,
                    pid = capture.evidence.target?.processId,
                    hwnd = liveWindow.toString(),
                    captureAuthorized = true,
                )
            }
            val observation = ResultScreenObservation(
                resultVisible = resultPageVisibility(detection),
                captureAuthorized = true,
                visualOnlyResultEvidence = detection?.evidence == "result-fixed-continue-visual",
                rankProgressVisible = detection?.kind == ScreenKind.RANK_PROGRESS_CONTINUATION,
                continueControlVisible = ResultPageEvidencePolicy.continueControlVisible(
                    capture.visual.resultContinueGrayLightRatio,
                ),
                destinationScreen = detection?.kind?.code?.takeIf { it in POST_RESULT_DESTINATIONS.map(ScreenKind::code) },
                destinationConfidence = detection?.confidence ?: 0,
                capturedAtNanos = capture.capturedAtNanos,
            )
            decisionMs = ResultScreenProbeTelemetry.elapsedMs(screenDecisionStartedAt)
            report(observation)
        }.getOrElse { error ->
            if (ScreenRecoveryRuntime.isCurrent(token)) {
                log.warn(error) { "SCREEN_RECOVERY_RESULT_POSTCHECK_FAILED" }
            }
            decisionPath = "FAILED_${error.javaClass.simpleName}"
            report(ResultScreenObservation(null, captureAuthorized = false))
        }
    }

    private fun resultPageVisibility(detection: Detection?): Boolean? =
        resultVisibilityForTest(detection?.kind?.code, detection?.confidence ?: 0)

    private fun recordResultDeckSelectionStartProof(
        detection: Detection,
        capture: Capture,
        pid: Int?,
        hwnd: String,
        captureAuthorized: Boolean,
    ) {
        val mode = TournamentModeConfirmation.observeImageVisualOnly(capture.image)
        val expectedMode = DeckStrategyManager.currentRunMode?.name
        val captureAgeMs = (System.nanoTime() - capture.capturedAtNanos) / 1_000_000L
        val startVisible = ScreenWatchdog.hasEnabledDeckSelectionStartButton(capture.image)
        val modalVisible = capture.visual.centeredModalPanelVisual
        val recorded = ResultDeckSelectionStartProofPolicy.record(
            captureAuthorized = captureAuthorized,
            captureAgeMs = captureAgeMs,
            pid = pid,
            hwnd = hwnd,
            confidence = detection.confidence,
            evidence = detection.evidence,
            expectedMode = expectedMode,
            observedMode = mode.observedMode.name,
            enabledStartVisible = startVisible,
            modalVisible = modalVisible,
            now = System.currentTimeMillis(),
        )
        log.info {
            "RESULT_PAGE_DECK_START_PROOF decision=${if (recorded) "RECORDED" else "BLOCKED"} " +
                "reason=${when {
                    !captureAuthorized -> "capture-unauthorized"
                    captureAgeMs !in 0..ResultDeckSelectionStartProofPolicy.MAX_CAPTURE_AGE_MS -> "capture-stale"
                    expectedMode !in setOf("WILD", "STANDARD") -> "configured-mode-unavailable"
                    mode.observedMode.name != expectedMode -> "configured-mode-not-visually-confirmed"
                    !startVisible -> "start-control-not-visible"
                    modalVisible -> "modal-visible"
                    else -> "capture-or-screen-evidence-rejected"
                }} expectedMode=${expectedMode ?: "UNKNOWN"} observedMode=${mode.observedMode.name} " +
                "captureAgeMs=$captureAgeMs captureAuthorized=$captureAuthorized confidence=${detection.confidence} " +
                "evidence=${detection.evidence} startVisible=$startVisible modal=$modalVisible " +
                "pid=${pid ?: "none"} hwnd=$hwnd"
        }
    }

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
        // Exact RECONNECT classification grants only a bounded observation
        // grace followed by client restart. Never dispatch dialog input.
        return false
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
                val capture = captureScreen(liveWindow)
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
                        ScreenKind.RANK_PROGRESS_CONTINUATION ->
                            (observed?.kind == ScreenKind.RANK_PROGRESS_CONTINUATION && mode == ModeEnum.GAMEPLAY) ||
                                (observed?.kind?.let { it in POST_RESULT_DESTINATIONS } == true && !WarEx.inWar)
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
        delayMs: Long,
    ) {
        val task = EXTRA_THREAD_POOL.schedule({
            if (!ScreenRecoveryRuntime.isCurrent(recoveryToken)) return@schedule
            if (!StartupQuestOverlayRecoveryFence.tryBeginObservation()) return@schedule
            try {
                val observations = StartupQuestOverlayRecoveryFence.recordObservation()
                if (observations % STARTUP_QUEST_OVERLAY_MAX_PROBES == 0) {
                    log.warn {
                        "SCREEN_RECOVERY_HOME_TASK_OVERLAY_REOBSERVATION_WINDOW_EXHAUSTED " +
                            "continuing=bounded-10s-reobserve " +
                            "taps=${StartupQuestOverlayRecoveryFence.tapsAttempted()} " +
                            "observations=$observations pause=${PauseStatus.isPause} " +
                            "ordinaryDispatch=blocked"
                    }
                }
                if (PauseStatus.isPause || !WorkTimeListener.working || Mode.currMode != ModeEnum.HUB) {
                    log.info {
                        "SCREEN_RECOVERY_HOME_TASK_OVERLAY_REOBSERVE_DEFERRED " +
                            "reason=manual-pause-or-state-change working=${WorkTimeListener.working} " +
                            "pause=${PauseStatus.isPause} mode=${Mode.currMode?.name ?: "NONE"} " +
                            "taps=${StartupQuestOverlayRecoveryFence.tapsAttempted()}"
                    }
                    scheduleHomeTaskOverlayRecovery(recoveryToken, 10_000L)
                    return@schedule
                }

                val sourcePid = GameUtil.findGameProcessIdForDiagnostics()
                val hwnd = resolveLiveGameWindow()
                if (sourcePid == null || hwnd == null) {
                    log.warn {
                        "SCREEN_RECOVERY_HOME_TASK_OVERLAY_UNRESOLVED reason=game-window-unavailable " +
                            "pause=${PauseStatus.isPause} ordinaryDispatch=blocked"
                    }
                    scheduleHomeTaskOverlayRecovery(recoveryToken, 10_000L)
                    return@schedule
                }
                val frameResult = MouseUtil.withRecoveryForeground(hwnd) {
                    val currentWindow = resolveLiveGameWindow()
                    val currentPid = GameUtil.findGameProcessIdForDiagnostics()
                    if (currentWindow == null || currentWindow.toString() != hwnd.toString() || currentPid != sourcePid) {
                        null
                    } else {
                        captureScreen(currentWindow)
                    }
                }
                val freshCapture = frameResult.value
                val targetWindow = freshCapture?.evidence?.target?.rootWindow
                val expectedWindow = com.sun.jna.Pointer.nativeValue(hwnd.pointer)
                val captureTrusted = frameResult.foregroundConfirmed && freshCapture != null &&
                    freshCapture.evidence.target?.processId?.toLong() == sourcePid &&
                    targetWindow == expectedWindow &&
                    freshCapture.evidence.currentGameProcessId == sourcePid &&
                    GameUtil.findGameProcessIdForDiagnostics() == sourcePid &&
                    GameUtil.isVerifiedCurrentGameWindow(hwnd, sourcePid)
                if (!captureTrusted) {
                    log.warn {
                        "SCREEN_RECOVERY_HOME_TASK_OVERLAY_UNRESOLVED reason=trusted-capture-unavailable " +
                            "foregroundConfirmed=${frameResult.foregroundConfirmed} capture=${freshCapture != null} " +
                            "probe=$observations pause=${PauseStatus.isPause} ordinaryDispatch=blocked"
                    }
                    scheduleHomeTaskOverlayRecovery(recoveryToken, 10_000L)
                    return@schedule
                }

                val capture = requireNotNull(freshCapture)
                StartupQuestOverlayRecoveryFence.observeSession(sourcePid, hwnd.toString())
                val evidence = runOCR(capture)
                val currentPid = GameUtil.findGameProcessIdForDiagnostics()
                val currentHwnd = resolveLiveGameWindow()
                val captureStillTrusted = currentPid == sourcePid && currentHwnd?.toString() == hwnd.toString() &&
                    GameUtil.isVerifiedCurrentGameWindow(hwnd, sourcePid)
                val detection = if (captureStillTrusted) detect(evidence, capture.visual) else null
                val activeMatchmaking = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
                    LifecycleTrace.hasRecentMatchmakingInputIntent()
                val gameIntent = WarEx.inWar || GameUtil.isTerminalGameState() ||
                    Mode.currMode == ModeEnum.GAMEPLAY || Mode.nextMode == ModeEnum.GAMEPLAY
                if (activeMatchmaking || gameIntent) {
                    log.warn {
                        "SCREEN_RECOVERY_HOME_TASK_OVERLAY_DEFERRED reason=${if (activeMatchmaking) "recent-matchmaking-intent" else "game-or-terminal-intent"} " +
                            "detected=${detection?.kind?.code ?: "UNKNOWN"} pid=$sourcePid hwnd=$hwnd " +
                            "pause=${PauseStatus.isPause} ordinaryDispatch=blocked input=not-sent " +
                            "clearFence=verified-transition-only"
                    }
                    scheduleHomeTaskOverlayRecovery(recoveryToken, 10_000L)
                    return@schedule
                }

                val nowMs = System.currentTimeMillis()
                val captureAgeMs = ((System.nanoTime() - capture.capturedAtNanos) / 1_000_000L).coerceAtLeast(0L)
                val isExactOverlay = detection?.kind == ScreenKind.HOME_TASK_OVERLAY &&
                    detection.evidence == "startup-your-quests-overlay"
                val safeTransition = detection != null && detection.confidence >= 85 &&
                    detection.kind in setOf(
                        ScreenKind.HOME,
                        ScreenKind.TOURNAMENT,
                        ScreenKind.DECK_SELECTION,
                        ScreenKind.MATCHMAKING,
                        ScreenKind.LOGIN,
                        ScreenKind.GAME_MODE,
                        ScreenKind.COLLECTION,
                        ScreenKind.PACK_OPENING,
                    )
                val observation = StartupQuestOverlayDismissPolicy.Observation(
                    nowMs = nowMs,
                    captureAtMs = nowMs - captureAgeMs,
                    captureTrusted = captureStillTrusted && captureAgeMs <= StartupQuestOverlayDismissPolicy.MAX_CAPTURE_AGE_MS,
                    currentPid = currentPid,
                    expectedPid = sourcePid,
                    currentWindow = currentHwnd?.toString(),
                    expectedWindow = hwnd.toString(),
                    exactQuestOverlay = isExactOverlay,
                    confidence = detection?.confidence ?: 0,
                    noRecentMatchmakingIntent = !activeMatchmaking,
                    noGameIntent = !gameIntent,
                    safeTransition = safeTransition,
                )
                val tapsAttempted = StartupQuestOverlayRecoveryFence.tapsAttempted()
                val decision = StartupQuestOverlayDismissPolicy.decide(tapsAttempted, observation)
                log.info {
                    "SCREEN_RECOVERY_HOME_TASK_OVERLAY_PROBE observation=${detection?.kind?.code ?: "UNKNOWN"} " +
                        "confidence=${detection?.confidence ?: 0} evidence=${detection?.evidence ?: "none"} " +
                        "action=${decision.action} reason=${decision.reason} taps=$tapsAttempted " +
                        "probe=$observations pid=$sourcePid hwnd=$hwnd " +
                        "screenshot=${capture.file?.absolutePath ?: "none"}"
                }
                when (decision.action) {
                    StartupQuestOverlayDismissPolicy.Action.RESOLVE_SAFE_TRANSITION -> {
                        StartupQuestOverlayRecoveryFence.resolve()
                        val mode = requireNotNull(detection).mode
                        Mode.recover(
                            mode,
                            "verified-screen-transition-after-quest-overlay",
                            enterStrategy = mode == ModeEnum.HUB,
                        )
                        log.warn {
                            "SCREEN_RECOVERY_HOME_TASK_OVERLAY_RESOLVED screen=${detection.kind.code} " +
                                "mode=${mode.name} confidence=${detection.confidence} " +
                                "ordinaryDispatch=enabled"
                        }
                    }
                    StartupQuestOverlayDismissPolicy.Action.TAP_TOP_LEFT -> {
                        val point = StartupQuestOverlayDismissPolicy.topLeftPoint(capture.bounds)
                        if (point == null) {
                            log.warn {
                                "SCREEN_RECOVERY_HOME_TASK_OVERLAY_UNRESOLVED reason=invalid-client-bounds " +
                                    "taps=$tapsAttempted pause=${PauseStatus.isPause} ordinaryDispatch=blocked"
                            }
                        } else {
                            val permit = StartupQuestOverlayDismissAuthorization.permits.issue(
                                sourcePid,
                                hwnd.toString(),
                                point,
                                System.currentTimeMillis(),
                            )
                            val accepted = if (permit == null) false else runCatching {
                                MouseUtil.leftButtonClickForStartupQuestOverlayRecovery(
                                    point,
                                    capture.bounds,
                                    sourcePid,
                                    hwnd,
                                    permit,
                                )
                            }.onFailure { error ->
                                log.warn(error) {
                                    "SCREEN_RECOVERY_HOME_TASK_OVERLAY_CLICK_FAILED " +
                                        "reason=${error.javaClass.simpleName} input=unknown failClosed=true"
                                }
                            }.getOrDefault(false)
                            if (permit != null) StartupQuestOverlayRecoveryFence.recordTapAttempt()
                            log.warn {
                                "SCREEN_RECOVERY_HOME_TASK_OVERLAY_TOP_LEFT_TAP tap=" +
                                    "${StartupQuestOverlayRecoveryFence.tapsAttempted()}/${StartupQuestOverlayDismissPolicy.MAX_TAPS} " +
                                    "accepted=$accepted uiAccepted=awaiting-fresh-capture point=$point " +
                                    "pid=$sourcePid hwnd=$hwnd"
                            }
                        }
                    }
                    StartupQuestOverlayDismissPolicy.Action.STOP -> {
                        log.warn {
                            "SCREEN_RECOVERY_HOME_TASK_OVERLAY_UNRESOLVED reason=${decision.reason} " +
                                "taps=$tapsAttempted observed=${detection?.kind?.code ?: "UNKNOWN"} " +
                                "confidence=${detection?.confidence ?: 0} pause=${PauseStatus.isPause} " +
                                "ordinaryDispatch=blocked"
                        }
                        if (decision.reason == "max-taps-reached") {
                            val escalation = StartupQuestOverlayRecoveryFence.escalationDecision(
                                StartupQuestOverlayEscalationPolicy.Evidence(
                                    nowMs = nowMs,
                                    pid = sourcePid,
                                    window = hwnd.toString(),
                                    exactTrustedQuestOverlay = captureStillTrusted &&
                                        captureAgeMs <= StartupQuestOverlayDismissPolicy.MAX_CAPTURE_AGE_MS &&
                                        isExactOverlay,
                                    tapsAttempted = StartupQuestOverlayRecoveryFence.tapsAttempted(),
                                    observations = observations,
                                    noActiveQueue = !activeMatchmaking,
                                    noActiveGameOrTerminal = !gameIntent,
                                    working = WorkTimeListener.working,
                                    paused = PauseStatus.isPause,
                                ),
                            )
                            when (escalation.action) {
                                StartupQuestOverlayEscalationPolicy.Action.RESTART_CLIENT -> {
                                    val restartStillSafe = ScreenRecoveryRuntime.isCurrent(recoveryToken) &&
                                        !PauseStatus.isPause && WorkTimeListener.working &&
                                        Mode.currMode == ModeEnum.HUB && !WarEx.inWar &&
                                        !GameUtil.isTerminalGameState() &&
                                        !LifecycleTrace.hasRecentActiveMatchmakingScreen() &&
                                        !LifecycleTrace.hasRecentMatchmakingInputIntent() &&
                                        GameUtil.findGameProcessIdForDiagnostics() == sourcePid &&
                                        resolveLiveGameWindow()?.toString() == hwnd.toString() &&
                                        GameUtil.isVerifiedCurrentGameWindow(hwnd, sourcePid)
                                    log.warn {
                                        "SCREEN_RECOVERY_HOME_TASK_OVERLAY_ESCALATION " +
                                            "action=${if (restartStillSafe) "RESTART_CLIENT" else "WAIT"} " +
                                            "request=${if (restartStillSafe) "idle-watchdog" else "none"} " +
                                            "restartGuard=$restartStillSafe " +
                                            "reason=${escalation.reason} attempt=${escalation.restartAttempt} " +
                                            "thresholdMs=${StartupQuestOverlayEscalationPolicy.DEFAULT_STUCK_THRESHOLD_MS} " +
                                            "pid=$sourcePid hwnd=$hwnd pause=${PauseStatus.isPause}"
                                    }
                                    if (restartStillSafe) {
                                        Core.restartFromIdleWatchdog()
                                    }
                                }
                                StartupQuestOverlayEscalationPolicy.Action.ALERT_OPERATOR -> {
                                    log.warn {
                                        "SCREEN_RECOVERY_HOME_TASK_OVERLAY_ESCALATION " +
                                            "action=ALERT_OPERATOR reason=${escalation.reason} " +
                                            "attempts=${escalation.restartAttempt} pause=${PauseStatus.isPause} " +
                                            "ordinaryDispatch=blocked"
                                    }
                                    SystemUtil.notice(
                                        "The Hearthstone startup quest overlay remains unresolved after bounded " +
                                            "verified dismissal and restart attempts. Automation remains unpaused " +
                                            "but gameplay actions stay fenced until a verified screen transition.",
                                        forceNotify = true,
                                    )
                                }
                                StartupQuestOverlayEscalationPolicy.Action.WAIT,
                                StartupQuestOverlayEscalationPolicy.Action.ALREADY_ALERTED,
                                -> log.info {
                                    "SCREEN_RECOVERY_HOME_TASK_OVERLAY_ESCALATION " +
                                        "action=${escalation.action} reason=${escalation.reason} " +
                                        "attempts=${escalation.restartAttempt} pause=${PauseStatus.isPause}"
                                }
                            }
                        }
                    }
                }
                if (StartupQuestOverlayRecoveryFence.isActive()) {
                    scheduleHomeTaskOverlayRecovery(
                        recoveryToken,
                        if (decision.action == StartupQuestOverlayDismissPolicy.Action.TAP_TOP_LEFT) 450L else 10_000L,
                    )
                }
            } finally {
                StartupQuestOverlayRecoveryFence.finishObservation()
            }
        }, delayMs, TimeUnit.MILLISECONDS)
        ScreenRecoveryRuntime.track(recoveryToken, task)
    }

    private fun apply(
        detection: Detection,
        recoveryToken: Long,
        currentSessionReady: Boolean,
        resultPageEvidenceVisible: Boolean,
    ): Boolean {
        val observationIsCurrent = ScreenRecoveryRuntime.isCurrent(recoveryToken)
        if (!observationIsCurrent) return false
        // A strongly identified cannot-reconnect modal is authoritative over a
        // stale in-game flag: that match can no longer be resumed in-place.
        if (WarEx.inWar && currentSessionReady && detection.kind !in setOf(
                ScreenKind.RECONNECT,
                ScreenKind.RECONNECT_SPINNER,
                ScreenKind.RECONNECT_FAILURE,
            )
        ) {
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
                ScreenKind.RANK_PROGRESS_CONTINUATION,
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
                if (detection.evidence != "startup-your-quests-overlay" ||
                    detection.confidence < StartupQuestOverlayDismissPolicy.MIN_CONFIDENCE
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_HOME_TASK_OVERLAY_UNRESOLVED " +
                            "reason=initial-overlay-evidence-not-exact confidence=${detection.confidence} " +
                            "evidence=${detection.evidence} pause=${PauseStatus.isPause} " +
                            "ordinaryDispatch=unchanged"
                    }
                    return false
                }
                StartupQuestOverlayRecoveryFence.begin()
                Mode.recover(ModeEnum.HUB, "visible-home-task-overlay", enterStrategy = false)
                log.warn {
                    "SCREEN_RECOVERY_ACTION_REQUESTED screen=HOME_TASK_OVERLAY " +
                        "action=FRESH_CAPTURE_THEN_TOP_LEFT_TAP maxTaps=${StartupQuestOverlayDismissPolicy.MAX_TAPS} " +
                        "pause=${PauseStatus.isPause} ordinaryDispatch=blocked"
                }
                scheduleHomeTaskOverlayRecovery(
                    recoveryToken = recoveryToken,
                    delayMs = 300L,
                )
            }
            ScreenKind.DECK_SELECTION -> {
                if (!FreshDeckSelectionConfirmationPolicy.isConfirmed(
                        screenKind = detection.kind.code,
                        confidence = detection.confidence,
                        evidence = detection.evidence,
                        freshObservation = observationIsCurrent,
                    )
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_DECK_SELECTION_UNCONFIRMED confidence=${detection.confidence} " +
                            "evidence=${detection.evidence} freshObservation=$observationIsCurrent " +
                            "dispatch=false"
                    }
                    return false
                }
                val guard = club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
                if (!guard.isPending() && guard.isTerminalCleanupPending()) {
                    if (!guard.confirmAtFreshDeckSelection(
                            screenKind = detection.kind.code,
                            confidence = detection.confidence,
                            visualEvidence = detection.evidence,
                            freshObservation = observationIsCurrent,
                        )
                    ) {
                        log.warn {
                            "SCREEN_RECOVERY_DECK_SELECTION_BLOCKED reason=terminal-cleanup-proof-or-freshness " +
                                "confidence=${detection.confidence} evidence=${detection.evidence} dispatch=false"
                        }
                        return false
                    }
                    log.info {
                        "TERMINAL_CLEANUP_FENCE_RELEASED evidence=fresh-authorized-DECK_SELECTION " +
                            "confidence=${detection.confidence} visualEvidence=${detection.evidence} " +
                            "rankPreflightRequired=true"
                    }
                }
                val surrenderCompletion =
                    club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderDeckSelectionRecovery
                        .completeIfRequired(
                            screenKind = detection.kind.code,
                            confidence = detection.confidence,
                            visualEvidence = detection.evidence,
                            freshObservation = observationIsCurrent,
                        )
                if (surrenderCompletion ==
                    club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderDeckSelectionRecovery.Result.BLOCKED
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_DECK_SELECTION_BLOCKED reason=rank-surrender-terminal-proof-missing " +
                            "terminalCleanupAuthorized=${guard.hasTerminalCleanupCapability()} " +
                            "confidence=${detection.confidence} evidence=${detection.evidence} " +
                            "barrier=${club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier.currentState()} " +
                            "dispatch=false pause=false"
                    }
                    return false
                }
                if (surrenderCompletion ==
                    club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderDeckSelectionRecovery.Result.COMPLETED
                ) {
                    // apply() is reached only from the current, foreground- and
                    // PID-verified capture in inspectAndRecover. Require the
                    // terminal capability already issued for this surrender;
                    // a deck-selection screenshot alone must not manufacture
                    // terminal authority or release an unresolved game.
                    log.info {
                        "RANK_SURRENDER_RECOVERY_COMPLETED evidence=SCREEN_DECK_SELECTION " +
                            "terminalCleanupAuthorized=true confidence=${detection.confidence} " +
                            "visualEvidence=${detection.evidence} requeueAllowed=true rankPreflightRequired=true"
                    }
                }
                if (!guard.confirmAtFreshDeckSelection(
                        screenKind = detection.kind.code,
                        confidence = detection.confidence,
                        visualEvidence = detection.evidence,
                        freshObservation = observationIsCurrent,
                    )
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_DECK_SELECTION_BLOCKED reason=terminal-cleanup-proof-or-freshness " +
                            "confidence=${detection.confidence} evidence=${detection.evidence} " +
                            "dispatch=false"
                    }
                    return false
                }
                if (!PowerLogListener.confirmCurrentGameTerminalDestination(
                        screenKind = detection.kind.code,
                        confidence = detection.confidence,
                        freshCaptureAuthorized = observationIsCurrent,
                        resultPageEvidenceVisible = resultPageEvidenceVisible,
                    )
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_DECK_SELECTION_BLOCKED reason=current-game-terminal-input-fence " +
                            "confidence=${detection.confidence} freshObservation=$observationIsCurrent dispatch=false"
                    }
                    return false
                }
                if (DeckStrategyManager.currentDeckStrategy == null ||
                    DeckStrategyManager.currentRunMode == null
                ) {
                    log.warn { "SCREEN_RECOVERY_DECK_SELECTION_SKIPPED reason=no-selected-strategy" }
                    return false
                }
                Mode.recover(ModeEnum.TOURNAMENT, "visible-deck-selection", enterStrategy = false)
                val startupStartReady = ActionDispatchGate.isFreshConfiguredDeckSelectionStartProofReady()
                if (!startupStartReady) {
                    log.warn {
                        "SCREEN_RECOVERY_DECK_SELECTION_BLOCKED reason=mode-specific-startup-proof-missing " +
                            "expectedMode=${DeckStrategyManager.currentRunMode?.name ?: "UNKNOWN"} " +
                            "observedMode=UNKNOWN action=NO_DECK_SELECTION_NO_START"
                    }
                    return false
                }
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
                        val currentProof = ActionDispatchGate.isFreshConfiguredDeckSelectionStartProofReady()
                        val dispatched = StartupInputAuthorizationPolicy.dispatchDeckSelectionRecoveryIfReady(
                            startupScreenConfirmed = currentProof,
                        ) {
                            TournamentModeStrategy.recoverDeckSelectionAndStart()
                        }
                        if (!dispatched) {
                            log.warn {
                                "SCREEN_RECOVERY_DECK_SELECTION_BLOCKED reason=start-proof-expired-before-dispatch " +
                                    "action=NO_DECK_SELECTION_NO_START"
                            }
                        }
                    }
                }, 300, TimeUnit.MILLISECONDS)
            }

            ScreenKind.RESULT -> {
                Mode.recover(ModeEnum.GAMEPLAY, "visible-result-screen", enterStrategy = false)
                log.warn { "SCREEN_RECOVERY_ACTION_REQUESTED screen=RESULT next=DISMISS_STALE_RESULT" }
                val cleanupCapability = club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
                    .existingTerminalCleanupCapability()
                GameUtil.dismissStaleGameEndScreen(
                    resultAlreadyObserved = true,
                    terminalCleanupCapability = cleanupCapability,
                )
            }

            ScreenKind.RANK_PROGRESS_CONTINUATION -> {
                val cleanupCapability = club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
                    .existingTerminalCleanupCapability()
                if (club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
                        .isTerminalCleanupCapabilityValid(cleanupCapability)
                ) {
                    Mode.recover(ModeEnum.GAMEPLAY, "visible-post-result-rank-progress", enterStrategy = false)
                    log.info { "SCREEN_RECOVERY_ACTION_REQUESTED screen=RANK_PROGRESS_CONTINUATION action=CONTINUE_WITH_TERMINAL_CAPABILITY" }
                    GameUtil.dismissStaleGameEndScreen(
                        resultAlreadyObserved = true,
                        terminalCleanupCapability = cleanupCapability,
                    )
                } else {
                    log.warn {
                        "SCREEN_RECOVERY_RANK_PROGRESS_BLOCKED reason=terminal-cleanup-capability-required " +
                            "dispatch=false"
                    }
                }
            }

            ScreenKind.MATCHMAKING -> {
                if (BuildChannel.identityToken(BuildInfo.RELEASE_CHANNEL) == "beta" &&
                    !UpstreamScreenStateRecovery.confirmPendingTerminalCleanupAtMatchmaking(
                        screenKind = detection.kind.code,
                        confidence = detection.confidence,
                        freshCaptureAuthorized = observationIsCurrent,
                        resultPageEvidenceVisible = resultPageEvidenceVisible,
                    )
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_MATCHMAKING_BLOCKED reason=terminal-cleanup-or-input-fence " +
                            "confidence=${detection.confidence} freshObservation=$observationIsCurrent dispatch=false"
                    }
                    return false
                }
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
                // A fresh exact reconnect capture replaces the prior active
                // queue-screen observation. Live-game and terminal proof are
                // checked independently below.
                val staleQueueEvidenceCleared = LifecycleTrace.supersedeActiveMatchmakingScreenForReconnect()
                if (staleQueueEvidenceCleared) {
                    log.info {
                        "SCREEN_RECOVERY_RECONNECT_QUEUE_HISTORY_CLEARED reason=exact-current-reconnect " +
                            "staleIntent=${LifecycleTrace.hasRecentMatchmakingInputIntent()} " +
                            "action=CONTINUE_RECONNECT_AGEING"
                    }
                }
                val freshActiveQueue = LifecycleTrace.hasRecentActiveMatchmakingScreen()
                val currentSessionReady = isCurrentGameSessionReadyForStart()
                val activeMatchmaking = freshActiveQueue || currentSessionReady && WarEx.inWar
                val activeGameEvidence = hasAuthoritativeCurrentGameEvidenceForReconnect()
                val terminalState = GameUtil.isTerminalGameState() ||
                    club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
                        .isTerminalCleanupPending()
                if (!ReconnectRestartSafetyPolicy.mayRestart(
                        exactReconnectVisible = true,
                        activeGame = activeGameEvidence,
                        activeMatchmaking = activeMatchmaking,
                        terminalState = terminalState,
                        currentSessionReady = currentSessionReady,
                    )
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_RECONNECT_DEFERRED reason=${if (terminalState) "terminal" else "active-matchmaking"} " +
                            "action=NO_INPUT_NO_RESTART staleQueueEvidenceCleared=$staleQueueEvidenceCleared " +
                            "staleMatchmakingIntentIgnored=${LifecycleTrace.hasRecentMatchmakingInputIntent()}"
                    }
                    return false
                }
                Mode.recover(ModeEnum.LOGIN, "visible-reconnect-screen", enterStrategy = false)
                val now = System.currentTimeMillis()
                val reconnectDecision = offlineReconnectRecovery.observe(
                    OfflineReconnectRecovery.Screen.OFFLINE_PROMPT,
                    now,
                )
                val restartDecision = indefiniteReconnectRestartPolicy.observe(
                    exactReconnectVisible = true,
                    nowMs = now,
                    activeQueue = activeMatchmaking,
                    terminalState = terminalState,
                )
                log.warn {
                    "SCREEN_RECOVERY_RECONNECT_STATE state=${reconnectDecision.state} " +
                        "action=${reconnectDecision.action} attempt=${reconnectDecision.attempt} " +
                        "reason=${reconnectDecision.reason} restartDecision=$restartDecision result=prompt-detected"
                }
                when (restartDecision) {
                    IndefiniteReconnectRestartPolicy.Decision.RESTART_CLIENT_NO_PAUSE -> {
                        log.error {
                            "SCREEN_RECOVERY_RECONNECT_ESCALATED reason=persistent-exact-offline-prompt " +
                                "thresholdMs=${IndefiniteReconnectRestartPolicy.DEFAULT_THRESHOLD_MS} " +
                                "action=RESTART_CLIENT_NO_INPUT_NO_PAUSE"
                        }
                        if (ScreenRecoveryRuntime.isCurrent(recoveryToken)) Core.restartFromIdleWatchdog()
                        return true
                    }
                    IndefiniteReconnectRestartPolicy.Decision.ALERT_OPERATOR -> {
                        log.error { "SCREEN_RECOVERY_RECONNECT_RESTART_EXHAUSTED action=NO_PAUSE_NO_INPUT" }
                        return false
                    }
                    IndefiniteReconnectRestartPolicy.Decision.DEFER_ACTIVE_QUEUE,
                    IndefiniteReconnectRestartPolicy.Decision.DEFER_TERMINAL -> return false
                    IndefiniteReconnectRestartPolicy.Decision.WAIT -> Unit
                }
                val acceptedReconnectAt = reconnectAcceptedAt.get()
                if (acceptedReconnectAt > 0L &&
                    shouldRestartStalledReconnect(acceptedReconnectAt, now) &&
                    reconnectAcceptedAt.compareAndSet(acceptedReconnectAt, 0L)
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_ACTION_REQUESTED screen=RECONNECT action=RESTART_CLIENT " +
                            "reason=stalled-reconnect elapsedMs=${now - acceptedReconnectAt} " +
                            "thresholdMs=$STALLED_RECONNECT_RESTART_MS"
                    }
                    // Some client builds leave the original disconnect dialog
                    // visible after consuming the reconnect click.  Do not
                    // wait for the optional slow-loading warning: once the
                    // confirmed reconnect attempt has been stuck for the
                    // recovery threshold, restart the client directly.
                    if (ScreenRecoveryRuntime.isCurrent(recoveryToken)) Core.restartFromIdleWatchdog()
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
                            Mode.currMode == ModeEnum.LOGIN &&
                            (!currentSessionReady || !GameUtil.isTerminalGameState())
                        ) {
                            // This is the upstream reconnect input primitive;
                            // skip the pre-click right-click because the
                            // offline page is not a card/targeting state.
                            val point = GameUtil.RECONNECT_RECT.getCenterClickPos()
                            val liveHwnd = resolveLiveGameWindow()
                            val livePid = GameUtil.findGameProcessIdForDiagnostics()?.toInt()
                            val preSessionReconnect = !currentSessionModelReady()
                            val freshOfflinePrompt = if (preSessionReconnect) {
                                liveHwnd != null && livePid != null && !WarEx.inWar &&
                                    !GameUtil.isTerminalGameState() &&
                                    !LifecycleTrace.hasRecentActiveMatchmakingScreen() &&
                                    !LifecycleTrace.hasRecentMatchmakingInputIntent() &&
                                    confirmFreshReconnectPrompt(
                                        recoveryToken = recoveryToken,
                                        expectedPid = livePid,
                                        expectedHwnd = liveHwnd,
                                    ) && StartupInputReadiness.issueReconnectPermit(
                                        pid = livePid,
                                        hwnd = liveHwnd.toString(),
                                        point = point,
                                        now = System.currentTimeMillis(),
                                    )
                            } else true
                            val accepted = if (freshOfflinePrompt) {
                                MouseUtil.leftButtonClickForRecovery(
                                    point,
                                    preSessionReconnect = preSessionReconnect,
                                )
                            } else false
                            val result = offlineReconnectRecovery.reportReconnectDispatched(
                                System.currentTimeMillis(),
                                accepted,
                            )
                            log.info {
                                "SCREEN_RECOVERY_RECONNECT_DISPATCHED input=recovery-sendinput " +
                                    "accepted=$accepted state=${result.state} action=${result.action}"
                            }
                            if (accepted) {
                                if (preSessionReconnect && livePid != null && liveHwnd != null) {
                                    StartupInputReadiness.consumeReconnectPermit(
                                        livePid,
                                        liveHwnd.toString(),
                                        point,
                                        System.currentTimeMillis(),
                                    )
                                }
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
                } else if (acceptedReconnectAt <= 0L &&
                    reconnectDecision.action == OfflineReconnectRecovery.Action.ESCALATE
                ) {
                    log.error {
                        "SCREEN_RECOVERY_RECONNECT_EXHAUSTED reason=${reconnectDecision.reason} " +
                            "attempt=${reconnectDecision.attempt} action=RESTART_CLIENT_NO_PAUSE"
                    }
                    if (ScreenRecoveryRuntime.isCurrent(recoveryToken)) Core.restartFromIdleWatchdog()
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
                                if (ScreenRecoveryRuntime.isCurrent(recoveryToken)) Core.restartFromIdleWatchdog()
                            }
                            ReconnectFailureRecoveryPolicy.Decision.WAIT_FOR_RESTART -> log.info {
                                "SCREEN_RECOVERY_RECONNECT_FAILURE_WAIT reason=restart-cooldown " +
                                    "attempt=${recovery.attempt} " +
                                    "cooldownMs=${ReconnectFailureRecoveryPolicy.DEFAULT_RETRY_COOLDOWN_MS}"
                            }
                            ReconnectFailureRecoveryPolicy.Decision.ALERT_OPERATOR -> {
                                log.error {
                                    "SCREEN_RECOVERY_RECONNECT_FAILURE_ALERT " +
                                        "reason=restart-attempts-exhausted attempts=${recovery.attempt} " +
                                        "paused=${PauseStatus.isPause} action=NO_AUTO_PAUSE"
                                }
                                SystemUtil.notice(
                                    "Hearthstone could not reconnect after bounded restarts. Check the game client " +
                                        "or click Start to retry; automation was not paused.",
                                )
                            }
                        }
                    } else {
                        log.info { "SCREEN_RECOVERY_RECONNECT_FAILURE_SKIPPED reason=state-changed" }
                    }
                }, 300, TimeUnit.MILLISECONDS)
            }

            ScreenKind.RECONNECT_SPINNER -> {
                val freshActiveQueue = LifecycleTrace.hasRecentActiveMatchmakingScreen()
                val currentSessionReady = isCurrentGameSessionReadyForStart()
                val activeMatchmaking = freshActiveQueue || currentSessionReady && WarEx.inWar
                val terminalState = GameUtil.isTerminalGameState() ||
                    club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
                        .isTerminalCleanupPending()
                if (!ReconnectRestartSafetyPolicy.mayRestart(
                        exactReconnectVisible = true,
                        activeMatchmaking = activeMatchmaking,
                        terminalState = terminalState,
                        currentSessionReady = currentSessionReady,
                    )
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_RECONNECT_DEFERRED screen=RECONNECT_SPINNER " +
                            "reason=${if (terminalState) "terminal" else "active-matchmaking"} " +
                            "action=NO_INPUT_NO_RESTART"
                    }
                    return false
                }
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
                    OfflineReconnectRecovery.Action.WAIT_FOR_SPINNER ->
                        scheduleReconnectSpinnerProbe(recoveryToken)

                    OfflineReconnectRecovery.Action.ESCALATE -> {
                        log.error {
                            "SCREEN_RECOVERY_RECONNECT_ESCALATED state=${decision.state} " +
                                "reason=${decision.reason} result=spinner-still-visible " +
                                "action=RESTART_CLIENT_NO_CANCEL_NO_PAUSE"
                        }
                        if (ScreenRecoveryRuntime.isCurrent(recoveryToken)) {
                            Core.restartFromIdleWatchdog()
                        }
                    }

                    else -> Unit
                }
            }

            ScreenKind.SERVER_UNAVAILABLE -> {
                Mode.recover(ModeEnum.STARTUP, "visible-server-unavailable-page", enterStrategy = false)
                log.warn {
                    "SCREEN_RECOVERY_CLASSIFIED screen=SERVER_UNAVAILABLE action=NO_INPUT_NO_RESTART " +
                        "currentSessionReady=$currentSessionReady"
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
                            "thresholdMs=${if (slowReconnectStalled) STALLED_RECONNECT_RESTART_MS else STALLED_LOADING_RESTART_MS}"
                    }
                    // Reuse the fatal-error recovery primitive. It pauses
                    // input, launches a fresh client, then lets the starter
                    // rediscover that window. This also covers a reconnect
                    // dialog whose text OCR was missed and was classified
                    // only by the loading visual.
                    if (ScreenRecoveryRuntime.isCurrent(recoveryToken)) Core.restartFromIdleWatchdog()
                } else {
                    log.info {
                        "SCREEN_RECOVERY_ACTION_REQUESTED screen=LOADING action=WAIT_FOR_CLIENT " +
                            "loadingObservedForMs=${now - loadingStartedAt} " +
                            "restartThresholdMs=${if (detection.evidence == "reconnect-slow-loading-text") STALLED_RECONNECT_RESTART_MS else STALLED_LOADING_RESTART_MS}"
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
