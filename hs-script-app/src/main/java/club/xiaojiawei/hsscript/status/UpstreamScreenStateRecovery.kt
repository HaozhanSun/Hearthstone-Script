package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.consts.CHI_SIM_DATA
import club.xiaojiawei.hsscript.consts.TESS_DATA_PATH
import club.xiaojiawei.hsscript.core.Core
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.strategy.mode.LoginModeStrategy
import club.xiaojiawei.hsscript.strategy.mode.TournamentModeStrategy
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.MouseUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscriptbase.const.BuildInfo
import club.xiaojiawei.hsscriptbase.const.BuildChannel
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.User32
import com.sun.jna.ptr.IntByReference
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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
object UpstreamScreenStateRecovery {

    private const val MAX_OCR_TEXT_LENGTH = 500
    private const val OCR_MAX_WIDTH = 1280
    private const val RECONNECT_RETRY_INTERVAL_MS = 60_000L
    /**
     * The client displays a distinct slow-connection warning only after a
     * reconnect attempt. A fresh script can attach after that click, so its
     * first observation of the same exact warning also starts this timer.
     * Give it two minutes before using the established client restart path.
     */
    private const val STALLED_RECONNECT_LOADING_RESTART_MS = 60_000L
    private val reconnectAttemptAt = AtomicLong(0L)
    private val reconnectAcceptedAt = AtomicLong(0L)
    private val reconnectClickConsumed = AtomicBoolean(false)
    private val reconnectAttemptProcessId = AtomicLong(0L)
    private val slowReconnectWarningObservedAt = AtomicLong(0L)
    private val unknownStartupRestartPolicy = UnknownStartupRestartPolicy()
    private val stuckScreenUnblockPolicy = StuckScreenUnblockPolicy()
    private val indefiniteReconnectRestartPolicy = IndefiniteReconnectRestartPolicy()
    private val exactReconnectRecheckSchedule = ExactReconnectRecheckSchedule()
    private val clientRestartInFlight = ClientRestartRequestGate()
    private val exactCaptureOcrReuse = ExactCaptureOcrReuse()
    private val eventQuestOverlayRecoveryInFlight = AtomicBoolean(false)
    private val eventQuestOverlayRecoveryPolicy = EventQuestOverlayRecoveryPolicy()
    private val startupDeckModeNavigationAttempts = StartupDeckModeNavigationAttemptGate()
    private val stuckScreenUnblockRecheckGate = StuckScreenUnblockRecheckGate()
    private val stuckScreenUnblockBatchInFlight = AtomicBoolean(false)

    private enum class ScreenKind(val code: String) {
        DECK_SELECTION("DECK_SELECTION"),
        TOURNAMENT_MODE_SELECTOR(StartupDeckModeNavigationPolicy.MODE_SELECTOR_SCREEN),
        HOME("HOME"),
        TOURNAMENT("TOURNAMENT"),
        MATCHMAKING("MATCHMAKING"),
        RESULT("RESULT"),
        RANK_PROGRESS_CONTINUATION("RANK_PROGRESS_CONTINUATION"),
        RECONNECT("RECONNECT"),
        RECONNECT_FAILURE("RECONNECT_FAILURE"),
        AMAZON_APPSTORE_NOTICE("AMAZON_APPSTORE_NOTICE"),
        EVENT_QUEST_OVERLAY("EVENT_QUEST_OVERLAY"),
        BETA_RESULT_CONFIRMATION("BETA_RESULT_CONFIRMATION"),
        SERVER_UNAVAILABLE("SERVER_UNAVAILABLE"),
        LOGIN("LOGIN"),
        GAME_MODE("GAME_MODE"),
        COLLECTION("COLLECTION"),
        PACK_OPENING("PACK_OPENING"),
        LOADING("LOADING"),
    }

    private val POST_RESULT_DESTINATIONS = setOf(
        ScreenKind.DECK_SELECTION,
        ScreenKind.HOME,
        ScreenKind.TOURNAMENT,
        ScreenKind.MATCHMAKING,
        ScreenKind.LOGIN,
        ScreenKind.GAME_MODE,
        ScreenKind.COLLECTION,
        ScreenKind.PACK_OPENING,
    )

    private data class Capture(
        val image: BufferedImage,
        val bounds: Rectangle,
        val file: File?,
        val visual: VisualSignature,
        val gameRectKnown: Boolean,
        val gameWindowKnown: Boolean,
        val purpose: ScreenRecoveryCapturePurpose,
        val capturedAtNanos: Long,
        val processId: Long = 0L,
        val windowId: String = "",
        val resultProbeTimings: ResultProbeCaptureTimings = ResultProbeCaptureTimings(),
    )

    private data class AuthorizedCapture(
        val gameWindow: WinDef.HWND,
        val capture: Capture,
        val purpose: ScreenRecoveryCapturePurpose,
        val foregroundVerified: Boolean,
    )

    private data class VisualSignature(
        val sampleHash: Long,
        val warmRatio: Double,
        val blueRatio: Double,
        val loadingCentralDarkRatio: Double,
        val resultContinueGrayLightRatio: Double,
        val resultBannerLowSaturationRatio: Double,
        val resultBannerWarmRatio: Double = 0.0,
        val reconnectDialogVisual: Boolean = false,
        val matchmakingSearchVisual: Boolean = false,
        val hubModeChooserVisual: Boolean = false,
        val amazonAppstoreSunsetNoticeVisual: Boolean = false,
        val startupQuestOverlayVisual: Boolean = false,
        val battleNetLauncherVisual: Boolean = false,
        val tournamentModeSelectorVisual: Boolean = false,
    ) {
        override fun toString(): String =
            "hash=${java.lang.Long.toUnsignedString(sampleHash, 16)} " +
                "warmRatio=${"%.3f".format(Locale.ROOT, warmRatio)} " +
                "blueRatio=${"%.3f".format(Locale.ROOT, blueRatio)} " +
                "loadingCentralDark=${"%.3f".format(Locale.ROOT, loadingCentralDarkRatio)} " +
                "resultContinueGrayLight=${"%.3f".format(Locale.ROOT, resultContinueGrayLightRatio)} " +
                "resultBannerLowSaturation=${"%.3f".format(Locale.ROOT, resultBannerLowSaturationRatio)} " +
                "resultBannerWarm=${"%.3f".format(Locale.ROOT, resultBannerWarmRatio)} " +
                "reconnectDialogVisual=$reconnectDialogVisual " +
                "matchmakingSearchVisual=$matchmakingSearchVisual " +
                "hubModeChooserVisual=$hubModeChooserVisual " +
                "amazonAppstoreSunsetNoticeVisual=$amazonAppstoreSunsetNoticeVisual " +
                "startupQuestOverlayVisual=$startupQuestOverlayVisual " +
                "battleNetLauncherVisual=$battleNetLauncherVisual " +
                "tournamentModeSelectorVisual=$tournamentModeSelectorVisual"
    }

    private data class RegionSignal(
        val grayLightRatio: Double,
        val lowSaturationRatio: Double,
        val darkRatio: Double,
        val warmRatio: Double = 0.0,
    )

    private data class Detection(
        val kind: ScreenKind,
        val mode: ModeEnum,
        val confidence: Int,
        val evidence: String,
    )

    /**
     * A fresh, authoritative return to deck selection completes the visual
     * half of a mandatory rank surrender. The Power.log terminal capability
     * is still required by the guard; a screenshot by itself cannot release
     * the queue barrier.
     */
    internal fun completeMandatoryRankSurrenderAtDeckSelection(
        screenKind: String,
        confidence: Int,
        visualEvidence: String,
        freshObservation: Boolean,
    ): club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderDeckSelectionRecovery.Result =
        club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
            screenKind = screenKind,
            confidence = confidence,
            visualEvidence = visualEvidence,
            freshObservation = freshObservation,
        )

    /**
     * Inspect the visible client and, if possible, move the state machine to
     * the detected screen. Returns true only when a state/action was applied.
     */
    fun inspectAndRecover(
        stuckForMs: Long,
        stateFingerprint: String,
        startupProbe: Boolean = false,
        exactReconnectRecheck: Boolean = false,
        stuckScreenUnblockRecheck: Boolean = false,
        stuckScreenUnblockRecheckToken: Long? = null,
        stateStillCurrent: () -> Boolean = { true },
    ): Boolean {
        val gateNow = System.currentTimeMillis()
        val timedOutRecheck = stuckScreenUnblockRecheckGate.expireIfTimedOut(gateNow)
        if (timedOutRecheck != null) {
            log.error {
                "STUCK_SCREEN_UNBLOCK_RECHECK result=TIMEOUT generation=${timedOutRecheck.generation} " +
                    "elapsedMs=${gateNow - timedOutRecheck.startedAtMs} action=RELEASE_GATE_CONTINUE_BOUNDED_RECOVERY"
            }
        }
        if (stuckScreenUnblockRecheck) {
            if (stuckScreenUnblockRecheckToken == null ||
                !stuckScreenUnblockRecheckGate.isCurrentGeneration(stuckScreenUnblockRecheckToken, gateNow)
            ) {
                log.warn {
                    "STUCK_SCREEN_UNBLOCK_RECHECK result=STALE_CALLBACK " +
                        "generation=${stuckScreenUnblockRecheckToken ?: "none"} action=NO_INPUT"
                }
                return false
            }
        } else if (stuckScreenUnblockRecheckGate.isInFlight(gateNow)) {
            log.warn {
                "SCREEN_RECOVERY_SKIPPED reason=stuck-screen-post-click-recheck-in-flight " +
                    "state=$stateFingerprint"
            }
            return false
        }
        if (!WorkTimeListener.working || PauseStatus.isPause) {
            log.info {
                "SCREEN_RECOVERY_SKIPPED reason=unsafe " +
                    "working=${WorkTimeListener.working} paused=${PauseStatus.isPause}"
            }
            return false
        }

        val probeId = ResultScreenProbeTelemetry.nextProbeId()
        val probeStartedAt = System.nanoTime()
        val captureStartedAt = System.nanoTime()
        var captureTimings = ResultProbeCaptureTimings()
        var ocrTimings = ResultProbeOcrTimings()
        var decisionMs = 0L
        val authorizedCapture = captureVerifiedGameScreen(stuckForMs, startupProbe, exactReconnectRecheck)
        if (authorizedCapture == null) {
            captureTimings = ResultProbeCaptureTimings(
                authorizedCaptureMs = ResultScreenProbeTelemetry.elapsedMs(captureStartedAt),
            )
            log.info {
                "SCREEN_RECOVERY_PROBE_TIMING " + ResultScreenProbeTelemetry.formatScreenRecovery(
                    probeId, captureTimings, ocrTimings, decisionMs,
                    ResultScreenProbeTelemetry.elapsedMs(probeStartedAt), null, 0, null, false,
                )
            }
            log.warn {
                "SCREEN_RECOVERY_FAILED reason=verified-game-capture-unavailable stuckForMs=$stuckForMs " +
                    "state=$stateFingerprint"
            }
            return false
        }
        val capture = authorizedCapture.capture
        captureTimings = capture.resultProbeTimings

        log.warn {
            "SCREEN_RECOVERY_TRIGGER stuckForMs=$stuckForMs state=$stateFingerprint " +
                "bounds=${capture.bounds} screenshot=${capture.file?.absolutePath ?: "not-saved"} " +
                "screenshotLink=${capture.file?.toURI()?.toString() ?: "none"} " +
                "visual=${capture.visual}"
        }

        val questOverlayVisual = capture.visual.startupQuestOverlayVisual
        val deckSelectionVisualCandidate = authorizedCapture.purpose in setOf(
            ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
            ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
        ) && !questOverlayVisual && !capture.visual.matchmakingSearchVisual &&
            !ScreenStateRecovery.hasCenteredModalPanel(capture.image) &&
            DeckStrategyManager.currentDeckStrategy != null && DeckStrategyManager.currentRunMode != null &&
            ScreenWatchdog.hasEnabledDeckSelectionStartButton(capture.image)
        val ocrText = when {
            questOverlayVisual -> ""
            deckSelectionVisualCandidate -> {
                log.info { "SCREEN_RECOVERY_OCR_SKIPPED reason=exact-deck-selection-start-control-visual" }
                ""
            }
            else -> runOCR(capture, File(TESS_DATA_PATH)) { ocrTimings = it }
        }
        if (!stateStillCurrent()) {
            log.info {
                "SCREEN_RECOVERY_PROBE_TIMING " + ResultScreenProbeTelemetry.formatScreenRecovery(
                    probeId, captureTimings, ocrTimings, decisionMs,
                    ResultScreenProbeTelemetry.elapsedMs(probeStartedAt), null, 0, null, true,
                ) + " path=STATE_CHANGED"
            }
            log.info { "SCREEN_RECOVERY_SKIPPED reason=state-changed-during-inspection state=$stateFingerprint" }
            return false
        }
        val decisionStartedAt = System.nanoTime()
        val ocrDetection = detect(
            ocrText,
            capture.visual,
            ScreenStateRecovery.hasCenteredModalPanel(capture.image),
            capture.image,
        )
        val detection = ocrDetection ?: startupDeckSelectionVisualDetection(
            purpose = authorizedCapture.purpose,
            image = capture.image,
            ocrUnknown = ocrDetection == null,
            questOverlayVisible = capture.visual.startupQuestOverlayVisual,
            matchmakingVisible = capture.visual.matchmakingSearchVisual,
            modalVisible = ScreenStateRecovery.hasCenteredModalPanel(capture.image) &&
                !ScreenStateRecovery.isWrongModeDeckTooltip(capture.image),
            configuredDeckSelected = DeckStrategyManager.currentDeckStrategy != null &&
                DeckStrategyManager.currentRunMode != null,
        )
        decisionMs = ResultScreenProbeTelemetry.elapsedMs(decisionStartedAt)
        log.info {
            "SCREEN_RECOVERY_PROBE_TIMING " + ResultScreenProbeTelemetry.formatScreenRecovery(
                probeId, captureTimings, ocrTimings, decisionMs,
                ResultScreenProbeTelemetry.elapsedMs(probeStartedAt), detection?.kind?.code,
                detection?.confidence ?: 0, detection?.evidence, true,
            ) + " purpose=${authorizedCapture.purpose}"
        }
        val expectedDeckModeForUnblock = DeckStrategyManager.currentRunMode?.name
        val observedDeckModeForUnblock = if (detection?.kind == ScreenKind.DECK_SELECTION) {
            TournamentModeConfirmation.observeImage(capture.image).observedMode.name
        } else null
        val wrongModeDeckCandidate = isConfirmedWrongModeDeck(
            detection, capture.image, expectedDeckModeForUnblock, observedDeckModeForUnblock,
        )
        val safeIdleMenuCandidate = isSafeIdleUnblockScreen(detection, capture.visual) || wrongModeDeckCandidate
        val exactReconnectCandidate = detection?.kind == ScreenKind.RECONNECT && capture.visual.reconnectDialogVisual
        val observedStuckFrame = stuckScreenFrameIdentity(
            capture = capture,
            windowId = authorizedCapture.gameWindow.toString(),
            detection = detection,
            expectedMode = expectedDeckModeForUnblock,
            exactReconnect = exactReconnectCandidate,
        )
        val observedStuckFingerprint = observedStuckFrame.fingerprint
        val sameScreenLadderInProgress = stuckScreenUnblockPolicy.hasProgressFor(observedStuckFingerprint)
        if (detection != null) unknownStartupRestartPolicy.reset()
        if (detection != null &&
            !((safeIdleMenuCandidate || exactReconnectCandidate) &&
                (stuckForMs >= StuckScreenUnblockPolicy.DEFAULT_FIRST_THRESHOLD_MS || sameScreenLadderInProgress))
        ) {
            if (stuckScreenUnblockPolicy.hasProgress()) {
                log.warn {
                    "STUCK_SCREEN_UNBLOCK_RECHECK result=UNBLOCKED newScreen=${detection.kind.code} " +
                        "evidence=${detection.evidence} identityReason=${observedStuckFrame.reason} action=STOP_LADDER"
                }
            }
            stuckScreenUnblockPolicy.reset()
        }
        if (detection?.kind in setOf(
                ScreenKind.DECK_SELECTION,
                ScreenKind.HOME,
                ScreenKind.TOURNAMENT,
                ScreenKind.MATCHMAKING,
                ScreenKind.LOGIN,
                ScreenKind.GAME_MODE,
                ScreenKind.COLLECTION,
                ScreenKind.PACK_OPENING,
            )
        ) {
            indefiniteReconnectRestartPolicy.reset()
            reconnectAttemptAt.set(0L)
            reconnectAcceptedAt.set(0L)
            reconnectClickConsumed.set(false)
        }
        val exactReconnectRecoveryEvidence = detection?.kind == ScreenKind.RECONNECT ||
            detection?.kind == ScreenKind.LOADING && detection.evidence == "reconnect-slow-loading-text"
        if (WarEx.inWar && !exactReconnectRecoveryEvidence) {
            log.info {
                "SCREEN_RECOVERY_SKIPPED reason=active-game-classification-only " +
                    "detected=${detection?.kind?.code ?: "UNKNOWN"} action=NO_INPUT_NO_RESTART"
            }
            return false
        }
        // Stale probes also run before Power.log/session readiness. They must
        // classify a deck chooser as classification-only unless fresh mode
        // proof matches the configured format; treating these as generic stale
        // recovery caused repeated START_MATCHING attempts against Standard.
        val startupClientProbe = StartupDeckModeNavigationPolicy.isModeProofProbe(authorizedCapture.purpose)
        var startupClassificationOnly = false
        val currentPid = GameUtil.findGameProcessIdForDiagnostics()?.toInt()
        if (currentPid != null && currentPid > 0) {
            val previousPid = reconnectAttemptProcessId.getAndSet(currentPid.toLong())
            if (previousPid > 0L && previousPid != currentPid.toLong()) {
                clientRestartInFlight.releaseIfProcessChanged(currentPid.toLong())
            }
        }
        val currentHwnd = ScriptStatus.gameHWND?.toString().orEmpty()
        val activeQueueEvidence = capture.visual.matchmakingSearchVisual ||
            LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
            LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard()
        val startupHubModeChooser = StartupHubModeChooserPolicy.canAuthorizeTraditionalBattleSelection(
            purpose = authorizedCapture.purpose,
            currentSessionReady = ScreenStateRecovery.isCurrentGameSessionReadyForStart(),
            screenKind = detection?.kind?.code,
            confidence = detection?.confidence ?: 0,
            evidence = detection?.evidence,
            currentMode = Mode.currMode?.name,
            modalVisible = ScreenStateRecovery.hasCenteredModalPanel(capture.image),
            activeGame = StartupGamePhaseEvidencePolicy.hasAuthoritativeActiveGame(
                warModelAuthoritative = ScreenStateRecovery.isCurrentGameSessionReadyForStart(),
                warInProgress = WarEx.inWar,
            ),
            activeMatchmaking = activeQueueEvidence,
            trustedCurrentWindow = currentPid != null && currentPid > 0 &&
                currentHwnd == authorizedCapture.gameWindow.toString() &&
                GameUtil.isVerifiedCurrentGameWindow(authorizedCapture.gameWindow),
        )
        if (startupHubModeChooser) {
            // A verified return to the selector ends the previous deck-screen
            // attempt window. A later fresh wrong-mode deck frame may safely
            // get its one bounded Return attempt again.
            startupDeckModeNavigationAttempts.reset()
            log.info {
                "STARTUP_DECK_MODE_NAVIGATION_BUDGET_RESET reason=fresh-hub-mode-chooser " +
                    "pid=$currentPid hwnd=$currentHwnd action=ALLOW_ONE_NEW_RETURN_AFTER_REENTRY"
            }
            StartupInputReadiness.recordHubModeChooser(currentPid!!, currentHwnd)
            startupClassificationOnly = true
            log.warn {
                "STARTUP_MODE_CHOOSER_CONFIRMED screen=TOURNAMENT mode=HUB pid=$currentPid " +
                    "hwnd=$currentHwnd confidence=${detection?.confidence ?: 0} " +
                    "evidence=${detection?.evidence} currentSessionReady=${ScreenStateRecovery.isCurrentGameSessionReadyForStart()} " +
                    "proof=FRESH_PID_HWND_ONE_USE action=ALLOW_TRADITIONAL_ENTRY_ONLY"
            }
        } else if (authorizedCapture.purpose in setOf(
                ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
                ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
                ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
            ) && !ScreenStateRecovery.isCurrentGameSessionReadyForStart() &&
            detection?.kind != ScreenKind.TOURNAMENT_MODE_SELECTOR &&
            detection?.kind != ScreenKind.DECK_SELECTION
        ) {
            // A fresh, process-bound reconnect/unknown/modal observation revokes
            // any prior startup menu authorization; it must not survive a UI change.
            // Keep the separately selected mode permit through a deck observation
            // so recordDeckSelectionStart can pair it with this exact fresh Start
            // control capture when title OCR is UNKNOWN. A visible opposite-mode
            // title still clears the permit in recordDeckSelectionStart itself.
            StartupInputReadiness.invalidate(currentPid, currentHwnd)
        }
        if (startupClientProbe) {
            val pid = currentPid
            val hwnd = authorizedCapture.gameWindow.toString()
            val isReconnectState = detection?.kind == ScreenKind.RECONNECT ||
                detection?.kind == ScreenKind.RECONNECT_FAILURE ||
                detection?.kind == ScreenKind.SERVER_UNAVAILABLE
            val wrongModeDeckTooltip = detection?.kind == ScreenKind.DECK_SELECTION &&
                ScreenStateRecovery.isWrongModeDeckTooltip(capture.image)
            val selectorModalVisible = detection?.kind == ScreenKind.TOURNAMENT_MODE_SELECTOR &&
                detection?.evidence == "three-choice-mode-selector-structural-visual"
            val blockingModalVisible = ScreenStateRecovery.hasCenteredModalPanel(capture.image) &&
                !wrongModeDeckTooltip && !selectorModalVisible
            val cleanMenu = pid != null && !WarEx.inWar &&
                !blockingModalVisible &&
                !looksLikeReconnectText(ocrText) && !looksLikeReconnectFailureText(ocrText) &&
                detection != null && ScreenRecoveryCapturePurposePolicy.allowsStartupOnlineMenu(detection.kind.code)
            if (detection?.kind == ScreenKind.TOURNAMENT_MODE_SELECTOR) {
                val currentSessionReady = ScreenStateRecovery.isCurrentGameSessionReadyForStart()
                val activeGame = currentSessionReady || WarEx.inWar || Mode.currMode == ModeEnum.GAMEPLAY
                val terminal = GameUtil.isTerminalGameState() ||
                    MandatoryRankSurrenderGuard.isPending() ||
                    MandatoryRankSurrenderGuard.isTerminalCleanupPending() ||
                    PowerLogListener.hasCurrentGameCompleteTerminalPowerLogEvidence()
                val exactWindow = pid != null && currentHwnd == hwnd &&
                    GameUtil.isVerifiedCurrentGameWindow(authorizedCapture.gameWindow)
                val hasSelectorOptions = selectorModalVisible ||
                    StartupDeckModeNavigationPolicy.hasFreshWildSelectorEvidence(
                        screenKind = detection.kind.code,
                        modalVisible = blockingModalVisible,
                        wildTargetLabelVisible = detection.evidence ==
                            "mode-selector-centered-modal-with-standard-and-wild-target-label",
                        ocrText = ocrText,
                    )
                val priorMismatchProof = pid != null && StartupInputReadiness.isDeckSelectionModeNavigationReady(
                    pid, hwnd, expectedMode = "WILD",
                )
                val selectorProofRecorded = pid != null && exactWindow &&
                    (priorMismatchProof || selectorModalVisible) &&
                    !currentSessionReady && !activeGame && !activeQueueEvidence && !terminal &&
                    stateStillCurrent() && WorkTimeListener.working && !PauseStatus.isPause &&
                    StartupInputReadiness.recordWildModeSelectorControl(
                        pid = pid,
                        hwnd = hwnd,
                        confidence = detection.confidence,
                        modalVisible = blockingModalVisible || selectorModalVisible,
                        wildAndStandardOptionsVisible = hasSelectorOptions,
                        structuralSelectorVisualVisible = selectorModalVisible,
                    )
                val selected = selectorProofRecorded && stateStillCurrent() &&
                    !WarEx.inWar && !LifecycleTrace.hasRecentActiveMatchmakingScreen() &&
                    !LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard() &&
                    !GameUtil.isTerminalGameState() &&
                    TournamentModeStrategy.selectWildFromVerifiedModeSelector()
                startupClassificationOnly = true
                log.warn {
                    "STARTUP_DECK_MODE_NAVIGATION stage=FRESH_SELECTOR_CAPTURE " +
                        "decision=${if (selected) "WILD_SELECTED" else "BLOCKED"} " +
                        "reason=${when {
                            selected -> "fresh-selector-visible-wild-option"
                            !hasSelectorOptions -> "wild-and-standard-options-not-both-visible"
                            !priorMismatchProof && !selectorModalVisible -> "prior-opposite-mode-deck-proof-missing-or-expired"
                            !exactWindow -> "exact-window-not-verified"
                            activeGame -> "active-game"
                            activeQueueEvidence -> "active-matchmaking"
                            terminal -> "terminal-state"
                            else -> "runtime-or-dispatch-fence"
                        }} " +
                        "pid=${pid ?: "none"} hwnd=$hwnd confidence=${detection.confidence} " +
                        "evidence=${detection.evidence} modal=$blockingModalVisible " +
                        "selectorModal=$selectorModalVisible optionsVisible=$hasSelectorOptions " +
                        "structuralSelector=$selectorModalVisible " +
                        "priorMismatchProof=$priorMismatchProof " +
                        "exactWindow=$exactWindow activeGame=$activeGame activeQueue=$activeQueueEvidence " +
                        "terminal=$terminal inputAccepted=$selected action=${if (selected) "FRESH_MODE_REPROBE_NO_START" else "NO_INPUT_NO_START"}"
                }
                if (selected) ScreenStateRecovery.scheduleStartupMenuReprobeAfterModeSelection()
            } else if (startupHubModeChooser) {
                // The chooser is the HUB's Traditional Battle/Tavern Brawl/Arena
                // list, not the tournament strategy screen. Keep it classification-
                // only; its exact first action is authorized separately.
            } else if (cleanMenu) {
                val currentDetection = detection!!
                val deckModeObservation = if (currentDetection.kind == ScreenKind.DECK_SELECTION) {
                    TournamentModeConfirmation.observeImage(capture.image)
                } else null
                val expectedDeckMode = if (currentDetection.kind == ScreenKind.DECK_SELECTION) {
                    DeckStrategyManager.currentRunMode?.name
                } else null
                val readinessRecorded = StartupInputReadiness.recordStartupOnlineScreen(
                    pid = pid!!,
                    hwnd = hwnd,
                    screen = currentDetection.kind.code,
                    confidence = currentDetection.confidence,
                    evidence = currentDetection.evidence,
                    expectedRunMode = expectedDeckMode,
                    observedRunMode = deckModeObservation?.observedMode?.name,
                    enabledStartButtonVisible = currentDetection.kind == ScreenKind.DECK_SELECTION &&
                        ScreenWatchdog.hasEnabledDeckSelectionStartButton(capture.image),
                    modalVisible = false,
                )
                val selectedModePermitValid = pid != null && expectedDeckMode != null &&
                    StartupInputReadiness.hasSelectedTournamentModeStartPermit(pid, hwnd, expectedDeckMode)
                val enabledStartVisible = currentDetection.kind == ScreenKind.DECK_SELECTION &&
                    ScreenWatchdog.hasEnabledDeckSelectionStartButton(capture.image)
                log.warn {
                    "STARTUP_INPUT_READINESS_CONFIRMED screen=${currentDetection.kind.code} pid=$pid hwnd=$hwnd " +
                        "evidence=${currentDetection.evidence} recorded=$readinessRecorded " +
                        "expectedRunMode=${expectedDeckMode ?: "n/a"} " +
                        "observedRunMode=${deckModeObservation?.observedMode?.name ?: "n/a"} " +
                        "selectedModePermitValid=$selectedModePermitValid startVisible=$enabledStartVisible " +
                        "proof=${if (currentDetection.kind == ScreenKind.DECK_SELECTION) "MATCHMAKING_START_ONLY" else "ONLINE_MENU"} " +
                        "provider=UPSTREAM"
                }
                if (StartupDeckModeNavigationPolicy.requiresClassificationOnly(
                        purpose = authorizedCapture.purpose,
                        screenKind = currentDetection.kind.code,
                        freshConfiguredModeStartProof = readinessRecorded,
                    )
                ) {
                    startupClassificationOnly = true
                    val expectedMode = expectedDeckMode
                    val observedMode = deckModeObservation?.observedMode?.name
                    val startVisible = ScreenWatchdog.hasEnabledDeckSelectionStartButton(capture.image)
                    val activeGame = ScreenStateRecovery.isCurrentGameSessionReadyForStart() ||
                        WarEx.inWar || Mode.currMode == ModeEnum.GAMEPLAY
                    // A historical matchmaking intent is not a live queue. The current screen/short
                    // entry guard, active-game state, and terminal evidence remain authoritative.
                    val activeQueue = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
                        LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard()
                    val terminal = GameUtil.isTerminalGameState() ||
                        MandatoryRankSurrenderGuard.isPending() ||
                        MandatoryRankSurrenderGuard.isTerminalCleanupPending() ||
                        PowerLogListener.hasCurrentGameCompleteTerminalPowerLogEvidence()
                    val trustedWindow = pid != null && currentHwnd == hwnd &&
                        GameUtil.isVerifiedCurrentGameWindow(authorizedCapture.gameWindow)
                    val navigationDecision = StartupDeckModeNavigationPolicy.decide(
                        purpose = authorizedCapture.purpose.name,
                        screenKind = currentDetection.kind.code,
                        confidence = currentDetection.confidence,
                        evidence = currentDetection.evidence,
                        expectedRunMode = expectedMode,
                        observedRunMode = observedMode,
                        enabledStartButtonVisible = startVisible,
                        modalVisible = blockingModalVisible,
                        currentSessionReady = ScreenStateRecovery.isCurrentGameSessionReadyForStart(),
                        activeGame = activeGame,
                        activeMatchmaking = activeQueue,
                        terminal = terminal,
                        trustedCurrentWindow = trustedWindow,
                    )
                    var modeNavigationAttempted = false
                    if (navigationDecision.allowed && pid != null && trustedWindow &&
                        stateStillCurrent() && WorkTimeListener.working && !PauseStatus.isPause
                    ) {
                        val attempt = startupDeckModeNavigationAttempts.tryBegin(pid, hwnd)
                        val proofRecorded = attempt.allowed && StartupInputReadiness.recordDeckSelectionModeNavigation(
                            pid = pid,
                            hwnd = hwnd,
                            expectedRunMode = expectedMode,
                            observedRunMode = observedMode,
                            confidence = currentDetection.confidence,
                            evidence = currentDetection.evidence,
                            enabledStartButtonVisible = startVisible,
                            modalVisible = blockingModalVisible,
                        )
                        val accepted = if (proofRecorded && stateStillCurrent() && !WarEx.inWar &&
                            !LifecycleTrace.hasRecentActiveMatchmakingScreen() &&
                            !LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard() &&
                            !GameUtil.isTerminalGameState()
                        ) {
                            StartupDeckModeNavigationPolicy.dispatchOpenSelectorForVerifiedDeckMismatch(
                                image = capture.image,
                                expectedMode = expectedMode,
                                decision = navigationDecision,
                                dispatchOpenSelector = {
                                    TournamentModeStrategy.openModeSelectorAfterVerifiedDeckMismatch()
                                },
                                requestFreshSelectorProbe = {
                                    log.warn {
                                        "STARTUP_DECK_MODE_NAVIGATION_POSTCHECK scheduled=true pid=$pid hwnd=$hwnd " +
                                            "next=FRESH_SELECTOR_CAPTURE expectedMode=${expectedMode ?: "UNKNOWN"} " +
                                            "action=NO_MODE_SELECTION_NO_START"
                                    }
                                    ScreenStateRecovery.scheduleStartupMenuReprobeAfterModeSelection()
                                },
                            )
                        } else false
                        val attemptCommitted = if (attempt.allowed) {
                            startupDeckModeNavigationAttempts.resolve(pid, hwnd, accepted)
                        } else false
                        modeNavigationAttempted = accepted
                        log.warn {
                            "STARTUP_DECK_MODE_NAVIGATION decision=${if (accepted) "MODE_CORRECTION_DISPATCHED" else "BLOCKED"} " +
                                "reason=${if (accepted) "fresh-opposite-mode-deck-proof" else if (!attempt.allowed) attempt.reason else if (!proofRecorded) "proof-not-recorded" else if (!stateStillCurrent()) "state-changed-before-dispatch" else if (WarEx.inWar) "active-game-before-dispatch" else if (LifecycleTrace.hasRecentActiveMatchmakingScreen() || LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard()) "active-queue-before-dispatch" else if (GameUtil.isTerminalGameState()) "terminal-before-dispatch" else "input-dispatch-rejected"} " +
                                "attempt=${attempt.attempt} expectedMode=${expectedMode ?: "UNKNOWN"} " +
                                "observedMode=${observedMode ?: "UNKNOWN"} pid=$pid hwnd=$hwnd " +
                                "confidence=${currentDetection.confidence} evidence=${currentDetection.evidence} " +
                                "startVisible=$startVisible wrongModeDeckTooltip=$wrongModeDeckTooltip " +
                                "activeGame=$activeGame activeQueue=$activeQueue " +
                                "terminal=$terminal exactWindow=$trustedWindow " +
                                "action=${if (accepted) StartupDeckModeNavigationPolicy.OPEN_SELECTOR_ACTION else "NO_MODE_INPUT"} " +
                                "next=${if (accepted) "FRESH_SELECTOR_CAPTURE" else "RETRY_ON_NEXT_FRESH_PROBE"} " +
                                "inputAccepted=$accepted attemptBudgetCommitted=$attemptCommitted"
                        }
                    } else {
                        log.warn {
                            "STARTUP_DECK_MODE_NAVIGATION decision=BLOCKED reason=${navigationDecision.denial.name.lowercase()} " +
                                "expectedMode=${expectedMode ?: "UNKNOWN"} observedMode=${observedMode ?: "UNKNOWN"} " +
                                "pid=${pid ?: "none"} hwnd=$hwnd activeGame=$activeGame activeQueue=$activeQueue " +
                            "terminal=$terminal exactWindow=$trustedWindow " +
                            "wrongModeDeckTooltip=$wrongModeDeckTooltip action=NO_INPUT"
                        }
                    }
                    TournamentModeConfirmation.reportUnconfirmedDeckSelection(
                        expectedMode = expectedMode,
                        observedMode = observedMode,
                        reason = deckModeObservation?.evidence ?: "fresh-mode-proof-missing",
                    )
                    log.warn {
                        "SCREEN_RECOVERY_DECK_SELECTION_CLASSIFICATION_ONLY " +
                            "expectedRunMode=${expectedDeckMode ?: "UNKNOWN"} " +
                            "observedRunMode=${deckModeObservation?.observedMode?.name ?: "UNKNOWN"} " +
                            "purpose=${authorizedCapture.purpose.name} " +
                            "action=${if (modeNavigationAttempted) "BACK_TO_MODE_SELECTOR_ONLY" else "NO_START"} provider=UPSTREAM"
                    }
                    if (authorizedCapture.purpose == ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE) {
                        StartupInputReadiness.invalidate(pid, hwnd)
                    }
                }
            } else {
                StartupInputReadiness.invalidate(pid, hwnd)
                startupClassificationOnly = !isReconnectState
                log.warn {
                    "STARTUP_INPUT_READINESS_NOT_CONFIRMED screen=${detection?.kind?.code ?: "UNKNOWN"} " +
                        "modalVisible=${ScreenStateRecovery.hasCenteredModalPanel(capture.image)} " +
                        "action=${if (startupClassificationOnly) "NO_INPUT" else "CLASSIFY_RECOVERY_ONLY"} " +
                        "provider=UPSTREAM"
                }
            }
        }
        log.info {
            "SCREEN_RECOVERY_OBSERVATION " +
                "provider=LEGACY " +
                "ocr=${ocrText.ifBlank { "<empty>" }.take(MAX_OCR_TEXT_LENGTH)} " +
                "detected=${detection?.kind?.code ?: "UNKNOWN"} " +
                "confidence=${detection?.confidence ?: 0} " +
                "evidence=${detection?.evidence ?: "none"}"
        }

        if (detection?.kind == ScreenKind.AMAZON_APPSTORE_NOTICE &&
            dismissFreshAmazonAppstoreNotice(authorizedCapture)
        ) return true
        if (dispatchEventQuestRecovery(detection?.kind?.code) {
                startEventQuestOverlayRecovery(authorizedCapture, stuckForMs, stateStillCurrent)
            }
        ) return true
        if (detection?.kind == ScreenKind.BETA_RESULT_CONFIRMATION &&
            dismissFreshBetaResultConfirmation(authorizedCapture)
        ) return true
        if (detection?.kind == ScreenKind.RECONNECT && capture.visual.reconnectDialogVisual &&
            handleGenericIdleUnblock(
                authorizedCapture = authorizedCapture,
                detection = detection,
                currentPid = currentPid,
                stateFingerprint = stateFingerprint,
                stuckForMs = stuckForMs,
                stateStillCurrent = stateStillCurrent,
            )
        ) return true

        // A known startup menu can remain unresponsive without becoming UNKNOWN.
        // Let the bounded idle ladder act on that exact current screen after the
        // same 60-second threshold used for unidentified blockers.
        if (safeIdleMenuCandidate && stuckForMs >= StuckScreenUnblockPolicy.DEFAULT_FIRST_THRESHOLD_MS &&
            handleGenericIdleUnblock(
                authorizedCapture = authorizedCapture,
                detection = detection,
                currentPid = currentPid,
                stateFingerprint = stateFingerprint,
                stuckForMs = stuckForMs,
                stateStillCurrent = stateStillCurrent,
            )
        ) return false

        // Keep one durable, categorized copy for every 30-second recovery
        // inspection.  The existing DebugScreenshotRing remains the compact
        // 60-file timeline; this copy is the post-mortem evidence and is
        // retained per category/day by UnknownStateScreenshot.
        val evidenceCategory = if (detection == null || detection.confidence < 85) {
            UnknownStateScreenshot.CATEGORY_SCREEN_RECOVERY_UNRESOLVED
        } else {
            UnknownStateScreenshot.CATEGORY_STUCK_STATE
        }
        val evidence = UnknownStateScreenshot.save(
            image = capture.image,
            regions = listOf(
                UnknownStateScreenshot.UnknownRegion(
                    Rectangle(0, 0, capture.image.width, capture.image.height),
                    if (evidenceCategory == UnknownStateScreenshot.CATEGORY_STUCK_STATE) {
                        "stuck-state-observation"
                    } else {
                        "unidentified-screen"
                    },
                ),
            ),
            category = evidenceCategory,
            trigger = "screen-recovery-observation",
            state = stateFingerprint,
            phase = "stuck-screen-recovery",
            ocrText = ocrText,
            visual = capture.visual.toString(),
        )
        log.warn {
            "SCREEN_RECOVERY_EVIDENCE category=$evidenceCategory " +
                "path=${evidence?.file?.absolutePath ?: "not-saved"} " +
                "link=${evidence?.link ?: "none"}"
        }

        if (detection == null || detection.confidence < 85) {
            log.warn {
                "SCREEN_RECOVERY_UNRESOLVED confidence=${detection?.confidence ?: 0} " +
                    "reason=${if (detection == null) "no-screen-classifier-matched" else "classifier-below-confidence-threshold"} " +
                    "purpose=${authorizedCapture.purpose} questOverlayVisual=${capture.visual.startupQuestOverlayVisual} " +
                    "capture=${capture.image.width}x${capture.image.height} " +
                    "screenshot=${capture.file?.absolutePath ?: "not-saved"} " +
                    "screenshotLink=${capture.file?.toURI()?.toString() ?: "none"} " +
                    "unknownStateScreenshot=${evidence?.file?.absolutePath ?: "not-saved"} " +
                    "unknownStateScreenshotLink=${evidence?.link ?: "none"}"
            }
            if (handleGenericIdleUnblock(
                    authorizedCapture = authorizedCapture,
                    detection = detection,
                    currentPid = currentPid,
                    stateFingerprint = stateFingerprint,
                    stuckForMs = stuckForMs,
                    stateStillCurrent = stateStillCurrent,
                )
            ) return false
            val eligibleProbe = authorizedCapture.purpose in setOf(
                ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
                ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
                ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
            )
            val safeNoGame = !ScreenStateRecovery.isCurrentGameSessionReadyForStart() &&
                Mode.currMode != ModeEnum.GAMEPLAY && !WarEx.inWar && !GameUtil.isTerminalGameState()
            val safeNoQueue = !activeQueueEvidence && !capture.visual.matchmakingSearchVisual
            when (unknownStartupRestartPolicy.observe(
                stateFingerprint = stateFingerprint,
                now = System.currentTimeMillis(),
                stuckForMs = stuckForMs,
                eligibleProbe = eligibleProbe,
                noActiveGame = safeNoGame,
                noActiveQueue = safeNoQueue,
            )) {
                UnknownStartupRestartPolicy.Decision.RESTART_CLIENT -> {
                    val expectedPid = currentPid
                    val expectedHwnd = authorizedCapture.gameWindow
                    if (!clientRestartInFlight.tryAcquire(
                            processId = expectedPid?.toLong() ?: 0L,
                            now = System.currentTimeMillis(),
                        )
                    ) {
                        log.warn { "SCREEN_RECOVERY_UNKNOWN_RESTART_COALESCED action=WAIT_FOR_EXISTING_REQUEST" }
                        return false
                    }
                    log.error {
                        "SCREEN_RECOVERY_UNKNOWN_ESCALATED screen=UNKNOWN stuckForMs=$stuckForMs " +
                            "action=RECHECK_THEN_RESTART_CLIENT_NO_PAUSE"
                    }
                    EXTRA_THREAD_POOL.schedule({
                            if (!stateStillCurrent() || !WorkTimeListener.working || PauseStatus.isPause ||
                                expectedPid == null || expectedPid <= 0 ||
                                ScreenStateRecovery.isCurrentGameSessionReadyForStart() || Mode.currMode == ModeEnum.GAMEPLAY ||
                                WarEx.inWar || GameUtil.isTerminalGameState() ||
                                LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
                                LifecycleTrace.hasRecentMatchmakingInputIntent() ||
                                GameUtil.findGameProcessIdForDiagnostics() != expectedPid.toLong() ||
                                !GameUtil.isVerifiedCurrentGameWindow(expectedHwnd)
                            ) {
                                log.info { "SCREEN_RECOVERY_UNKNOWN_RESTART_SKIPPED reason=state-or-safety-fence" }
                                return@schedule
                            }
                            val fresh = captureVerifiedGameScreen(stuckForMs = stuckForMs, startupProbe = true)
                            if (fresh == null || fresh.gameWindow.toString() != expectedHwnd.toString() ||
                                fresh.purpose !in setOf(
                                    ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
                                    ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
                                    ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
                                ) || fresh.capture.visual.matchmakingSearchVisual ||
                                detect(runOCR(fresh.capture), fresh.capture.visual) != null
                            ) {
                                log.info { "SCREEN_RECOVERY_UNKNOWN_RESTART_SKIPPED reason=fresh-probe-not-unknown" }
                                unknownStartupRestartPolicy.reset()
                                return@schedule
                            }
                            log.error {
                                "SCREEN_RECOVERY_UNKNOWN_RESTART_CONFIRMED pid=$expectedPid " +
                                    "hwnd=${expectedHwnd} action=RESTART_CLIENT_NO_PAUSE"
                            }
                            Core.restartFromIdleWatchdog()
                    }, 300L, TimeUnit.MILLISECONDS)
                }
                UnknownStartupRestartPolicy.Decision.ALERT_OPERATOR -> {
                    if (clientRestartInFlight.isInFlight(System.currentTimeMillis())) {
                        log.warn { "SCREEN_RECOVERY_UNKNOWN_RESTART_ALERT_DEFERRED reason=restart-in-flight" }
                        return false
                    }
                    log.error {
                        "SCREEN_RECOVERY_UNKNOWN_RESTART_EXHAUSTED action=NO_PAUSE_NO_INPUT " +
                            "attempts=${UnknownStartupRestartPolicy.DEFAULT_MAX_RESTARTS}"
                    }
                    SystemUtil.notice(
                        "Hearthstone remains on an unidentified startup screen after bounded client restarts. " +
                            "Automation was not paused; inspect the client and click Start to retry.",
                    )
                }
                UnknownStartupRestartPolicy.Decision.WAIT -> Unit
            }
            return false
        }

        if (startupClassificationOnly) return false

        if (!stateStillCurrent()) {
            log.info { "SCREEN_RECOVERY_SKIPPED reason=state-changed-before-apply state=$stateFingerprint" }
            return false
        }
        if (authorizedCapture.purpose == ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE) {
            val staleProbeDecision = ScreenRecoveryCapturePurposePolicy.staleProbeRecoveryDecision(
                purpose = authorizedCapture.purpose,
                screenKind = detection.kind.code,
                confidence = detection.confidence,
                evidence = detection.evidence,
                freshObservation = true,
                configuredDeck = DeckStrategyManager.currentDeckStrategy != null,
                configuredRunMode = DeckStrategyManager.currentRunMode != null,
                activeGame = StartupGamePhaseEvidencePolicy.hasAuthoritativeActiveGame(
                    warModelAuthoritative = ScreenStateRecovery.isCurrentGameSessionReadyForStart(),
                    warInProgress = WarEx.inWar,
                ),
                activeMatchmaking = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
                    LifecycleTrace.hasRecentMatchmakingInputIntent(),
            )
            if (staleProbeDecision == ScreenRecoveryCapturePurposePolicy.StaleProbeRecoveryDecision.CLASSIFICATION_ONLY) {
                log.warn {
                    "SCREEN_RECOVERY_STALE_PROBE_CLASSIFICATION_ONLY screen=${detection.kind.code} " +
                        "action=NO_INPUT reason=stale-probe-policy"
                }
                return false
            }
            if (staleProbeDecision ==
                ScreenRecoveryCapturePurposePolicy.StaleProbeRecoveryDecision.APPLY_CONFIGURED_DECK_SELECTION
            ) {
                log.info {
                    "SCREEN_RECOVERY_STALE_PROBE_AUTHORIZED screen=${detection.kind.code} " +
                        "action=APPLY_CONFIGURED_DECK_SELECTION confidence=${detection.confidence}"
                }
            }
        }
        if (authorizedCapture.purpose == ScreenRecoveryCapturePurpose.STARTUP_YOGG_PROBE &&
            detection.kind != ScreenKind.SERVER_UNAVAILABLE
        ) {
            log.warn {
                "SCREEN_RECOVERY_STARTUP_YOGG_CLASSIFICATION_ONLY screen=${detection.kind.code} " +
                    "action=NO_INPUT reason=purpose-allows-server-unavailable-only"
            }
            return false
        }
        if (detection.kind == ScreenKind.SERVER_UNAVAILABLE) {
            log.warn {
                "SCREEN_RECOVERY_CLASSIFIED screen=SERVER_UNAVAILABLE action=NO_INPUT_NO_RESTART " +
                    "purpose=${authorizedCapture.purpose.name}"
            }
            return false
        }
        return applyWithVerifiedForeground(detection, authorizedCapture.gameWindow)
    }

    /** Resolve a fresh process-owned HWND, hold it foreground through capture, then capture. */
    private fun captureVerifiedGameScreen(
        stuckForMs: Long = 0L,
        startupProbe: Boolean = false,
        exactReconnectRecheck: Boolean = false,
        captureContext: String = "screen-recovery",
    ): AuthorizedCapture? {
        val gameWindow = resolveVerifiedGameWindow()
        if (gameWindow == null) {
            log.warn {
                "SCREEN_RECOVERY_CAPTURE_BLOCKED context=$captureContext reason=hearthstone-window-unverified " +
                    "processAlive=${GameUtil.isAliveOfGame()} stuckForMs=$stuckForMs"
            }
            return null
        }

        val attachedPowerLog = club.xiaojiawei.hsscript.listener.log.PowerLogListener.logFile
        val currentPowerLog = GameUtil.getLatestLogDir()?.resolve(
            club.xiaojiawei.hsscript.consts.GAME_WAR_LOG_NAME,
        )
        val currentSessionReady = CurrentGameScreenReadinessPolicy.isReady(
            gameWindowVerified = GameUtil.isVerifiedCurrentGameWindow(gameWindow),
            attachedPowerLogPath = attachedPowerLog?.path(),
            currentSessionPowerLogPath = currentPowerLog?.absolutePath,
            powerLogLength = attachedPowerLog?.length() ?: 0L,
        )
        // WarEx can retain an active-game phase from the previous Hearthstone
        // process. Before the new session log is ready, that latch alone must
        // not fence the exact, read-only startup chooser probe.
        val currentWarModelAuthoritative = currentSessionReady
        val authoritativeActiveGame = StartupGamePhaseEvidencePolicy.hasAuthoritativeActiveGame(
            warModelAuthoritative = currentWarModelAuthoritative,
            warInProgress = WarEx.inWar,
        )
        val authoritativeTerminalState = currentWarModelAuthoritative && GameUtil.isTerminalGameState()
        val activeQueueEvidence = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
            LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard() ||
            !exactReconnectRecheck && LifecycleTrace.hasRecentMatchmakingInputIntent()
        val capturePurpose = if (
            !currentSessionReady && stuckForMs >= ScreenRecoveryCapturePurposePolicy.IDLE_CLIENT_PROBE_AFTER_MS &&
            Mode.currMode !== ModeEnum.GAMEPLAY && !authoritativeActiveGame && !authoritativeTerminalState &&
            !activeQueueEvidence
        ) {
            ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE
        } else ScreenRecoveryCapturePurposePolicy.purposeForStateRecovery(
            tournamentMode = Mode.currMode === ModeEnum.TOURNAMENT,
            nonGameplayScreen = Mode.currMode !== ModeEnum.GAMEPLAY,
            activeGame = authoritativeActiveGame,
            mulligan = currentWarModelAuthoritative && WarEx.war.currentPhase == WarPhaseEnum.REPLACE_CARD,
            terminal = currentWarModelAuthoritative && GameUtil.isTerminalGameState(),
            recentInputIntent = !exactReconnectRecheck && LifecycleTrace.hasRecentMatchmakingInputIntent(),
            stuckForMs = stuckForMs,
            warModelAuthoritative = currentWarModelAuthoritative,
            startupProbe = startupProbe,
            noActiveQueueEvidence = !activeQueueEvidence,
        )
        val preSessionQueueModalAuthorized = ScreenRecoveryCapturePurposePolicy.allowsPreSessionQueueModal(
            purpose = capturePurpose,
            tournamentMode = Mode.currMode === ModeEnum.TOURNAMENT,
            activeGame = authoritativeActiveGame,
            mulligan = currentWarModelAuthoritative && WarEx.war.currentPhase == WarPhaseEnum.REPLACE_CARD,
            terminal = currentWarModelAuthoritative && GameUtil.isTerminalGameState(),
        )
        val preSessionMatchmakingAuthorized = ScreenRecoveryCapturePurposePolicy.allowsPreSessionMatchmaking(
            purpose = capturePurpose,
            tournamentMode = Mode.currMode === ModeEnum.TOURNAMENT,
            activeGame = authoritativeActiveGame,
            mulligan = currentWarModelAuthoritative && WarEx.war.currentPhase == WarPhaseEnum.REPLACE_CARD,
            terminal = authoritativeTerminalState,
            recentInputIntent = LifecycleTrace.hasRecentMatchmakingInputIntent(),
        )
        val preSessionStaleClientProbeAuthorized = ScreenRecoveryCapturePurposePolicy.allowsPreSessionStaleClientProbe(
            purpose = capturePurpose,
            nonGameplayScreen = Mode.currMode !== ModeEnum.GAMEPLAY,
            activeGame = authoritativeActiveGame,
            mulligan = currentWarModelAuthoritative && WarEx.war.currentPhase == WarPhaseEnum.REPLACE_CARD,
            terminal = currentWarModelAuthoritative && GameUtil.isTerminalGameState(),
            stuckForMs = stuckForMs,
            warModelAuthoritative = currentWarModelAuthoritative,
        )
        val preSessionStartupClientProbeAuthorized = ScreenRecoveryCapturePurposePolicy
            .allowsPreSessionStartupClientProbe(capturePurpose, currentSessionReady)
        val preSessionIdleClientProbeAuthorized = ScreenRecoveryCapturePurposePolicy.allowsPreSessionIdleClientProbe(
            purpose = capturePurpose,
            currentSessionReady = currentSessionReady,
            stuckForMs = stuckForMs,
        )
        val preSessionYoggProbeAuthorized = ScreenRecoveryCapturePurposePolicy.allowsPreSessionYoggProbe(
            purpose = capturePurpose,
            currentSessionReady = currentSessionReady,
            activeGame = authoritativeActiveGame,
            noActiveQueueEvidence = !LifecycleTrace.hasRecentActiveMatchmakingScreen() &&
                !LifecycleTrace.hasRecentMatchmakingInputIntent(),
        )
        val preflightRejection = ScreenRecoveryCapturePurposePolicy.preflightRejectionReason(
            currentSessionReady = currentSessionReady,
            preSessionQueueModalAuthorized = preSessionQueueModalAuthorized,
            preSessionMatchmakingAuthorized = preSessionMatchmakingAuthorized,
            preSessionStaleClientProbeAuthorized = preSessionStaleClientProbeAuthorized,
            preSessionIdleClientProbeAuthorized = preSessionIdleClientProbeAuthorized,
            preSessionStartupClientProbeAuthorized = preSessionStartupClientProbeAuthorized,
            preSessionYoggProbeAuthorized = preSessionYoggProbeAuthorized,
        )
        if (preflightRejection != null) {
            log.info {
                "SCREEN_RECOVERY_CAPTURE_BLOCKED context=$captureContext reason=$preflightRejection " +
                    "beforeForeground=true purpose=${capturePurpose.name} stuckForMs=$stuckForMs " +
                    "sessionReady=$currentSessionReady idleProbe=$preSessionIdleClientProbeAuthorized " +
                    "startupProbe=$preSessionStartupClientProbeAuthorized staleProbe=$preSessionStaleClientProbeAuthorized " +
                    "queueModal=$preSessionQueueModalAuthorized matchmaking=$preSessionMatchmakingAuthorized " +
                    "pid=${GameUtil.findGameProcessIdForDiagnostics() ?: "none"} hwnd=$gameWindow " +
                    "mode=${Mode.currMode?.name ?: "UNKNOWN"} phase=${WarEx.war.currentPhase?.name ?: "UNKNOWN"} " +
                    "attachedPowerLog=${attachedPowerLog?.path() ?: "none"} " +
                    "currentPowerLog=${currentPowerLog?.absolutePath ?: "none"} " +
                    "powerLogLength=${attachedPowerLog?.length() ?: 0L}"
            }
            return null
        }
        var authorityRejected = false
        val foregroundResult = MouseUtil.withRecoveryForeground(gameWindow) {
                val currentWindow = resolveVerifiedGameWindow()
                val evidence = ScreenRecoveryAuthorityEvidence(
                    processAlive = GameUtil.isAliveOfGame(),
                    windowPresent = currentWindow != null,
                    windowVerified = currentWindow != null && GameUtil.isVerifiedCurrentGameWindow(currentWindow),
                    foregroundConfirmed = true,
                    sameWindow = currentWindow?.toString() == gameWindow.toString(),
                )
                // Pixel authority is necessarily false before capture. Validate
                // process/window/foreground ownership first, then captureIfAuthorized
                // independently verifies that the resulting pixels belong to this
                // current Hearthstone client before any action can use them.
                if (!ScreenRecoveryAuthorityGate.isCapturePreAuthorized(evidence)) {
                    authorityRejected = true
                    log.warn {
                        "SCREEN_RECOVERY_CAPTURE_BLOCKED context=$captureContext reason=authority-lost-before-capture " +
                            "gameWindow=$gameWindow currentWindow=$currentWindow purpose=${capturePurpose.name} " +
                            "stuckForMs=$stuckForMs"
                    }
                    null
                } else {
                    ScreenRecoveryAuthorityGate.captureIfAuthorized(evidence) {
                        captureScreen(gameWindow, purpose = capturePurpose, staleForMs = stuckForMs)
                    }
                }
        }
        if (!foregroundResult.foregroundConfirmed) {
            log.warn {
                "SCREEN_RECOVERY_CAPTURE_BLOCKED context=$captureContext reason=game-foreground-unconfirmed " +
                    "gameWindow=$gameWindow purpose=${capturePurpose.name} stuckForMs=$stuckForMs"
            }
            return null
        }
        if (authorityRejected) return null
        val capture = foregroundResult.value ?: run {
            log.warn {
                "SCREEN_RECOVERY_CAPTURE_BLOCKED context=$captureContext reason=authorized-frame-unavailable " +
                    "purpose=${capturePurpose.name} pid=${GameUtil.findGameProcessIdForDiagnostics() ?: "none"} " +
                    "hwnd=$gameWindow stuckForMs=$stuckForMs sessionReady=$currentSessionReady " +
                    "powerLog=${currentPowerLog?.absolutePath ?: "none"} " +
                    "powerLogLength=${attachedPowerLog?.length() ?: 0L}"
            }
            return null
        }
        return AuthorizedCapture(gameWindow, capture, capturePurpose, foregroundVerified = true)
    }

    /** Reacquire and revalidate the same Hearthstone HWND before applying any recovery action. */
    private fun applyWithVerifiedForeground(detection: Detection, capturedWindow: WinDef.HWND): Boolean {
        val currentWindow = resolveVerifiedGameWindow()
        if (currentWindow == null || currentWindow.toString() != capturedWindow.toString()) {
            log.warn {
                "SCREEN_RECOVERY_BLOCKED reason=hearthstone-window-changed-before-apply " +
                    "capturedWindow=$capturedWindow currentWindow=$currentWindow"
            }
            return false
        }

        var authorityRejected = false
        val foregroundResult = MouseUtil.withRecoveryForeground(currentWindow) {
            val freshWindow = resolveVerifiedGameWindow()
            val evidence = ScreenRecoveryAuthorityEvidence(
                processAlive = GameUtil.isAliveOfGame(),
                windowPresent = freshWindow != null,
                windowVerified = freshWindow != null && GameUtil.isVerifiedCurrentGameWindow(freshWindow),
                foregroundConfirmed = true,
                sameWindow = freshWindow?.toString() == capturedWindow.toString(),
                capturedPixelsVerified = true,
            )
            if (!ScreenRecoveryAuthorityGate.isAuthorized(evidence)) {
                authorityRejected = true
                log.warn {
                    "SCREEN_RECOVERY_BLOCKED reason=authority-lost-before-apply " +
                        "capturedWindow=$capturedWindow currentWindow=$freshWindow"
                }
                false
            } else if (MatchmakingEntryRecoveryPolicy.shouldBlockModeChangingRecovery(
                    entryGuardActive = LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard(),
                    screenKind = detection.kind.code,
                )
            ) {
                log.warn {
                    "SCREEN_RECOVERY_BLOCKED reason=matchmaking-entry-transition-guard " +
                        "screen=${detection.kind.code} action=NO_MODE_CHANGE"
                }
                false
            } else {
                ScreenRecoveryAuthorityGate.dispatchIfAuthorized(evidence) { apply(detection) }
            }
        }
        if (!foregroundResult.foregroundConfirmed) {
            log.warn {
                "SCREEN_RECOVERY_BLOCKED reason=game-foreground-unconfirmed-before-apply gameWindow=$currentWindow"
            }
            return false
        }
        return !authorityRejected && foregroundResult.value == true
    }

    private fun resolveVerifiedGameWindow(): WinDef.HWND? {
        if (!GameUtil.isAliveOfGame()) return null
        val window = runCatching { GameUtil.findGameHWND() }.getOrNull()
            ?.takeIf(GameUtil::isVerifiedCurrentGameWindow)
            ?: return null
        ScriptStatus.gameHWND = window
        GameUtil.updateGameRect(window)
        return window
    }

    private fun captureScreen(
        hwnd: WinDef.HWND,
        purpose: ScreenRecoveryCapturePurpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY,
        staleForMs: Long = 0L,
    ): Capture? {
        val processIdBeforeCapture = windowProcessId(hwnd)
        if (processIdBeforeCapture <= 0L) return null
        val captureStartedAt = System.nanoTime()
        val authorizedFrame = ScreenRecoveryWindowCapture.capture(hwnd, purpose = purpose, staleForMs = staleForMs) ?: return null
        if (windowProcessId(hwnd) != processIdBeforeCapture) return null
        val authorizedCaptureMs = ResultScreenProbeTelemetry.elapsedMs(captureStartedAt)
        val image = authorizedFrame.image
        val screenshotStartedAt = System.nanoTime()
        val saved = DebugScreenshotRing.save(image, "screen-recovery", "stale-screen")
        val screenshotSaveMs = ResultScreenProbeTelemetry.elapsedMs(screenshotStartedAt)
        val visualStartedAt = System.nanoTime()
        val visual = visualSignature(image)
        val visualAnalysisMs = ResultScreenProbeTelemetry.elapsedMs(visualStartedAt)
        return Capture(
            image,
            authorizedFrame.bounds,
            saved?.file,
            visual,
            gameRectKnown = true,
            gameWindowKnown = true,
            purpose = purpose,
            capturedAtNanos = System.nanoTime(),
            processId = processIdBeforeCapture,
            windowId = hwnd.toString(),
            resultProbeTimings = ResultProbeCaptureTimings(
                authorizedCaptureMs = authorizedCaptureMs,
                screenshotSaveMs = screenshotSaveMs,
                visualAnalysisMs = visualAnalysisMs,
            ),
        )
    }

    private fun runOCR(capture: Capture): String = runOCR(capture, File(TESS_DATA_PATH))

    private fun windowProcessId(hwnd: WinDef.HWND): Long {
        val pid = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid)
        return pid.value.toLong()
    }

    private fun runOCR(
        capture: Capture,
        tessData: File,
        postResultProbe: Boolean = false,
        onTimings: ((ResultProbeOcrTimings) -> Unit)? = null,
    ): String {
        val chiSim = File(tessData, "$CHI_SIM_DATA.traineddata")
        if (!chiSim.isFile) {
            log.info {
                "SCREEN_RECOVERY_OCR_SKIPPED provider=LEGACY reason=missing-tessdata " +
                    "path=${chiSim.absolutePath}"
            }
            onTimings?.invoke(ResultProbeOcrTimings(path = "SKIPPED_MISSING_TESSDATA"))
            return ""
        }
        val fingerprintStartedAt = System.nanoTime()
        val fingerprint = if (ExactCaptureOcrReuse.enabledForChannel(BuildInfo.RELEASE_CHANNEL) &&
            capture.processId > 0 && capture.windowId.isNotBlank()
        ) ExactCaptureOcrReuse.fingerprint(capture.image) else null
        val fingerprintMs = if (fingerprint == null) 0L else ResultScreenProbeTelemetry.elapsedMs(fingerprintStartedAt)
        if (fingerprint != null) {
            val reused = exactCaptureOcrReuse.lookup(capture.processId, capture.windowId, fingerprint)
            if (reused != null) {
                onTimings?.invoke(
                    ResultProbeOcrTimings(
                        provider = "LEGACY_TESSERACT",
                        path = "BETA_EXACT_FRESH_FRAME_REUSE",
                        totalMs = fingerprintMs,
                    ),
                )
                log.info {
                    "SCREEN_RECOVERY_OCR_REUSED reason=exact-fresh-frame pid=${capture.processId} " +
                        "hwnd=${capture.windowId} fingerprintMs=$fingerprintMs"
                }
                return reused
            }
        }
        var targetedMs = 0L
        var secondaryMs = 0L
        var path = "FAILED"
        var roiTimingSummary = ""
        var ocrSucceeded = false
        val result = runCatching {
            val roiTimings = mutableListOf<String>()
            // ScreenStateRoiSelector no longer has the former broad
            // screen-state-center crop. Probe the same narrow, capture-local
            // anchors as beta recovery so the upstream fallback cannot turn
            // every OCR observation into an empty string.
            val (selectedTargetedRois, secondaryRois) = screenRecoveryOcrRois(
                capture.image.width,
                capture.image.height,
            )
            val targetedRois = if (postResultProbe) {
                orderPostResultTargetedRois(selectedTargetedRois)
            } else selectedTargetedRois
            fun recognize(roi: ScreenStateRoiSelector.Roi, targeted: Boolean): String {
                val roiStartedAt = System.nanoTime()
                val crop = crop(capture.image, roi.bounds)
                val image = if (targeted) enlargeTargetedOcr(crop) else resizeForOcr(crop)
                val language = if (roi.name == ScreenStateRoiSelector.YOGG_SERVER_UNAVAILABLE_ROI) {
                    "eng"
                } else {
                    CHI_SIM_DATA
                }
                val value = Tesseract().apply {
                    setDatapath(tessData.absolutePath)
                    setLanguage(language)
                    setPageSegMode(if (targeted && language == "eng") 6 else if (targeted) 7 else 11)
                    setVariable("user_defined_dpi", if (targeted) "180" else "160")
                }.doOCR(image).replace(Regex("\\s+"), "")
                roiTimings += "${roi.name}:${ResultScreenProbeTelemetry.elapsedMs(roiStartedAt)}"
                return value
            }
            val targetedStartedAt = System.nanoTime()
            var earlyDestination = false
            val targetedText = try {
                val accumulated = StringBuilder()
                for (roi in targetedRois) {
                    accumulated.append(recognize(roi, targeted = true))
                    val detection = detect(accumulated.toString(), capture.visual)
                    if (postResultProbe &&
                        PostResultOcrOptimizationPolicy.shouldShortCircuit(
                            screenKind = detection?.kind?.code,
                            confidence = detection?.confidence ?: 0,
                            centeredModalVisible = ScreenStateRecovery.hasCenteredModalPanel(capture.image),
                            freshCaptureAuthorized = true,
                        )
                    ) {
                        earlyDestination = true
                        path = "TARGETED_POST_RESULT_DECK_DESTINATION_EARLY_EXIT"
                        ocrSucceeded = true
                        break
                    }
                }
                accumulated.toString()
            } finally {
                targetedMs = ResultScreenProbeTelemetry.elapsedMs(targetedStartedAt)
                roiTimingSummary = roiTimings.joinToString(",")
            }
            if (earlyDestination) {
                ocrSucceeded = true
                return@runCatching targetedText
            }
            if (detect(targetedText, capture.visual) != null) {
                path = "TARGETED_ONLY"
                ocrSucceeded = true
                return@runCatching targetedText
            }
            val secondaryStartedAt = System.nanoTime()
            val secondaryText = try {
                secondaryRois.joinToString(separator = "") { recognize(it, targeted = false) }
            } finally {
                secondaryMs = ResultScreenProbeTelemetry.elapsedMs(secondaryStartedAt)
            }
            roiTimingSummary = roiTimings.joinToString(",")
            path = "TARGETED_PLUS_SECONDARY"
            ocrSucceeded = true
            targetedText + secondaryText
        }.getOrElse { error ->
            log.warn(error) { "SCREEN_RECOVERY_OCR_FAILED" }
            ""
        }
        if (fingerprint != null && ocrSucceeded) {
            exactCaptureOcrReuse.remember(capture.processId, capture.windowId, fingerprint, result)
        }
        onTimings?.invoke(
            ResultProbeOcrTimings(
                targetedMs = targetedMs,
                secondaryMs = secondaryMs,
                provider = "LEGACY_TESSERACT",
                path = path,
                totalMs = targetedMs + secondaryMs,
            ),
        )
        log.info {
            "SCREEN_RECOVERY_OCR_ROI_TIMING pid=${capture.processId} hwnd=${capture.windowId} " +
                "fingerprintMs=$fingerprintMs path=$path targetedMs=$targetedMs secondaryMs=$secondaryMs " +
                "roiMs=${roiTimingSummary.ifBlank { "UNAVAILABLE" }}"
        }
        return result
    }

    private fun screenRecoveryOcrRois(
        width: Int,
        height: Int,
    ): Pair<List<ScreenStateRoiSelector.Roi>, List<ScreenStateRoiSelector.Roi>> =
        ScreenStateRoiSelector.selectTargeted(width, height) to
            ScreenStateRoiSelector.selectSecondary(width, height)

    internal fun screenRecoveryOcrRoiNamesForTest(): List<String> {
        val (targeted, secondary) = screenRecoveryOcrRois(1920, 1080)
        return (targeted + secondary).map { it.name }
    }

    internal fun postResultOcrRoiNamesForTest(): List<String> =
        orderPostResultTargetedRois(
            screenRecoveryOcrRois(1920, 1080).first,
        ).map { it.name }

    private fun orderPostResultTargetedRois(
        rois: List<ScreenStateRoiSelector.Roi>,
    ): List<ScreenStateRoiSelector.Roi> = rois.sortedBy {
        if (it.name == ScreenStateRoiSelector.DECK_SELECTION_TITLE_ROI) 0 else 1
    }

    internal fun classifyCapturedImageForTest(image: BufferedImage, tessData: File): String? {
        val capture = Capture(
            image = image,
            bounds = Rectangle(0, 0, image.width, image.height),
            file = null,
            visual = visualSignature(image),
            gameRectKnown = false,
            gameWindowKnown = false,
            purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            capturedAtNanos = System.nanoTime(),
        )
        return detect(runOCR(capture, tessData), capture.visual)?.kind?.code
    }

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
        return VisualSignature(
            sampleHash = hash,
            warmRatio = if (samples == 0) 0.0 else warm.toDouble() / samples,
            blueRatio = if (samples == 0) 0.0 else blue.toDouble() / samples,
            loadingCentralDarkRatio = loadingCenterSignal.darkRatio,
            resultContinueGrayLightRatio = continueSignal.grayLightRatio,
            resultBannerLowSaturationRatio = bannerSignal.lowSaturationRatio,
            resultBannerWarmRatio = bannerSignal.warmRatio,
            reconnectDialogVisual = ScreenStateRecovery.looksLikeReconnectDialogVisual(image),
            matchmakingSearchVisual = MatchmakingSearchVisualPolicy.isVisible(image),
            hubModeChooserVisual = HubModeChooserVisualPolicy.isVisible(image),
            amazonAppstoreSunsetNoticeVisual = ScreenStateRecovery.looksLikeAmazonAppstoreSunsetNoticeVisual(image),
            startupQuestOverlayVisual = StartupQuestOverlayVisualClassifier.isQuestOverlay(image),
            battleNetLauncherVisual = BattleNetLauncherVisualPolicy.isVisible(image),
            tournamentModeSelectorVisual = TournamentModeSelectorVisualPolicy.isVisible(image),
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

    /** Confirm and close only the exact known startup notice on a safe, verified client. */
    private fun dismissFreshAmazonAppstoreNotice(captured: AuthorizedCapture): Boolean {
        val expectedWindow = captured.gameWindow
        val currentPid = GameUtil.findGameProcessIdForDiagnostics()?.toInt()
        if (currentPid == null || currentPid <= 0) {
            log.warn {
                "STARTUP_NOTICE_DISMISS_BLOCKED stage=initial reason=pid-unavailable " +
                    "pid=${currentPid ?: "none"} input=not-sent"
            }
            return false
        }
        fun safeToDismiss(purpose: ScreenRecoveryCapturePurpose, stage: String): Boolean {
            val currentSessionReady = ScreenStateRecovery.isCurrentGameSessionReadyForStart()
            val activeGame = WarEx.inWar || Mode.currMode === ModeEnum.GAMEPLAY
            val activeMatchmaking = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
                LifecycleTrace.hasRecentMatchmakingInputIntent()
            val terminalState = GameUtil.isTerminalGameState()
            val trustedCurrentWindow = GameUtil.findGameProcessIdForDiagnostics() == currentPid.toLong() &&
                ScriptStatus.gameHWND?.toString() == expectedWindow.toString() &&
                GameUtil.isVerifiedCurrentGameWindow(expectedWindow)
            val allowed = StartupNoticeRecoveryPolicy.mayDismiss(
                purpose = purpose,
                currentSessionReady = currentSessionReady,
                activeGame = activeGame,
                activeMatchmaking = activeMatchmaking,
                gameplayMode = Mode.currMode === ModeEnum.GAMEPLAY,
                trustedCurrentWindow = trustedCurrentWindow,
                terminalState = terminalState,
            )
            if (!allowed) log.warn {
                "STARTUP_NOTICE_DISMISS_BLOCKED stage=$stage reason=safety-policy " +
                    "purpose=${purpose.name} currentSessionReady=$currentSessionReady activeGame=$activeGame " +
                    "activeMatchmaking=$activeMatchmaking gameplayMode=${Mode.currMode === ModeEnum.GAMEPLAY} " +
                    "terminal=$terminalState trustedWindow=$trustedCurrentWindow input=not-sent"
            }
            return allowed
        }

        if (!safeToDismiss(captured.purpose, "initial")) return false
        val fresh = captureVerifiedGameScreen(stuckForMs = 0L, startupProbe = true)
        if (fresh == null) {
            log.warn { "STARTUP_NOTICE_DISMISS_BLOCKED stage=fresh reason=authorized-capture-unavailable input=not-sent" }
            return false
        }
        if (fresh.purpose !in setOf(
                ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
                ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY,
            ) ||
            fresh.gameWindow.toString() != expectedWindow.toString() ||
            GameUtil.findGameProcessIdForDiagnostics() != currentPid.toLong() ||
            !GameUtil.isVerifiedCurrentGameWindow(expectedWindow)
        ) {
            log.warn {
                "STARTUP_NOTICE_DISMISS_BLOCKED reason=window-or-purpose-changed pid=$currentPid " +
                    "capturedPurpose=${captured.purpose} freshPurpose=${fresh.purpose}"
            }
            return false
        }
        val freshText = runOCR(fresh.capture)
        val freshDetection = detect(freshText, fresh.capture.visual)
        val point = StartupNoticeRecoveryPolicy.closeButtonPoint(
            fresh.capture.image.width,
            fresh.capture.image.height,
            fresh.capture.bounds.x,
            fresh.capture.bounds.y,
        )
        val freshAmazonVisual = fresh.capture.visual.amazonAppstoreSunsetNoticeVisual
        if (freshDetection?.kind != ScreenKind.AMAZON_APPSTORE_NOTICE ||
            (freshDetection.evidence == "amazon-appstore-sunset-visual" && !freshAmazonVisual) ||
            point == null
        ) {
            log.warn {
                "STARTUP_NOTICE_DISMISS_BLOCKED stage=fresh reason=signature-or-coordinate-mismatch " +
                    "screen=${freshDetection?.kind?.code ?: "UNKNOWN"} evidence=${freshDetection?.evidence ?: "none"} " +
                    "visualSignature=$freshAmazonVisual pid=$currentPid input=not-sent"
            }
            return false
        }
        if (!safeToDismiss(fresh.purpose, "fresh")) return false
        if (!StartupInputReadiness.issueNoticeDismissPermit(
                currentPid,
                expectedWindow.toString(),
                point,
                System.currentTimeMillis(),
            )
        ) {
            log.warn {
                "STARTUP_NOTICE_DISMISS_BLOCKED stage=permit reason=issue-rejected pid=$currentPid " +
                    "hwnd=$expectedWindow point=$point input=not-sent"
            }
            return false
        }

        val accepted = MouseUtil.leftButtonClickForRecovery(point, preSessionStartupNotice = true)
        log.warn {
            "STARTUP_NOTICE_DISMISS_DISPATCHED notice=AMAZON_APPSTORE_SUNSET pid=$currentPid " +
                "hwnd=$expectedWindow pos=$point accepted=$accepted input=SendInput"
        }
        if (!accepted) return false

        EXTRA_THREAD_POOL.schedule({
            if (!WorkTimeListener.working || PauseStatus.isPause || WarEx.inWar ||
                LifecycleTrace.hasRecentActiveMatchmakingScreen() || LifecycleTrace.hasRecentMatchmakingInputIntent() ||
                GameUtil.findGameProcessIdForDiagnostics() != currentPid.toLong() ||
                !GameUtil.isVerifiedCurrentGameWindow(expectedWindow)
            ) return@schedule
            val after = captureVerifiedGameScreen(stuckForMs = 0L, startupProbe = true) ?: return@schedule
            if (after.gameWindow.toString() != expectedWindow.toString() ||
                after.purpose != ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE
            ) return@schedule
            val afterDetection = detect(runOCR(after.capture), after.capture.visual)
            if (afterDetection?.kind == ScreenKind.AMAZON_APPSTORE_NOTICE) {
                log.warn { "STARTUP_NOTICE_DISMISS_POSTCHECK result=STILL_VISIBLE action=NO_REPEAT_CLICK" }
                return@schedule
            }
            val afterModalVisible = ScreenStateRecovery.hasCenteredModalPanel(after.capture.image)
            val afterActiveMatchmaking = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
                LifecycleTrace.hasRecentMatchmakingInputIntent()
            val afterProof = StartupNoticePostcheckProofPolicy.choose(
                purpose = after.purpose,
                currentSessionReady = ScreenStateRecovery.isCurrentGameSessionReadyForStart(),
                screenKind = afterDetection?.kind?.code,
                confidence = afterDetection?.confidence ?: 0,
                evidence = afterDetection?.evidence,
                currentMode = Mode.currMode?.name,
                modalVisible = afterModalVisible,
                activeGame = StartupGamePhaseEvidencePolicy.hasAuthoritativeActiveGame(
                    warModelAuthoritative = ScreenStateRecovery.isCurrentGameSessionReadyForStart(),
                    warInProgress = WarEx.inWar,
                ),
                activeMatchmaking = afterActiveMatchmaking,
                terminalState = ScreenStateRecovery.isCurrentGameSessionReadyForStart() &&
                    GameUtil.isTerminalGameState(),
                trustedCurrentWindow = GameUtil.findGameProcessIdForDiagnostics() == currentPid.toLong() &&
                    ScriptStatus.gameHWND?.toString() == expectedWindow.toString() &&
                    GameUtil.isVerifiedCurrentGameWindow(expectedWindow),
            )
            when (afterProof) {
                StartupNoticePostcheckProofPolicy.Proof.HUB_MODE_CHOOSER -> {
                    StartupInputReadiness.recordHubModeChooser(currentPid, expectedWindow.toString())
                    log.warn {
                        "STARTUP_NOTICE_DISMISS_POSTCHECK result=CLOSED screen=TOURNAMENT " +
                            "action=HUB_MODE_CHOOSER_PROOF_RECONFIRMED evidence=${afterDetection?.evidence}"
                    }
                }
                StartupNoticePostcheckProofPolicy.Proof.ONLINE_MENU -> {
                    val postcheck = afterDetection!!
                    val deckModeObservation = if (postcheck.kind == ScreenKind.DECK_SELECTION) {
                        TournamentModeConfirmation.observeImage(after.capture.image)
                    } else null
                    StartupInputReadiness.recordStartupOnlineScreen(
                        pid = currentPid,
                        hwnd = expectedWindow.toString(),
                        screen = postcheck.kind.code,
                        confidence = postcheck.confidence,
                        evidence = postcheck.evidence,
                        expectedRunMode = if (postcheck.kind == ScreenKind.DECK_SELECTION) {
                            DeckStrategyManager.currentRunMode?.name
                        } else null,
                        observedRunMode = deckModeObservation?.observedMode?.name,
                        enabledStartButtonVisible = postcheck.kind == ScreenKind.DECK_SELECTION &&
                            ScreenWatchdog.hasEnabledDeckSelectionStartButton(after.capture.image),
                        modalVisible = ScreenStateRecovery.hasCenteredModalPanel(after.capture.image),
                    )
                    log.warn {
                        "STARTUP_NOTICE_DISMISS_POSTCHECK result=CLOSED screen=${afterDetection.kind.code} " +
                            "action=STARTUP_READINESS_RECONFIRMED " +
                            "expectedRunMode=${if (postcheck.kind == ScreenKind.DECK_SELECTION) DeckStrategyManager.currentRunMode?.name ?: "UNKNOWN" else "n/a"} " +
                            "observedRunMode=${deckModeObservation?.observedMode?.name ?: "n/a"}"
                    }
                }
                StartupNoticePostcheckProofPolicy.Proof.NONE -> {
                    StartupInputReadiness.invalidate(currentPid, expectedWindow.toString())
                    log.warn {
                        "STARTUP_NOTICE_DISMISS_POSTCHECK result=UNRESOLVED " +
                            "screen=${afterDetection?.kind?.code ?: "UNKNOWN"} action=NO_INPUT"
                    }
                }
            }
        }, 800L, TimeUnit.MILLISECONDS)
        return true
    }

    /** Exact Beta-only startup acknowledgement; no readiness/menu proof is granted by the click. */
    private fun dismissFreshBetaResultConfirmation(captured: AuthorizedCapture): Boolean {
        val expectedWindow = captured.gameWindow
        val currentPid = GameUtil.findGameProcessIdForDiagnostics()?.toInt() ?: return false
        fun allowed(purpose: ScreenRecoveryCapturePurpose): Boolean =
            BetaStartupResultConfirmationPolicy.mayAcknowledge(
                betaChannel = BuildChannel.identityToken(BuildInfo.RELEASE_CHANNEL) == "beta",
                purpose = purpose,
                currentSessionReady = ScreenStateRecovery.isCurrentGameSessionReadyForStart(),
                activeGame = WarEx.inWar || GameUtil.isTerminalGameState(),
                activeMatchmaking = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
                    LifecycleTrace.hasRecentMatchmakingInputIntent(),
                mode = Mode.currMode?.name,
                trustedCurrentWindow = GameUtil.findGameProcessIdForDiagnostics() == currentPid.toLong() &&
                    ScriptStatus.gameHWND?.toString() == expectedWindow.toString() &&
                    GameUtil.isVerifiedCurrentGameWindow(expectedWindow),
            )

        if (!allowed(captured.purpose)) {
            log.warn { "BETA_STARTUP_RESULT_CONFIRMATION_BLOCKED stage=initial reason=safety-policy input=not-sent" }
            return false
        }
        val fresh = captureVerifiedGameScreen(stuckForMs = 0L, startupProbe = true) ?: return false
        if (fresh.purpose != ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE ||
            fresh.gameWindow.toString() != expectedWindow.toString() ||
            GameUtil.findGameProcessIdForDiagnostics() != currentPid.toLong() ||
            !GameUtil.isVerifiedCurrentGameWindow(expectedWindow)
        ) {
            log.warn { "BETA_STARTUP_RESULT_CONFIRMATION_BLOCKED stage=fresh reason=window-or-purpose-changed input=not-sent" }
            return false
        }
        val freshText = runOCR(fresh.capture)
        val freshPoint = BetaStartupResultConfirmationPolicy.confirmButtonPoint(
            fresh.capture.image.width,
            fresh.capture.image.height,
            fresh.capture.bounds.x,
            fresh.capture.bounds.y,
        )
        if (!BetaStartupResultConfirmationPolicy.matches(freshText) || freshPoint == null || !allowed(fresh.purpose)) {
            log.warn {
                "BETA_STARTUP_RESULT_CONFIRMATION_BLOCKED stage=fresh reason=signature-or-safety-mismatch " +
                    "screen=${if (BetaStartupResultConfirmationPolicy.matches(freshText)) "EXACT" else "UNKNOWN"} input=not-sent"
            }
            return false
        }
        if (!StartupInputReadiness.issueBetaResultConfirmationPermit(
                currentPid,
                expectedWindow.toString(),
                freshPoint,
                System.currentTimeMillis(),
            )
        ) return false
        val accepted = MouseUtil.leftButtonClickForRecovery(
            freshPoint,
            preSessionBetaResultConfirmation = true,
        )
        log.warn {
            "BETA_STARTUP_RESULT_CONFIRMATION_DISPATCHED pid=$currentPid hwnd=$expectedWindow " +
                "pos=$freshPoint accepted=$accepted input=SendInput"
        }
        return accepted
    }

    private fun detect(
        ocrText: String,
        visual: VisualSignature,
        modalVisible: Boolean = false,
        image: BufferedImage? = null,
    ): Detection? {
        if (visual.battleNetLauncherVisual) return null
        val text = ocrText.lowercase(Locale.ROOT)
        fun has(vararg terms: String): Boolean = terms.all { text.contains(it) }

        if (BuildChannel.identityToken(BuildInfo.RELEASE_CHANNEL) == "beta" &&
            BetaStartupResultConfirmationPolicy.matches(text)
        ) {
            return Detection(
                ScreenKind.BETA_RESULT_CONFIRMATION,
                ModeEnum.LOGIN,
                100,
                "beta-previous-ranked-result-confirmation-exact-text",
            )
        }

        // More specific screens must win before generic home/login words.
        if (StartupNoticeRecoveryPolicy.isAmazonAppstoreSunsetNotice(text)) {
            return Detection(
                ScreenKind.AMAZON_APPSTORE_NOTICE,
                ModeEnum.HUB,
                100,
                "amazon-appstore-sunset-title-and-close-rois",
            )
        }
        if (visual.amazonAppstoreSunsetNoticeVisual) {
            return Detection(
                ScreenKind.AMAZON_APPSTORE_NOTICE,
                ModeEnum.HUB,
                96,
                "amazon-appstore-sunset-visual",
            )
        }
        if (ScreenStateRecovery.looksLikeServerUnavailableText(text)) {
            return Detection(ScreenKind.SERVER_UNAVAILABLE, ModeEnum.STARTUP, 100, "yogg-message-roi-eng")
        }
        // Modal text must outrank the menu visible behind it. A reconnect
        // overlay over deck selection or Home is never startup readiness.
        if (looksLikeReconnectFailureText(text)) {
            return Detection(ScreenKind.RECONNECT_FAILURE, ModeEnum.LOGIN, 97, "reconnect-failure-text")
        }
        if (looksLikeReconnectText(text)) {
            return Detection(ScreenKind.RECONNECT, ModeEnum.LOGIN, 96, "reconnect-disconnected-text")
        }
        if (visual.reconnectDialogVisual) {
            return Detection(ScreenKind.RECONNECT, ModeEnum.LOGIN, 94, "reconnect-dialog-structural-visual")
        }
        if (looksLikeStalledReconnectLoadingText(text)) {
            return Detection(ScreenKind.LOADING, ModeEnum.STARTUP, 95, "reconnect-slow-loading-text")
        }
        // The rank medal and lower-center Continue signature is specific to
        // the post-result reward page. Check it before broad hub/deck
        // geometry, which can overlap the blurred game background.
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
        // Defeat result banners can coexist with Hub/deck geometry in the
        // blurred board behind them. Preserve the dedicated result signature
        // before classifying those broader destination layouts.
        if (looksLikeResultVisual(
                visual.resultContinueGrayLightRatio,
                visual.resultBannerLowSaturationRatio,
                visual.loadingCentralDarkRatio,
                visual.resultBannerWarmRatio,
            )
        ) {
            return Detection(ScreenKind.RESULT, ModeEnum.GAMEPLAY, 92, "result-fixed-continue-visual")
        }
        if (visual.startupQuestOverlayVisual) {
            return Detection(
                ScreenKind.EVENT_QUEST_OVERLAY,
                ModeEnum.TOURNAMENT,
                96,
                "event-quest-overlay-structural-visual",
            )
        }
        if (image?.let(TournamentModeSelectorVisualPolicy::isVisible) == true) {
            return Detection(
                ScreenKind.TOURNAMENT_MODE_SELECTOR,
                ModeEnum.TOURNAMENT,
                96,
                "three-choice-mode-selector-structural-visual",
            )
        }
        val standardModeOptionVisible = text.contains("标准对战") || text.contains("标准模式")
        val wildModeOptionTextPresent = text.contains("狂野对战") || text.contains("狂野模式")
        val wildTargetLabelVisible = modalVisible && standardModeOptionVisible && wildModeOptionTextPresent &&
            image?.let(TournamentModeConfirmation::hasWildSelectorControlAtConfiguredTarget) == true
        if (StartupDeckModeNavigationPolicy.hasFreshWildSelectorEvidence(
                screenKind = StartupDeckModeNavigationPolicy.MODE_SELECTOR_SCREEN,
                modalVisible = modalVisible,
                wildTargetLabelVisible = wildTargetLabelVisible,
                ocrText = text,
            )
        ) {
            return Detection(
                ScreenKind.TOURNAMENT_MODE_SELECTOR,
                ModeEnum.TOURNAMENT,
                96,
                "mode-selector-centered-modal-with-standard-and-wild-target-label",
            )
        }
        val hubModeChoiceText = listOf("传统对战", "酒馆战棋", "竞技模式", "其他模式")
            .any(text::contains)
        // This structural four-button signature identifies the HUB chooser
        // even when OCR picks up unrelated deck/pack labels beneath it. Give
        // the visible screen precedence over noisy global OCR.
        if (visual.hubModeChooserVisual) {
            return Detection(
                ScreenKind.TOURNAMENT,
                ModeEnum.HUB,
                if (hubModeChoiceText) 96 else 94,
                if (hubModeChoiceText) "hub-mode-chooser-visual-and-text" else "hub-mode-chooser-visual",
            )
        }
        if (text.contains("选择套牌") || has("套牌", "狂野对战")) {
            return Detection(ScreenKind.DECK_SELECTION, ModeEnum.TOURNAMENT, 100, "deck-selection-title")
        }
        if (ResultPageEvidencePolicy.looksLikeResultText(text)) {
            return Detection(ScreenKind.RESULT, ModeEnum.GAMEPLAY, 95, "result-text")
        }
        // The HUB chooser's red ornament and dark lower-right background can
        // satisfy the queue color heuristic. Conflicting same-frame text is
        // never enough to promote that frame to MATCHMAKING.
        if (visual.matchmakingSearchVisual && hubModeChoiceText && !visual.hubModeChooserVisual) {
            return null
        }
        if (visual.matchmakingSearchVisual) {
            return Detection(ScreenKind.MATCHMAKING, ModeEnum.TOURNAMENT, 98, "matchmaking-search-visual")
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
        if (looksLikeLoadingText(text)) {
            return Detection(ScreenKind.LOADING, ModeEnum.STARTUP, 88, "loading-text")
        }
        // Card-back/color ratios alone also occur behind Home quests, rewards,
        // and modal overlays. Only explicit loading/reconnect text is strong
        // enough to move the state machine to STARTUP.
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
            text.contains("重新启动《炉石传说》")
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

    internal fun stalledReconnectAnchorForTest(
        acceptedReconnectAt: Long,
        observedWarningAt: Long,
        now: Long,
    ): Long = stalledReconnectAnchor(acceptedReconnectAt, observedWarningAt, now)

    private fun shouldRestartStalledReconnect(reconnectStartedAt: Long, now: Long): Boolean =
        reconnectStartedAt > 0L && now >= reconnectStartedAt &&
            now - reconnectStartedAt >= STALLED_RECONNECT_LOADING_RESTART_MS

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

    private fun consumeStalledReconnectAnchor(acceptedReconnectAt: Long, startedAt: Long): Boolean {
        return if (acceptedReconnectAt > 0L) {
            reconnectAcceptedAt.compareAndSet(acceptedReconnectAt, 0L)
        } else {
            slowReconnectWarningObservedAt.compareAndSet(startedAt, 0L)
        }
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

    internal fun classifyImageForTest(ocrText: String, image: BufferedImage): String? = detect(
        ocrText,
        visualSignature(image),
        ScreenStateRecovery.hasCenteredModalPanel(image),
        image,
    )?.kind?.code

    private fun startupDeckSelectionVisualDetection(
        purpose: ScreenRecoveryCapturePurpose,
        image: BufferedImage,
        ocrUnknown: Boolean,
        questOverlayVisible: Boolean,
        matchmakingVisible: Boolean,
        modalVisible: Boolean,
        configuredDeckSelected: Boolean,
    ): Detection? {
        val allowedPurpose = purpose in setOf(
            ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
            ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
        )
        if (!allowedPurpose || !ocrUnknown || questOverlayVisible || matchmakingVisible || modalVisible ||
            !configuredDeckSelected || !ScreenWatchdog.hasEnabledDeckSelectionStartButton(image)
        ) return null
        return Detection(ScreenKind.DECK_SELECTION, ModeEnum.TOURNAMENT, 94, "deck-selection-start-control-visual")
    }

    internal fun classifyStartupDeckSelectionVisualForTest(
        ocrText: String,
        image: BufferedImage,
        purpose: ScreenRecoveryCapturePurpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
        configuredDeckSelected: Boolean = true,
    ): String? {
        val detected = detect(ocrText, visualSignature(image))
        return (detected ?: startupDeckSelectionVisualDetection(
            purpose = purpose,
            image = image,
            ocrUnknown = detected == null,
            questOverlayVisible = StartupQuestOverlayVisualClassifier.isQuestOverlay(image),
            matchmakingVisible = visualSignature(image).matchmakingSearchVisual,
            modalVisible = ScreenStateRecovery.hasCenteredModalPanel(image),
            configuredDeckSelected = configuredDeckSelected,
        ))?.kind?.code
    }

    internal fun classificationEvidenceForImageTest(ocrText: String, image: BufferedImage): String? = detect(
        ocrText,
        visualSignature(image),
    )?.evidence

    /** A fresh queue screen is authoritative proof that result cleanup reached its post-result destination. */
    internal fun confirmPendingTerminalCleanupAtMatchmaking(
        screenKind: String,
        confidence: Int,
        freshCaptureAuthorized: Boolean,
    ): Boolean {
        if (!FreshPostResultDestinationPolicy.isConfirmed(screenKind, confidence, freshCaptureAuthorized)) return false
        val guard = club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
        if (guard.isTerminalCleanupPending()) {
            val capability = guard.existingTerminalCleanupCapability() ?: return false
            if (!guard.confirmCompleted("SCREEN_MATCHMAKING", capability)) return false
        }
        return club.xiaojiawei.hsscript.listener.log.PowerLogListener.confirmCurrentGameTerminalDestination(
            screenKind = screenKind,
            confidence = confidence,
            freshCaptureAuthorized = freshCaptureAuthorized,
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

    /** Offline replay seam for exact captured client frames. */
    internal fun classifyImageForResultFixture(image: BufferedImage): String? =
        detect("", visualSignature(image))?.kind?.code

    internal fun continueControlVisibleForFixture(image: BufferedImage): Boolean =
        ResultPageEvidencePolicy.continueControlVisible(visualSignature(image).resultContinueGrayLightRatio)

    /**
     * Re-check the actual desktop after a result-page input was sent.
     *
     * `MouseUtil` can report that an event was queued even when the client did
     * not consume it. Return false only for an explicitly recognized
     * post-result destination; a live board, transitional screen, or
     * inconclusive capture returns null and cannot release the rank barrier.
     */
    internal fun observeResultScreenForRecovery(): ResultScreenObservation {
        val probeId = ResultScreenProbeTelemetry.nextProbeId()
        val probeStartedAt = System.nanoTime()
        var captureTimings = ResultProbeCaptureTimings()
        var ocrTimings = ResultProbeOcrTimings()
        var decisionMs = 0L
        var decisionPath = "CAPTURE_UNAVAILABLE"
        fun report(observation: ResultScreenObservation): ResultScreenObservation {
            log.info {
                "SCREEN_RECOVERY_RESULT_PROBE_TIMING " +
                    ResultScreenProbeTelemetry.format(
                        probeId = probeId,
                        windowResolveMs = 0L,
                        capture = captureTimings,
                        ocr = ocrTimings,
                        decisionMs = decisionMs,
                        totalMs = ResultScreenProbeTelemetry.elapsedMs(probeStartedAt),
                        resultVisible = observation.resultVisible,
                        rankProgressVisible = observation.rankProgressVisible,
                        destinationScreen = observation.destinationScreen,
                        captureAuthorized = observation.captureAuthorized,
                        continueControlVisible = observation.continueControlVisible,
                    ) + " path=$decisionPath implementation=UPSTREAM"
            }
            return observation
        }
        return runCatching {
        val captureStartedAt = System.nanoTime()
        val authorizedCapture = captureVerifiedGameScreen()
        if (authorizedCapture == null) {
            captureTimings = ResultProbeCaptureTimings(
                authorizedCaptureMs = ResultScreenProbeTelemetry.elapsedMs(captureStartedAt),
            )
            return@runCatching report(ResultScreenObservation(null, captureAuthorized = false))
        }
        captureTimings = authorizedCapture.capture.resultProbeTimings.copy(
            authorizedCaptureMs = ResultScreenProbeTelemetry.elapsedMs(captureStartedAt),
        )
        val visual = authorizedCapture.capture.visual
        if (ScreenWatchdog.hasVerifiedGameMenuOverlay(authorizedCapture.capture.image)) {
            decisionPath = "VERIFIED_GAME_MENU_OVERLAY"
            return@runCatching report(ResultScreenObservation(
                resultVisible = null,
                captureAuthorized = true,
                gameMenuOverlayVisible = true,
                capturedAtNanos = authorizedCapture.capture.capturedAtNanos,
            ))
        }
        if (visual.startupQuestOverlayVisual) {
            decisionPath = "EXACT_EVENT_QUEST_VISUAL"
            log.info { "SCREEN_RECOVERY_RESULT_FAST_OVERLAY kind=EVENT_QUEST_OVERLAY ocr=skipped" }
            return@runCatching report(ResultScreenObservation(
                resultVisible = null,
                captureAuthorized = true,
                gameMenuOverlayVisible = true,
                capturedAtNanos = authorizedCapture.capture.capturedAtNanos,
            ))
        }
        val visualDecisionStartedAt = System.nanoTime()
        val fastVisual = ResultPageEvidencePolicy.fastVisualObservation(
            visual.resultContinueGrayLightRatio,
            visual.resultBannerLowSaturationRatio,
            visual.loadingCentralDarkRatio,
            visual.resultBannerWarmRatio,
        )
        if (fastVisual != null) {
            decisionMs = ResultScreenProbeTelemetry.elapsedMs(visualDecisionStartedAt)
            decisionPath = "FAST_VISUAL"
            log.info {
                "SCREEN_RECOVERY_RESULT_FAST_VISUAL result=${fastVisual.resultVisible ?: "UNKNOWN"} " +
                    "rankProgress=${fastVisual.rankProgressVisible} continueBand=true ocr=skipped"
            }
            return@runCatching report(ResultScreenObservation(
                resultVisible = fastVisual.resultVisible,
                captureAuthorized = true,
                visualOnlyResultEvidence = fastVisual.resultVisible == true && !fastVisual.rankProgressVisible,
                rankProgressVisible = fastVisual.rankProgressVisible,
                continueControlVisible = fastVisual.continueControlVisible,
                capturedAtNanos = authorizedCapture.capture.capturedAtNanos,
            ))
        }
        val ocrText = runOCR(
            authorizedCapture.capture,
            File(TESS_DATA_PATH),
            postResultProbe = BuildChannel.identityToken(BuildInfo.RELEASE_CHANNEL) == "beta",
            onTimings = { ocrTimings = it },
        )
        val decisionStartedAt = System.nanoTime()
        val detection = detect(ocrText, authorizedCapture.capture.visual)
        decisionMs = ResultScreenProbeTelemetry.elapsedMs(decisionStartedAt)
        decisionPath = if (detection == null) "OCR_UNKNOWN" else "OCR_${detection.kind.code}"
        if (detection?.kind?.code == "DECK_SELECTION") {
            recordResultDeckSelectionStartProof(
                detection = detection,
                capture = authorizedCapture.capture,
                pid = authorizedCapture.capture.processId.toInt(),
                hwnd = authorizedCapture.gameWindow.toString(),
                captureAuthorized = authorizedCapture.foregroundVerified,
                modalVisible = ScreenStateRecovery.hasCenteredModalPanel(authorizedCapture.capture.image),
            )
        }
        report(ResultScreenObservation(
            resultVisible = resultPageVisibility(detection),
            captureAuthorized = true,
            visualOnlyResultEvidence = detection?.evidence == "result-fixed-continue-visual",
            rankProgressVisible = detection?.kind?.code == "RANK_PROGRESS_CONTINUATION",
            continueControlVisible = ResultPageEvidencePolicy.continueControlVisible(
                authorizedCapture.capture.visual.resultContinueGrayLightRatio,
            ),
            destinationScreen = detection?.kind?.code?.takeIf { code -> POST_RESULT_DESTINATIONS.any { it.code == code } },
            destinationConfidence = detection?.confidence ?: 0,
            capturedAtNanos = authorizedCapture.capture.capturedAtNanos,
        ))
        }.getOrElse { error ->
            log.warn(error) { "SCREEN_RECOVERY_RESULT_POSTCHECK_FAILED" }
            decisionPath = "FAILED"
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
        modalVisible: Boolean,
    ) {
        val mode = TournamentModeConfirmation.observeImageVisualOnly(capture.image)
        val expectedMode = DeckStrategyManager.currentRunMode?.name
        val captureAgeMs = (System.nanoTime() - capture.capturedAtNanos) / 1_000_000L
        val startVisible = ScreenWatchdog.hasEnabledDeckSelectionStartButton(capture.image)
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
                "evidence=${detection.evidence} startVisible=$startVisible modal=$modalVisible pid=${pid ?: "none"} hwnd=$hwnd"
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
            text.contains("离线状态")
        val recoveryHint = text.contains("重新连接") ||
            text.contains("重新接") ||
            text.contains("离线")
        return disconnected && recoveryHint
    }

    private fun scheduleExactReconnectThresholdRecheck(firstObservedAtMs: Long) {
        if (!exactReconnectRecheckSchedule.reserve(firstObservedAtMs)) return
        val expectedPid = GameUtil.findGameProcessIdForDiagnostics()?.toInt()
        log.warn {
            "SCREEN_RECOVERY_RECONNECT_RECHECK_SCHEDULED delayMs=${ExactReconnectRecheckSchedule.RECHECK_DELAY_MS} " +
                "expectedPid=${expectedPid ?: "none"} capture=FRESH_AUTHORIZED purpose=IDLE_CLIENT_PROBE"
        }
        EXTRA_THREAD_POOL.schedule({
            val now = System.currentTimeMillis()
            if (!exactReconnectRecheckSchedule.isDue(now)) return@schedule
            exactReconnectRecheckSchedule.complete()
            val currentPid = GameUtil.findGameProcessIdForDiagnostics()?.toInt()
            if (!WorkTimeListener.working || PauseStatus.isPause || WarEx.inWar ||
                expectedPid == null || currentPid != expectedPid ||
                LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
                LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard() ||
                GameUtil.isTerminalGameState()
            ) {
                log.warn {
                    "SCREEN_RECOVERY_RECONNECT_RECHECK_SKIPPED reason=final-safety-fence " +
                        "working=${WorkTimeListener.working} paused=${PauseStatus.isPause} inWar=${WarEx.inWar} " +
                        "expectedPid=${expectedPid ?: "none"} currentPid=${currentPid ?: "none"} " +
                        "activeQueue=${LifecycleTrace.hasRecentActiveMatchmakingScreen() || LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard()} " +
                        "terminal=${GameUtil.isTerminalGameState()} action=NO_RESTART"
                }
                return@schedule
            }
            log.warn {
                "SCREEN_RECOVERY_RECONNECT_RECHECK_STARTED elapsedMs=${now - firstObservedAtMs} " +
                    "expectedPid=$expectedPid capture=FRESH_AUTHORIZED"
            }
            inspectAndRecover(
                stuckForMs = ExactReconnectRecheckSchedule.RECHECK_DELAY_MS,
                stateFingerprint = "exact-reconnect-threshold-recheck",
                startupProbe = true,
                exactReconnectRecheck = true,
            )
        }, ExactReconnectRecheckSchedule.RECHECK_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun restartClientForIndefiniteReconnect(now: Long): Boolean {
        val pid = GameUtil.findGameProcessIdForDiagnostics()?.toInt()
        val hwnd = ScriptStatus.gameHWND
        val currentSessionReady = ScreenStateRecovery.isCurrentGameSessionReadyForStart()
        val freshActiveQueue = LifecycleTrace.hasRecentActiveMatchmakingScreen()
        val activeGameEvidence = ScreenStateRecovery.hasAuthoritativeCurrentGameEvidenceForReconnect()
        val terminalState = runCatching { GameUtil.isTerminalGameState() }.getOrDefault(true)
        val reconnectSafetyDeferred = ReconnectRestartEvidencePolicy.shouldDefer(
            exactReconnectVisible = true,
            freshActiveQueue = freshActiveQueue,
            currentSessionReady = currentSessionReady,
            activeGame = WarEx.inWar,
            terminalState = terminalState,
        )
        if (!WorkTimeListener.working || PauseStatus.isPause || reconnectSafetyDeferred ||
            pid == null || pid <= 0 || hwnd == null ||
            GameUtil.findGameProcessIdForDiagnostics() != pid.toLong() ||
            !GameUtil.isVerifiedCurrentGameWindow(hwnd)
        ) {
            log.warn {
                "SCREEN_RECOVERY_RECONNECT_RESTART_DEFERRED reason=final-safety-check " +
                    "working=${WorkTimeListener.working} paused=${PauseStatus.isPause} " +
                "terminal=$terminalState freshActiveQueue=$freshActiveQueue activeGameEvidence=$activeGameEvidence " +
                    "currentSessionReady=$currentSessionReady staleMatchmakingIntentIgnored=${LifecycleTrace.hasRecentMatchmakingInputIntent()} " +
                    "staleWarLatch=${WarEx.inWar} " +
                    "pid=${pid ?: "none"} hwnd=${hwnd ?: "none"}"
            }
            return false
        }
        if (!clientRestartInFlight.tryAcquire(pid.toLong(), now)) {
            log.warn { "SCREEN_RECOVERY_RECONNECT_RESTART_COALESCED reason=client-restart-in-flight pid=$pid" }
            return false
        }
        return try {
            log.error {
                "SCREEN_RECOVERY_RECONNECT_RESTART_CONFIRMED pid=$pid hwnd=$hwnd " +
                    "activeGame=${WarEx.inWar} activeQueue=false action=RESTART_CLIENT_NO_PAUSE"
            }
            Core.restartFromIdleWatchdog()
            true
        } catch (error: Throwable) {
            clientRestartInFlight.release()
            log.error(error) { "SCREEN_RECOVERY_RECONNECT_RESTART_FAILED pid=$pid action=RELEASE_RESTART_GATE" }
            false
        }
    }

    private fun handleGenericIdleUnblock(
        authorizedCapture: AuthorizedCapture,
        detection: Detection?,
        currentPid: Int?,
        stateFingerprint: String,
        stuckForMs: Long,
        stateStillCurrent: () -> Boolean = { true },
    ): Boolean {
        val capture = authorizedCapture.capture
        if (StartupHubModeChooserPolicy.shouldDeferGenericIdleUnblock(
                screenKind = detection?.kind?.code,
                screenMode = detection?.mode?.name,
                hubModeChooserVisual = capture.visual.hubModeChooserVisual,
            )
        ) {
            stuckScreenUnblockPolicy.reset()
            log.info {
                "STUCK_SCREEN_UNBLOCK_SKIPPED reason=recognized-hub-mode-chooser " +
                    "action=WAIT_FOR_AUTHORIZED_TRADITIONAL_ENTRY activeGame=${WarEx.inWar} " +
                    "activeQueue=${capture.visual.matchmakingSearchVisual || LifecycleTrace.hasRecentActiveMatchmakingScreen()}"
            }
            return true
        }
        val exactReconnect = detection?.kind == ScreenKind.RECONNECT && capture.visual.reconnectDialogVisual
        val currentFrameProof = StuckScreenUnblockFrameIdentity.create(
            processId = currentPid ?: capture.processId.toInt(),
            windowId = authorizedCapture.gameWindow.toString(),
            perceptualFingerprint = stuckScreenPerceptualFingerprint(capture.image),
            exactReconnectDialog = exactReconnect,
        )
        val staleQueueHistoryCleared = if (exactReconnect) {
            LifecycleTrace.supersedeActiveMatchmakingScreenForReconnect()
        } else false
        if (staleQueueHistoryCleared) {
            log.warn {
                "STUCK_SCREEN_UNBLOCK_QUEUE_HISTORY_CLEARED reason=exact-current-reconnect " +
                    "staleIntentIgnored=${LifecycleTrace.hasRecentMatchmakingInputIntent()}"
            }
        }
        val expectedModeForLadder = DeckStrategyManager.currentRunMode?.name
        val observedModeForLadder = if (detection?.kind == ScreenKind.DECK_SELECTION) {
            TournamentModeConfirmation.observeImage(capture.image).observedMode.name
        } else null
        val wrongModeDeckCandidate = isConfirmedWrongModeDeck(
            detection, capture.image, expectedModeForLadder, observedModeForLadder,
        )
        if (detection?.kind == ScreenKind.DECK_SELECTION) {
            if (!wrongModeDeckCandidate) {
                stuckScreenUnblockPolicy.reset()
                log.info { "STUCK_SCREEN_UNBLOCK_SKIPPED reason=deck-mode-not-confirmed-opposite action=NO_GENERIC_CLICK" }
                return true
            }
            log.warn {
                "STUCK_SCREEN_UNBLOCK_MODE_MISMATCH recognized=true expectedMode=$expectedModeForLadder " +
                    "observedMode=$observedModeForLadder action=BOUNDED_TOP_LEFT_LADDER start=BLOCKED"
            }
        }
        val eligiblePurpose = authorizedCapture.purpose in setOf(
            ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
            ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
        )
        val exactWindow = currentPid != null &&
            GameUtil.findGameProcessIdForDiagnostics() == currentPid.toLong() &&
            ScriptStatus.gameHWND?.toString() == authorizedCapture.gameWindow.toString() &&
            GameUtil.isVerifiedCurrentGameWindow(authorizedCapture.gameWindow, currentPid.toLong())
        val activeGame = ScreenStateRecovery.isCurrentGameSessionReadyForStart() || WarEx.inWar ||
            Mode.currMode == ModeEnum.GAMEPLAY || Mode.nextMode == ModeEnum.GAMEPLAY
        val activeQueue = capture.visual.matchmakingSearchVisual ||
            LifecycleTrace.hasRecentActiveMatchmakingScreen() || LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard()
        val resultOrTerminal = GameUtil.isTerminalGameState() || MandatoryRankSurrenderGuard.isPending() ||
            MandatoryRankSurrenderGuard.isTerminalCleanupPending() || ScreenStateRecovery.looksLikeResultVisual(
                capture.visual.resultContinueGrayLightRatio,
                capture.visual.resultBannerLowSaturationRatio,
                capture.visual.loadingCentralDarkRatio,
                capture.visual.resultBannerWarmRatio,
            )
        val evidence = StuckScreenUnblockPolicy.Evidence(
            nowMs = System.currentTimeMillis(),
            stableForMs = stuckForMs,
            fingerprint = currentFrameProof.fingerprint,
            // Capture authority minted this exact probe; long OCR must not
            // make its screen classification retroactively stale. Before any
            // click below we acquire a second, immediately fresh frame.
            captureFresh = eligiblePurpose,
            exactWindowVerified = exactWindow,
            eligibleIdleProbe = eligiblePurpose,
            recognizedSafeBlocker = (detection == null || exactReconnect || isSafeIdleUnblockScreen(detection, capture.visual) || wrongModeDeckCandidate) &&
                (!capture.visual.reconnectDialogVisual || exactReconnect) && !capture.visual.battleNetLauncherVisual &&
                !capture.visual.amazonAppstoreSunsetNoticeVisual && !capture.visual.matchmakingSearchVisual &&
                !resultOrTerminal,
            currentSessionReady = ScreenStateRecovery.isCurrentGameSessionReadyForStart(),
            activeGame = activeGame,
            activeQueue = activeQueue,
            terminal = resultOrTerminal,
            paused = PauseStatus.isPause,
            modeMismatchDeck = wrongModeDeckCandidate,
            reconnect = false,
            resultOrTerminalScreen = detection?.kind in setOf(ScreenKind.RESULT, ScreenKind.BETA_RESULT_CONFIRMATION,
                ScreenKind.RANK_PROGRESS_CONTINUATION) || resultOrTerminal,
            activeGameplayScreen = activeGame,
            matchmakingScreen = activeQueue,
            semanticScreenIdentity = currentFrameProof.screenIdentity,
            automationRunId = WorkTimeListener.recoveryRunId(),
        )
        // Non-modal unknown and exact wrong-mode deck frames use the bounded, fresh-frame ladder.
        if (!evidence.recognizedSafeBlocker) return false
        val decision = stuckScreenUnblockPolicy.observe(evidence)
        val point = StartupQuestOverlayDismissPolicy.topLeftPixel(capture.bounds)
        log.warn {
            "STUCK_SCREEN_UNBLOCK_DECISION action=${decision.action} reason=${decision.reason} " +
                "click=${decision.clickNumber}/${StuckScreenUnblockPolicy.MAX_TOP_LEFT_CLICKS} " +
                "fingerprint=${evidence.fingerprint} purpose=${authorizedCapture.purpose} " +
                "perceptualFingerprint=${currentFrameProof.perceptualFingerprint} " +
                "frameIdentityReason=${currentFrameProof.reason} screen=${detection?.kind?.code ?: "UNKNOWN"} " +
                "fresh=${evidence.captureFresh} exactWindow=$exactWindow activeGame=$activeGame " +
                "activeQueue=$activeQueue terminal=$resultOrTerminal paused=${PauseStatus.isPause} " +
                "point=$point"
        }
        when (decision.action) {
            StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT -> {
                val fresh = captureVerifiedGameScreen(stuckForMs = stuckForMs, startupProbe = true)
                val freshVisualDetection = fresh?.let {
                    detect(
                        "",
                        it.capture.visual,
                        ScreenStateRecovery.hasCenteredModalPanel(it.capture.image),
                        it.capture.image,
                    ) ?: startupDeckSelectionVisualDetection(
                        purpose = it.purpose,
                        image = it.capture.image,
                        ocrUnknown = true,
                        questOverlayVisible = it.capture.visual.startupQuestOverlayVisual,
                        matchmakingVisible = it.capture.visual.matchmakingSearchVisual,
                        modalVisible = ScreenStateRecovery.hasCenteredModalPanel(it.capture.image) &&
                            !ScreenStateRecovery.isWrongModeDeckTooltip(it.capture.image),
                        configuredDeckSelected = DeckStrategyManager.currentDeckStrategy != null &&
                            DeckStrategyManager.currentRunMode != null,
                    )
                }
                val freshFrame = fresh?.let {
                    val isSameExactReconnectDialog = exactReconnect && fresh.capture.visual.reconnectDialogVisual
                    stuckScreenFrameIdentity(
                        capture = it.capture,
                        windowId = it.gameWindow.toString(),
                        detection = freshVisualDetection,
                        expectedMode = expectedModeForLadder,
                        exactReconnect = isSameExactReconnectDialog,
                    )
                }
                val freshAuthorized = fresh != null && currentPid != null &&
                    fresh.gameWindow.toString() == authorizedCapture.gameWindow.toString() &&
                    fresh.purpose in setOf(ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
                        ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE, ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE) &&
                    stateStillCurrent() &&
                    System.nanoTime() - fresh.capture.capturedAtNanos <= 5_000_000_000L &&
                    freshFrame != null && StuckScreenUnblockFrameIdentity.isSameClientScreen(currentFrameProof, freshFrame) &&
                    (freshVisualDetection == null || exactReconnect &&
                        freshVisualDetection.kind == ScreenKind.RECONNECT && fresh.capture.visual.reconnectDialogVisual ||
                        (isSafeIdleUnblockScreen(freshVisualDetection, fresh.capture.visual) ||
                            isConfirmedWrongModeDeck(
                                freshVisualDetection, fresh.capture.image, expectedModeForLadder,
                            )) &&
                        freshVisualDetection?.kind == detection?.kind) &&
                    (!fresh.capture.visual.reconnectDialogVisual || exactReconnect) &&
                    !fresh.capture.visual.battleNetLauncherVisual && !fresh.capture.visual.matchmakingSearchVisual &&
                    !ScreenStateRecovery.looksLikeResultVisual(
                        fresh.capture.visual.resultContinueGrayLightRatio,
                        fresh.capture.visual.resultBannerLowSaturationRatio,
                        fresh.capture.visual.loadingCentralDarkRatio,
                        fresh.capture.visual.resultBannerWarmRatio,
                    ) && !GameUtil.isTerminalGameState() &&
                    !ScreenStateRecovery.isCurrentGameSessionReadyForStart() && !WarEx.inWar &&
                    !LifecycleTrace.hasRecentActiveMatchmakingScreen() &&
                    !LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard() && !PauseStatus.isPause &&
                    WorkTimeListener.working && GameUtil.findGameProcessIdForDiagnostics() == currentPid.toLong() &&
                    GameUtil.isVerifiedCurrentGameWindow(authorizedCapture.gameWindow, currentPid.toLong())
                val freshPoint = fresh?.let { StartupQuestOverlayDismissPolicy.topLeftPixel(it.capture.bounds) }
                var dispatchedClickCount = 0
                val accepted = StuckScreenUnblockDispatch.dispatchTopLeftBatch(
                    batchInFlight = stuckScreenUnblockBatchInFlight,
                    action = decision.action,
                    firstClick = decision.clickNumber,
                    freshCaptureAuthorized = freshAuthorized,
                    point = freshPoint,
                    expectedFingerprint = evidence.fingerprint,
                    completionTimeMs = { System.currentTimeMillis() },
                    dispatchClick = { targetPoint, clickNumber ->
                        val clickAccepted = currentPid != null && MouseUtil.leftButtonClickForIdleStuckRecovery(
                            targetPoint, currentPid.toLong(), authorizedCapture.gameWindow,
                        )
                        dispatchedClickCount++
                        log.warn {
                            "STUCK_SCREEN_UNBLOCK_CLICK click=$clickNumber/3 point=$targetPoint " +
                                "accepted=$clickAccepted capture=${fresh?.capture?.file?.absolutePath ?: "not-saved"}"
                        }
                        clickAccepted
                    },
                    completeBatch = stuckScreenUnblockPolicy::completeTopLeftClickBatch,
                    requestFreshRecheck = {
                        val scheduled = stuckScreenUnblockRecheckGate.schedule(
                            nowMs = System.currentTimeMillis(),
                            enqueue = { task ->
                                EXTRA_THREAD_POOL.schedule({ task() }, 800L, TimeUnit.MILLISECONDS)
                            },
                        ) { lease ->
                            try {
                                val completed = inspectAndRecover(
                                    stuckForMs,
                                    stateFingerprint,
                                    startupProbe = true,
                                    stuckScreenUnblockRecheck = true,
                                    stuckScreenUnblockRecheckToken = lease.generation,
                                    stateStillCurrent = {
                                        stateStillCurrent() && stuckScreenUnblockRecheckGate.isCurrentGeneration(
                                            lease.generation, System.currentTimeMillis(),
                                        )
                                    },
                                )
                                log.info {
                                    "STUCK_SCREEN_UNBLOCK_RECHECK result=COMPLETED " +
                                        "generation=${lease.generation} inspectReturned=$completed"
                                }
                            } catch (error: Throwable) {
                                log.error(error) {
                                    "STUCK_SCREEN_UNBLOCK_RECHECK result=FAILED generation=${lease.generation} " +
                                        "action=RELEASE_GATE"
                                }
                            }
                        }
                        log.warn {
                            "STUCK_SCREEN_UNBLOCK_RECHECK scheduled=$scheduled delayMs=800 clickCount=3 " +
                                "timeoutMs=${StuckScreenUnblockRecheckGate.DEFAULT_TIMEOUT_MS} " +
                                "action=${if (scheduled) "FRESH_CAPTURE" else "CONTINUE_BOUNDED_RECOVERY"} " +
                                "reason=${if (scheduled) "accepted" else "probe-in-flight-or-scheduler-rejected"}"
                        }
                    },
                )
                if (!accepted) stuckScreenUnblockPolicy.reset()
                log.warn {
                    "STUCK_SCREEN_UNBLOCK_BATCH complete=$accepted clickCount=$dispatchedClickCount " +
                        "reason=${if (accepted) "dispatched" else "not-accepted-fresh-evidence-click-or-overlap"} " +
                        "sourcePoint=$point freshPoint=$freshPoint fingerprint=${evidence.fingerprint} " +
                        "perceptualFingerprint=${currentFrameProof.perceptualFingerprint} " +
                        "freshPerceptualFingerprint=${freshFrame?.perceptualFingerprint ?: "none"} " +
                        "frameIdentityReason=${freshFrame?.reason ?: "fresh-capture-unavailable"}"
                }
                true
            }
            StuckScreenUnblockPolicy.Action.REPROBE -> {
                log.warn { "STUCK_SCREEN_UNBLOCK_RECHECK result=STILL_BLOCKED fingerprint=${evidence.fingerprint}" }
                true
            }
            StuckScreenUnblockPolicy.Action.UNBLOCKED -> {
                stuckScreenUnblockPolicy.reset()
                log.warn {
                    "STUCK_SCREEN_UNBLOCK_RECHECK result=UNBLOCKED fingerprint=${evidence.fingerprint} " +
                        "identityReason=${currentFrameProof.reason}"
                }
                true
            }
            StuckScreenUnblockPolicy.Action.RESTART_CLIENT -> {
                val fresh = captureVerifiedGameScreen(stuckForMs = stuckForMs, startupProbe = true)
                val freshDetection = fresh?.let {
                    if (exactReconnect && it.capture.visual.reconnectDialogVisual) {
                        Detection(ScreenKind.RECONNECT, ModeEnum.LOGIN, 100, "reconnect-dialog-structural-visual")
                    } else detect(
                        "",
                        it.capture.visual,
                        ScreenStateRecovery.hasCenteredModalPanel(it.capture.image),
                        it.capture.image,
                    ) ?: startupDeckSelectionVisualDetection(
                        purpose = it.purpose,
                        image = it.capture.image,
                        ocrUnknown = true,
                        questOverlayVisible = it.capture.visual.startupQuestOverlayVisual,
                        matchmakingVisible = it.capture.visual.matchmakingSearchVisual,
                        modalVisible = ScreenStateRecovery.hasCenteredModalPanel(it.capture.image) &&
                            !ScreenStateRecovery.isWrongModeDeckTooltip(it.capture.image),
                        configuredDeckSelected = DeckStrategyManager.currentDeckStrategy != null &&
                            DeckStrategyManager.currentRunMode != null,
                    )
                }
                val freshIsSameExactReconnectDialog = exactReconnect &&
                    fresh != null &&
                    fresh.capture.visual.reconnectDialogVisual
                val finalFrame = fresh?.let {
                    stuckScreenFrameIdentity(
                        capture = it.capture,
                        windowId = it.gameWindow.toString(),
                        detection = freshDetection,
                        expectedMode = expectedModeForLadder,
                        exactReconnect = freshIsSameExactReconnectDialog,
                    )
                }
                val finalSafe = fresh != null && currentPid != null &&
                    fresh.gameWindow.toString() == authorizedCapture.gameWindow.toString() &&
                    stateStillCurrent() &&
                    System.nanoTime() - fresh.capture.capturedAtNanos <= 5_000_000_000L &&
                    finalFrame != null && StuckScreenUnblockFrameIdentity.isSameClientScreen(currentFrameProof, finalFrame) &&
                    !fresh.capture.visual.startupQuestOverlayVisual &&
                    (!fresh.capture.visual.reconnectDialogVisual || exactReconnect) &&
                    !fresh.capture.visual.matchmakingSearchVisual &&
                    !fresh.capture.visual.battleNetLauncherVisual && !fresh.capture.visual.amazonAppstoreSunsetNoticeVisual &&
                    (if (exactReconnect) freshDetection?.kind == ScreenKind.RECONNECT &&
                        fresh.capture.visual.reconnectDialogVisual else freshDetection == null ||
                        (isSafeIdleUnblockScreen(freshDetection, fresh.capture.visual) ||
                            isConfirmedWrongModeDeck(freshDetection, fresh.capture.image, expectedModeForLadder)) &&
                        freshDetection?.kind == detection?.kind) &&
                    !ScreenStateRecovery.hasAuthoritativeCurrentGameEvidenceForReconnect() &&
                    !PowerLogListener.hasCurrentGameCompleteTerminalPowerLogEvidence() &&
                    !ScreenStateRecovery.isCurrentGameSessionReadyForStart() &&
                    !WarEx.inWar && Mode.currMode != ModeEnum.GAMEPLAY && Mode.nextMode != ModeEnum.GAMEPLAY &&
                    !GameUtil.isTerminalGameState() && !MandatoryRankSurrenderGuard.isPending() &&
                    !MandatoryRankSurrenderGuard.isTerminalCleanupPending() && !PauseStatus.isPause &&
                    WorkTimeListener.working && !LifecycleTrace.hasRecentActiveMatchmakingScreen() &&
                    !LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard() &&
                    GameUtil.findGameProcessIdForDiagnostics() == currentPid.toLong() &&
                    GameUtil.isVerifiedCurrentGameWindow(authorizedCapture.gameWindow, currentPid.toLong())
                val restartAccepted = finalSafe && currentPid != null &&
                    clientRestartInFlight.tryAcquire(currentPid.toLong(), System.currentTimeMillis())
                log.error {
                    "STUCK_SCREEN_UNBLOCK_RESTART decision=${if (restartAccepted) "RESTART_CLIENT" else "DEFER"} " +
                        "reason=${if (!finalSafe) decision.reason else if (!restartAccepted) "client-restart-in-flight" else "final-fences-cleared"} " +
                        "finalGuard=$finalSafe freshScreen=${freshDetection?.kind?.code ?: "UNKNOWN"} " +
                        "fingerprint=${evidence.fingerprint} " +
                        "freshPerceptualFingerprint=${finalFrame?.perceptualFingerprint ?: "none"} " +
                        "frameIdentityReason=${finalFrame?.reason ?: "fresh-capture-unavailable"} " +
                        "pause=${PauseStatus.isPause} action=${if (restartAccepted) "idle-watchdog" else "NO_ACTION"}"
                }
                StuckScreenUnblockDispatch.dispatchRestartIfAuthorized(
                    action = decision.action,
                    finalSafetyPassed = restartAccepted,
                    restart = { Core.restartFromIdleWatchdog() },
                )
                true
            }
            StuckScreenUnblockPolicy.Action.WAIT,
            -> true
            StuckScreenUnblockPolicy.Action.STOP -> {
                if (decision.reason == "client-restart-cap-exhausted") {
                    log.error {
                        "STUCK_SCREEN_UNBLOCK_ALERT reason=client-restart-cap-exhausted " +
                            "automationRunId=${evidence.automationRunId} semanticScreen=${evidence.semanticScreenIdentity} " +
                            "action=NO_MORE_CLIENT_RESTARTS activeGame=$activeGame activeQueue=$activeQueue " +
                            "terminal=$resultOrTerminal paused=${PauseStatus.isPause}"
                    }
                }
                true
            }
        }
        return true
    }

    private fun stuckScreenFrameIdentity(
        capture: Capture,
        windowId: String,
        detection: Detection?,
        expectedMode: String?,
        exactReconnect: Boolean,
    ): StuckScreenUnblockFrameIdentity.Proof {
        val perceptual = stuckScreenPerceptualFingerprint(capture.image)
        val observedMode = if (detection?.kind == ScreenKind.DECK_SELECTION) {
            TournamentModeConfirmation.observeImage(capture.image).observedMode.name
        } else null
        val semantic = StuckScreenUnblockFrameIdentity.semanticScreenIdentity(
            screenKind = detection?.kind?.code,
            screenMode = detection?.mode?.name,
            expectedRunMode = expectedMode,
            observedRunMode = observedMode,
        )
        return StuckScreenUnblockFrameIdentity.create(
            processId = capture.processId.toInt(),
            windowId = windowId,
            perceptualFingerprint = perceptual,
            exactReconnectDialog = exactReconnect,
            semanticScreenIdentity = semantic,
        )
    }

    /** Stable coarse client-content fingerprint; omit the bottom HUD clock and frame-level motion. */
    private fun stuckScreenPerceptualFingerprint(image: BufferedImage): String {
        var hash = 0xcbf29ce484222325UL.toLong()
        val x0 = (image.width * 0.12).toInt()
        val x1 = (image.width * 0.88).toInt()
        val y0 = (image.height * 0.08).toInt()
        val y1 = (image.height * 0.90).toInt()
        // Average broad cells and quantize coarsely so animated glows and
        // small effects do not reset the unchanged-screen timer.
        val columns = 6
        val rows = 5
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val left = (x0 + column * (x1 - x0) / columns).coerceIn(0, image.width - 1)
                val right = (x0 + (column + 1) * (x1 - x0) / columns).coerceIn(left + 1, image.width)
                val top = (y0 + row * (y1 - y0) / rows).coerceIn(0, image.height - 1)
                val bottom = (y0 + (row + 1) * (y1 - y0) / rows).coerceIn(top + 1, image.height)
                var red = 0L
                var green = 0L
                var blue = 0L
                var count = 0L
                val stepX = ((right - left) / 12).coerceAtLeast(1)
                val stepY = ((bottom - top) / 12).coerceAtLeast(1)
                for (y in top until bottom step stepY) for (x in left until right step stepX) {
                    val rgb = image.getRGB(x, y)
                    red += (rgb ushr 16) and 0xff
                    green += (rgb ushr 8) and 0xff
                    blue += rgb and 0xff
                    count++
                }
                val r = (red / count).toInt()
                val g = (green / count).toInt()
                val b = (blue / count).toInt()
                val quantized = ((r / 64) shl 4) or ((g / 64) shl 2) or (b / 64)
                hash = (hash xor quantized.toLong()) * 0x100000001b3L
            }
        }
        return java.lang.Long.toUnsignedString(hash, 16)
    }

    private fun isExactEventQuestHubChooser(
        captureTrusted: Boolean,
        visual: VisualSignature,
        image: BufferedImage,
    ): Boolean {
        val screen = detect("", visual, ScreenStateRecovery.hasCenteredModalPanel(image), image)
        return captureTrusted && !visual.startupQuestOverlayVisual &&
            screen?.kind == ScreenKind.TOURNAMENT && screen.mode == ModeEnum.HUB &&
            screen.confidence >= 90 && screen.evidence.startsWith("hub-mode-chooser-visual") &&
            visual.hubModeChooserVisual
    }

    internal fun exactEventQuestHubChooserForTest(image: BufferedImage): Boolean {
        val visual = visualSignature(image)
        return isExactEventQuestHubChooser(captureTrusted = true, visual = visual, image = image)
    }

    private fun isSafeIdleUnblockScreen(detection: Detection?, visual: VisualSignature): Boolean =
        SafeIdleUnblockScreenPolicy.matches(
            screenKind = detection?.kind?.code,
            screenMode = detection?.mode?.name,
            hubModeChooserVisual = visual.hubModeChooserVisual,
            eventQuestOverlayVisual = detection?.kind == ScreenKind.EVENT_QUEST_OVERLAY,
            tournamentModeSelectorVisual = visual.tournamentModeSelectorVisual &&
                detection?.evidence == "three-choice-mode-selector-structural-visual",
        )

    private fun isConfirmedWrongModeDeck(
        detection: Detection?,
        image: BufferedImage,
        expectedMode: String?,
        observedMode: String? = if (detection?.kind == ScreenKind.DECK_SELECTION) {
            TournamentModeConfirmation.observeImage(image).observedMode.name
        } else null,
    ): Boolean = detection?.kind == ScreenKind.DECK_SELECTION &&
        ScreenWatchdog.hasEnabledDeckSelectionStartButton(image) &&
        expectedMode in setOf("STANDARD", "WILD") && observedMode in setOf("STANDARD", "WILD") &&
        observedMode != expectedMode

    private fun shouldAttemptReconnect(now: Long): Boolean {
        // Exact RECONNECT is observed during a bounded grace period, then
        // handled by the client restart policy. Never click dialog controls.
        return false
    }

    private fun startEventQuestOverlayRecovery(
        authorizedCapture: AuthorizedCapture,
        stuckForMs: Long,
        stateStillCurrent: () -> Boolean,
    ): Boolean {
        val capture = authorizedCapture.capture
        val pid = GameUtil.findGameProcessIdForDiagnostics() ?: return false
        val hwnd = authorizedCapture.gameWindow
        val exactOverlay = capture.visual.startupQuestOverlayVisual
        val emptySessionLoginProof = eventQuestFreshEmptySessionLoginProof(
            capture = capture,
            authorizedCapture = authorizedCapture,
            pid = pid,
            hwnd = hwnd,
        )
        val safeContext = eventQuestSafeContext(
            matchmakingSearchVisual = capture.visual.matchmakingSearchVisual,
            reconnectDialogVisual = capture.visual.reconnectDialogVisual,
            freshEmptySessionLoginProof = emptySessionLoginProof,
        )
        if (!exactOverlay || !safeContext ||
            !GameUtil.isVerifiedCurrentGameWindow(hwnd, pid)
        ) {
            log.warn {
                "EVENT_QUEST_RECOVERY_BLOCKED stage=admission exactVisual=$exactOverlay " +
                    "safeContext=$safeContext mode=${Mode.currMode?.name} phase=${WarEx.war.currentPhase?.name} " +
                    "verifiedTerminalOutcome=${eventQuestPostGameTerminalProof()} " +
                    "emptySessionLoginProof=$emptySessionLoginProof emptyCurrentPowerLog=${ScreenStateRecovery.hasVerifiedEmptyCurrentSessionPowerLog()} " +
                    "pause=${PauseStatus.isPause} pid=$pid hwnd=$hwnd action=NO_CLICK_NO_RESTART"
            }
            return false
        }
        if (!StartupQuestOverlayRecoveryFence.isActive()) {
            eventQuestOverlayRecoveryPolicy.reset()
            StartupQuestOverlayRecoveryFence.begin()
            StartupQuestOverlayRecoveryFence.observeSession(pid, hwnd.toString())
        }
        log.warn {
            "EVENT_QUEST_RECOVERY_STARTED stuckForMs=$stuckForMs pid=$pid hwnd=$hwnd " +
                "mode=${Mode.currMode?.name} phase=${WarEx.war.currentPhase?.name} " +
                "evidence=event-quest-overlay-structural-visual " +
                "clicksMax=${EventQuestOverlayRecoveryPolicy.MAX_CLICKS} " +
                "secondStageMs=${EventQuestOverlayRecoveryPolicy.SECOND_STAGE_DELAY_MS} " +
                "emptySessionLoginProof=$emptySessionLoginProof ocr=skipped"
        }
        val recoveryStartedAtMs = System.currentTimeMillis() - stuckForMs.coerceAtLeast(0L)
        scheduleEventQuestOverlayRecovery(pid, hwnd, stuckForMs, stateStillCurrent, recoveryStartedAtMs, 0L)
        return true
    }

    /** Shared live callsite routing seam; the callback is invoked only for the exact classified screen kind. */
    internal fun dispatchEventQuestRecovery(screenKind: String?, dispatch: () -> Boolean): Boolean {
        if (screenKind != ScreenKind.EVENT_QUEST_OVERLAY.code) return false
        return dispatch()
    }

    internal fun resolveEventQuestSafeTransition(action: EventQuestOverlayRecoveryPolicy.Action): Boolean {
        if (action != EventQuestOverlayRecoveryPolicy.Action.UNBLOCK_SAFE_TRANSITION) return false
        StartupQuestOverlayRecoveryFence.resolve()
        return true
    }

    private fun eventQuestSafeContext(
        matchmakingSearchVisual: Boolean = false,
        reconnectDialogVisual: Boolean = false,
        freshEmptySessionLoginProof: Boolean = false,
    ): Boolean {
        val activeGame = WarEx.inWar || Mode.currMode === ModeEnum.GAMEPLAY || Mode.nextMode === ModeEnum.GAMEPLAY
        val terminalOrResult = GameUtil.isTerminalGameState() ||
            MandatoryRankSurrenderGuard.isPending() || MandatoryRankSurrenderGuard.isTerminalCleanupPending()
        val postGameTerminalProof = eventQuestPostGameTerminalProof()
        return EventQuestOverlayRecoveryContextPolicy.allowsDismiss(
            mode = Mode.currMode?.name,
            phase = WarEx.war.currentPhase?.name,
            currentSessionReady = ScreenStateRecovery.isCurrentGameSessionReadyForStart(),
            nextModeGameplay = Mode.nextMode === ModeEnum.GAMEPLAY,
            activeGame = activeGame,
            activeQueueScreen = LifecycleTrace.hasRecentActiveMatchmakingScreen(),
            recentInputIntent = LifecycleTrace.hasRecentMatchmakingInputIntent(),
            shortRaceWindow = LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard(),
            matchmakingSearchVisual = matchmakingSearchVisual,
            terminalOrResult = terminalOrResult,
            working = WorkTimeListener.working,
            paused = PauseStatus.isPause,
            postGameTerminalProof = postGameTerminalProof,
            freshEmptySessionLoginProof = freshEmptySessionLoginProof,
            reconnectDialogVisual = reconnectDialogVisual,
        )
    }

    private fun eventQuestFreshEmptySessionLoginProof(
        capture: Capture,
        authorizedCapture: AuthorizedCapture,
        pid: Long,
        hwnd: WinDef.HWND,
    ): Boolean {
        val currentSessionReady = ScreenStateRecovery.isCurrentGameSessionReadyForStart()
        val activeQueueScreen = LifecycleTrace.hasRecentActiveMatchmakingScreen()
        val recentInputIntent = LifecycleTrace.hasRecentMatchmakingInputIntent()
        val shortRaceWindow = LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard()
        val mandatoryRankGuard = MandatoryRankSurrenderGuard.isPending() ||
            MandatoryRankSurrenderGuard.isTerminalCleanupPending()
        val exactForegroundCapture = authorizedCapture.foregroundVerified &&
            capture.processId == pid.toLong() && capture.windowId == hwnd.toString() &&
            GameUtil.findGameProcessIdForDiagnostics() == pid && GameUtil.isVerifiedCurrentGameWindow(hwnd, pid)
        return BuildChannel.identityToken(BuildInfo.RELEASE_CHANNEL) == "beta" &&
            EventQuestOverlayRecoveryContextPolicy.allowsFreshEmptySessionLoginProof(
                mode = Mode.currMode?.name,
                phase = WarEx.war.currentPhase?.name,
                exactForegroundCapture = exactForegroundCapture,
                emptyCurrentSessionPowerLog = ScreenStateRecovery.hasVerifiedEmptyCurrentSessionPowerLog(),
                currentSessionReady = currentSessionReady,
                nextModeGameplay = Mode.nextMode === ModeEnum.GAMEPLAY,
                activeGame = WarEx.inWar || Mode.currMode === ModeEnum.GAMEPLAY,
                activeQueueScreen = activeQueueScreen,
                recentInputIntent = recentInputIntent,
                shortRaceWindow = shortRaceWindow,
                matchmakingSearchVisual = capture.visual.matchmakingSearchVisual,
                reconnectDialogVisual = capture.visual.reconnectDialogVisual,
                mandatoryRankGuard = mandatoryRankGuard,
                working = WorkTimeListener.working,
                paused = PauseStatus.isPause,
            )
    }

    /** Stale FILL_DECK/final-step state can authorize only this post-game overlay cleanup. */
    private fun eventQuestPostGameTerminalProof(): Boolean =
        BuildChannel.identityToken(BuildInfo.RELEASE_CHANNEL) == "beta" &&
            PowerLogListener.hasVerifiedCurrentGameTerminalOutcome() &&
            !MandatoryRankSurrenderGuard.isPending() && !MandatoryRankSurrenderGuard.isTerminalCleanupPending()

    private fun scheduleEventQuestOverlayRecovery(
        expectedPid: Long,
        expectedWindow: WinDef.HWND,
        stuckForMs: Long,
        stateStillCurrent: () -> Boolean,
        recoveryStartedAtMs: Long,
        delayMs: Long,
    ) {
        if (!eventQuestOverlayRecoveryInFlight.compareAndSet(false, true)) return
        EXTRA_THREAD_POOL.schedule({
            var retryAfterMs: Long? = null
            try {
                val livePid = GameUtil.findGameProcessIdForDiagnostics()
                val liveWindow = ScriptStatus.gameHWND
                if (livePid != expectedPid || liveWindow?.toString() != expectedWindow.toString() ||
                    !GameUtil.isVerifiedCurrentGameWindow(expectedWindow, expectedPid) || !stateStillCurrent()
                ) {
                    StartupQuestOverlayRecoveryFence.resolve()
                    eventQuestOverlayRecoveryPolicy.reset()
                    log.warn {
                        "EVENT_QUEST_RECOVERY_UNBLOCKED reason=client-or-state-changed expectedPid=$expectedPid " +
                            "livePid=${livePid ?: "none"} expectedHwnd=$expectedWindow " +
                            "liveHwnd=${liveWindow ?: "none"} action=FENCE_RELEASED"
                    }
                    return@schedule
                }
                // This callback is a fresh recheck of an already admitted, exact
                // Event Quest overlay. Keep capture available while the new
                // Power.log is still empty, even if a callback accidentally
                // arrives with a zero elapsed-stuck value. The startup purpose
                // is capture-only; the exact overlay and all context fences
                // below still decide whether any input or restart is allowed.
                log.info {
                    "EVENT_QUEST_RECOVERY_CAPTURE_REQUESTED pid=$expectedPid hwnd=$expectedWindow " +
                        "stuckForMs=$stuckForMs startupProbe=true currentSessionReady=" +
                        "${ScreenStateRecovery.isCurrentGameSessionReadyForStart()} " +
                        "activeQueueScreen=${LifecycleTrace.hasRecentActiveMatchmakingScreen()} " +
                        "recentInputIntent=${LifecycleTrace.hasRecentMatchmakingInputIntent()} " +
                        "shortRaceWindow=${LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard()} " +
                        "mode=${Mode.currMode?.name ?: "UNKNOWN"} " +
                        "phase=${WarEx.war.currentPhase?.name ?: "UNKNOWN"} " +
                        "paused=${PauseStatus.isPause} working=${WorkTimeListener.working}"
                }
                val authorized = captureVerifiedGameScreen(
                    stuckForMs = stuckForMs,
                    startupProbe = true,
                    captureContext = "EVENT_QUEST_RETRY",
                )
                if (authorized == null || authorized.gameWindow.toString() != expectedWindow.toString()) {
                    val captureFailureReason = if (authorized == null) {
                        "capture-unavailable"
                    } else {
                        "captured-window-mismatch"
                    }
                    log.warn {
                        "EVENT_QUEST_RECOVERY_PROBE_BLOCKED reason=$captureFailureReason " +
                            "pid=$expectedPid hwnd=$expectedWindow " +
                            "capturedHwnd=${authorized?.gameWindow ?: "none"} " +
                            "capturePurpose=${authorized?.purpose?.name ?: "none"} " +
                            "captureContext=EVENT_QUEST_RETRY " +
                            "action=NO_CLICK_NO_RESTART"
                    }
                    retryAfterMs = 10_000L
                    return@schedule
                }

                val capture = authorized.capture
                log.info {
                    "EVENT_QUEST_RECOVERY_CAPTURE_AUTHORIZED pid=$expectedPid hwnd=$expectedWindow " +
                        "capturePid=${capture.processId} captureHwnd=${capture.windowId} " +
                        "purpose=${authorized.purpose.name} foregroundVerified=${authorized.foregroundVerified} " +
                        "questOverlay=${capture.visual.startupQuestOverlayVisual} " +
                        "matchmakingSearch=${capture.visual.matchmakingSearchVisual} " +
                        "reconnect=${capture.visual.reconnectDialogVisual} " +
                        "screenshot=${capture.file?.absolutePath ?: "none"}"
                }
                val captureTrusted = GameUtil.findGameProcessIdForDiagnostics() == expectedPid &&
                    ScriptStatus.gameHWND?.toString() == expectedWindow.toString() &&
                    capture.processId == expectedPid.toLong() && capture.windowId == expectedWindow.toString() &&
                    authorized.foregroundVerified &&
                    GameUtil.isVerifiedCurrentGameWindow(expectedWindow, expectedPid)
                val exactOverlay = captureTrusted && capture.visual.startupQuestOverlayVisual
                val currentSessionReady = ScreenStateRecovery.isCurrentGameSessionReadyForStart()
                val activeQueue = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
                    LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard() || capture.visual.matchmakingSearchVisual
                val activeGame = WarEx.inWar || Mode.currMode === ModeEnum.GAMEPLAY || Mode.nextMode === ModeEnum.GAMEPLAY
                val terminalOrResult = GameUtil.isTerminalGameState() ||
                    club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard.isPending() ||
                    club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard.isTerminalCleanupPending()
                val postGameTerminalProof = eventQuestPostGameTerminalProof()
                val freshEmptySessionLoginProof = eventQuestFreshEmptySessionLoginProof(
                    capture = capture,
                    authorizedCapture = authorized,
                    pid = expectedPid,
                    hwnd = expectedWindow,
                )
                val reconnectDialogVisual = capture.visual.reconnectDialogVisual
                val freshScreenDetection = detect(
                    ocrText = "",
                    visual = capture.visual,
                    modalVisible = ScreenStateRecovery.hasCenteredModalPanel(capture.image),
                    image = capture.image,
                )
                val exactHubModeChooser = isExactEventQuestHubChooser(captureTrusted, capture.visual, capture.image)
                val emptySessionLoginTransition =
                    EventQuestOverlayRecoveryContextPolicy.allowsFreshEmptySessionLoginTransition(
                        questOverlayVisible = exactOverlay,
                        centeredModalVisible = ScreenStateRecovery.hasCenteredModalPanel(capture.image),
                        freshEmptySessionLoginProof = freshEmptySessionLoginProof,
                    )
                val deckReadyVisible = !capture.visual.startupQuestOverlayVisual &&
                    Mode.currMode === ModeEnum.TOURNAMENT && WarEx.war.currentPhase == WarPhaseEnum.FILL_DECK &&
                    club.xiaojiawei.hsscript.status.ScreenWatchdog.hasEnabledDeckSelectionStartButton(capture.image)
                val verifiedSafeTransition = EventQuestOverlayFenceReleasePolicy.allows(
                    captureTrusted = captureTrusted,
                    questOverlayVisible = capture.visual.startupQuestOverlayVisual,
                    exactHubModeChooser = exactHubModeChooser,
                    deckReadyVisible = deckReadyVisible,
                    freshEmptySessionLoginTransition = emptySessionLoginTransition,
                    activeGame = activeGame,
                    activeQueue = activeQueue,
                    reconnectDialog = reconnectDialogVisual,
                    terminalOrResult = terminalOrResult,
                    mandatoryRankGuard = MandatoryRankSurrenderGuard.isPending() ||
                        MandatoryRankSurrenderGuard.isTerminalCleanupPending(),
                    working = WorkTimeListener.working,
                    paused = PauseStatus.isPause,
                )
                val decision = eventQuestOverlayRecoveryPolicy.observe(
                    EventQuestOverlayRecoveryPolicy.Evidence(
                        nowMs = System.currentTimeMillis(), captureTrusted = captureTrusted,
                        exactQuestOverlay = exactOverlay,
                        safeMenuOrDeckState = eventQuestSafeContext(
                            matchmakingSearchVisual = capture.visual.matchmakingSearchVisual,
                            reconnectDialogVisual = reconnectDialogVisual,
                            freshEmptySessionLoginProof = freshEmptySessionLoginProof,
                        ),
                        verifiedSafeTransition = verifiedSafeTransition,
                        working = WorkTimeListener.working, paused = PauseStatus.isPause,
                        activeGame = activeGame, activeQueue = activeQueue,
                        terminalOrResult = terminalOrResult,
                        postGameTerminalProof = postGameTerminalProof,
                        freshEmptySessionLoginProof = freshEmptySessionLoginProof,
                        stableForMs = (System.currentTimeMillis() - recoveryStartedAtMs).coerceAtLeast(stuckForMs),
                        automationRunId = WorkTimeListener.recoveryRunId(),
                        semanticScreenIdentity = "EVENT_QUEST_OVERLAY|mode=${Mode.currMode?.name ?: "UNKNOWN"}" +
                            "|phase=${WarEx.war.currentPhase?.name ?: "UNKNOWN"}",
                    ),
                )
                StartupQuestOverlayRecoveryFence.recordObservation()
                log.warn {
                    "EVENT_QUEST_RECOVERY_DECISION action=${decision.action} reason=${decision.reason} " +
                        "click=${decision.clickNumber}/${EventQuestOverlayRecoveryPolicy.MAX_CLICKS} " +
                        "stuckForMs=$stuckForMs currentSessionReady=$currentSessionReady " +
                        "activeGame=$activeGame activeQueue=$activeQueue terminalOrResult=$terminalOrResult " +
                        "postGameTerminalProof=$postGameTerminalProof " +
                        "freshEmptySessionLoginProof=$freshEmptySessionLoginProof " +
                        "paused=${PauseStatus.isPause} exactOverlay=$exactOverlay deckReadyVisible=$deckReadyVisible " +
                        "hubModeChooser=$exactHubModeChooser hubEvidence=${freshScreenDetection?.evidence ?: "none"} " +
                        "safeTransition=$verifiedSafeTransition " +
                        "pid=$expectedPid hwnd=$expectedWindow screenshot=${capture.file?.absolutePath ?: "none"}"
                }
                when (decision.action) {
                    EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT -> {
                        val point = StartupQuestOverlayDismissPolicy.topLeftPixel(capture.bounds)
                        val permit = point?.let {
                            StartupQuestOverlayDismissAuthorization.permits.issue(
                                expectedPid, expectedWindow.toString(), it, System.currentTimeMillis(),
                            )
                        }
                        val foregroundVerified = authorized.foregroundVerified
                        val dispatchContextSafe = eventQuestSafeContext(
                            matchmakingSearchVisual = capture.visual.matchmakingSearchVisual,
                            reconnectDialogVisual = reconnectDialogVisual,
                            freshEmptySessionLoginProof = freshEmptySessionLoginProof,
                        )
                        val accepted = if (point == null || permit == null) false else runCatching {
                            EventQuestOverlayClickDispatch.dispatch(
                                action = decision.action,
                                freshCaptureTrusted = captureTrusted && exactOverlay,
                                foregroundVerified = foregroundVerified,
                                safeContext = dispatchContextSafe,
                            ) {
                                MouseUtil.leftButtonClickForStartupQuestOverlayRecovery(
                                    point, capture.bounds, expectedPid, expectedWindow, permit,
                                    freshEmptySessionLoginProof = freshEmptySessionLoginProof,
                                    matchmakingSearchVisual = capture.visual.matchmakingSearchVisual,
                                    reconnectDialogVisual = reconnectDialogVisual,
                                )
                            }
                        }.onFailure { error ->
                            log.warn(error) { "EVENT_QUEST_RECOVERY_CLICK_FAILED error=${error.javaClass.simpleName}" }
                        }.getOrDefault(false)
                        StartupQuestOverlayRecoveryFence.recordTapAttempt()
                        log.warn {
                            "EVENT_QUEST_RECOVERY_TOP_LEFT_CLICK click=${decision.clickNumber}/" +
                                "${EventQuestOverlayRecoveryPolicy.MAX_CLICKS} point=$point accepted=$accepted " +
                                "freshCaptureTrusted=${captureTrusted && exactOverlay} foregroundVerified=$foregroundVerified " +
                                "safeContext=$dispatchContextSafe postGameTerminalProof=$postGameTerminalProof " +
                                "freshEmptySessionLoginProof=$freshEmptySessionLoginProof " +
                                "pid=$expectedPid hwnd=$expectedWindow action=${StartupQuestOverlayDismissPolicy.DISPATCH_ACTION}"
                        }
                        retryAfterMs = 500L
                    }
                    EventQuestOverlayRecoveryPolicy.Action.WAIT_AND_REPROBE,
                    EventQuestOverlayRecoveryPolicy.Action.DEFER,
                    -> retryAfterMs = 10_000L
                    EventQuestOverlayRecoveryPolicy.Action.UNBLOCK_SAFE_TRANSITION -> {
                        resolveEventQuestSafeTransition(decision.action)
                        log.warn {
                            "EVENT_QUEST_RECOVERY_UNBLOCKED reason=${decision.reason} " +
                                "clicks=${eventQuestOverlayRecoveryPolicy.clicksAttempted()} " +
                                "deckReadyVisible=$deckReadyVisible activeGame=$activeGame activeQueue=$activeQueue " +
                                "terminalOrResult=$terminalOrResult action=FENCE_RELEASED"
                        }
                    }
                    EventQuestOverlayRecoveryPolicy.Action.RESTART_CLIENT -> {
                        val stillSafe = captureTrusted && exactOverlay &&
                            eventQuestSafeContext(
                                matchmakingSearchVisual = capture.visual.matchmakingSearchVisual,
                                reconnectDialogVisual = reconnectDialogVisual,
                                freshEmptySessionLoginProof = freshEmptySessionLoginProof,
                            ) &&
                            GameUtil.findGameProcessIdForDiagnostics() == expectedPid &&
                            ScriptStatus.gameHWND?.toString() == expectedWindow.toString() &&
                            GameUtil.isVerifiedCurrentGameWindow(expectedWindow, expectedPid) && stateStillCurrent()
                        log.error {
                            "EVENT_QUEST_RECOVERY_SECOND_STAGE action=${if (stillSafe) "RESTART_CLIENT" else "DEFER"} " +
                                "reason=${decision.reason} thresholdMs=${EventQuestOverlayRecoveryPolicy.SECOND_STAGE_DELAY_MS} " +
                                "clicks=${decision.clickNumber} finalGuard=$stillSafe pid=$expectedPid hwnd=$expectedWindow " +
                                "pause=${PauseStatus.isPause} activeGame=$activeGame activeQueue=$activeQueue " +
                                "terminalOrResult=$terminalOrResult " +
                                "freshEmptySessionLoginProof=$freshEmptySessionLoginProof"
                        }
                        if (stillSafe) {
                            eventQuestOverlayRecoveryPolicy.confirmRestartIssued()
                            Core.restartFromIdleWatchdog()
                        } else retryAfterMs = 10_000L
                    }
                    EventQuestOverlayRecoveryPolicy.Action.STOP -> {
                        if (decision.reason == "client-restart-cap-exhausted") {
                            log.error {
                                "EVENT_QUEST_RECOVERY_ALERT reason=client-restart-cap-exhausted " +
                                    "automationRunId=${WorkTimeListener.recoveryRunId()} " +
                                    "semanticScreen=EVENT_QUEST_OVERLAY|mode=${Mode.currMode?.name}|phase=${WarEx.war.currentPhase?.name} " +
                                    "action=NO_FURTHER_CLIENT_RESTARTS activeGame=$activeGame activeQueue=$activeQueue " +
                                    "terminalOrResult=$terminalOrResult postGameTerminalProof=$postGameTerminalProof"
                            }
                        } else if (exactOverlay || PauseStatus.isPause || !captureTrusted) {
                            retryAfterMs = 10_000L
                        }
                    }
                }
            } catch (error: Throwable) {
                log.warn(error) {
                    "EVENT_QUEST_RECOVERY_PROBE_FAILED pid=$expectedPid hwnd=$expectedWindow " +
                        "error=${error.javaClass.simpleName} action=NO_UNGUARDED_INPUT"
                }
                retryAfterMs = 10_000L
            } finally {
                eventQuestOverlayRecoveryInFlight.set(false)
                retryAfterMs?.let {
                    scheduleEventQuestOverlayRecovery(
                        expectedPid, expectedWindow, stuckForMs, stateStillCurrent, recoveryStartedAtMs, it,
                    )
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun apply(detection: Detection): Boolean {
        val exactReconnectRecoveryEvidence = detection.kind == ScreenKind.RECONNECT ||
            detection.kind == ScreenKind.LOADING && detection.evidence == "reconnect-slow-loading-text"
        if (WarEx.inWar && !exactReconnectRecoveryEvidence) {
            log.warn { "SCREEN_RECOVERY_SKIPPED reason=war-started detected=${detection.kind.code}" }
            return false
        }
        if (detection.kind != ScreenKind.LOADING) {
            slowReconnectWarningObservedAt.set(0L)
        }

        when (detection.kind) {
            ScreenKind.AMAZON_APPSTORE_NOTICE -> {
                log.warn {
                    "SCREEN_RECOVERY_AMAZON_NOTICE_UNAUTHORIZED action=NO_INPUT " +
                        "reason=not-fresh-safe-startup-probe"
                }
                return false
            }
            ScreenKind.SERVER_UNAVAILABLE -> {
                log.warn { "SCREEN_RECOVERY_CLASSIFIED screen=SERVER_UNAVAILABLE action=NO_INPUT_NO_RESTART" }
            }
            ScreenKind.DECK_SELECTION -> {
                val terminalGuard =
                    club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
                if (!terminalGuard.isPending() && terminalGuard.isTerminalCleanupPending()) {
                    if (!terminalGuard.confirmAtFreshDeckSelection(
                            screenKind = detection.kind.code,
                            confidence = detection.confidence,
                            visualEvidence = detection.evidence,
                            freshObservation = true,
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
                val surrenderCompletion = completeMandatoryRankSurrenderAtDeckSelection(
                    screenKind = detection.kind.code,
                    confidence = detection.confidence,
                    visualEvidence = detection.evidence,
                    // apply() is reached only after the current capture and
                    // state fingerprint have been revalidated.
                    freshObservation = true,
                )
                log.info {
                    "SCREEN_RECOVERY_TERMINAL_CLEANUP_RECONCILIATION result=${surrenderCompletion.name} " +
                        "screen=${detection.kind.code} confidence=${detection.confidence} " +
                        "visualEvidence=${detection.evidence} freshObservation=true"
                }
                if (surrenderCompletion ==
                    club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderDeckSelectionRecovery.Result.BLOCKED
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_DECK_SELECTION_BLOCKED reason=rank-surrender-terminal-proof-missing " +
                            "confidence=${detection.confidence} evidence=${detection.evidence} " +
                            "terminalCleanupAuthorized=${club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard.hasTerminalCleanupCapability()} " +
                            "dispatch=false"
                    }
                    return false
                }
                if (surrenderCompletion ==
                    club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderDeckSelectionRecovery.Result.COMPLETED
                ) {
                    log.warn {
                        "MANDATORY_RANK_SURRENDER_COMPLETED evidence=fresh-deck-selection " +
                            "confidence=${detection.confidence} visualEvidence=${detection.evidence} " +
                            "queueMayResume=true"
                    }
                }
                if (!terminalGuard
                        .confirmAtFreshDeckSelection(
                            screenKind = detection.kind.code,
                            confidence = detection.confidence,
                            visualEvidence = detection.evidence,
                            freshObservation = true,
                        )
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_DECK_SELECTION_BLOCKED reason=terminal-cleanup-proof-or-freshness " +
                            "confidence=${detection.confidence} evidence=${detection.evidence} " +
                            "dispatch=false"
                    }
                    return false
                }
                if (!club.xiaojiawei.hsscript.listener.log.PowerLogListener.confirmCurrentGameTerminalDestination(
                        screenKind = detection.kind.code,
                        confidence = detection.confidence,
                        freshCaptureAuthorized = true,
                    )
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_DECK_SELECTION_BLOCKED reason=current-game-terminal-input-fence " +
                            "confidence=${detection.confidence} dispatch=false"
                    }
                    return false
                }
                if (DeckStrategyManager.currentDeckStrategy == null ||
                    DeckStrategyManager.currentRunMode == null
                ) {
                    log.warn { "SCREEN_RECOVERY_DECK_SELECTION_SKIPPED reason=no-selected-strategy" }
                    return false
                }
                if (!ActionDispatchGate.isFreshConfiguredDeckSelectionStartProofReady()) {
                    log.warn {
                        "SCREEN_RECOVERY_DECK_SELECTION_BLOCKED reason=mode-specific-startup-proof-missing " +
                            "expectedMode=${DeckStrategyManager.currentRunMode?.name ?: "UNKNOWN"} " +
                            "observedMode=UNKNOWN action=NO_START_REPROBE_MODE_SELECTOR"
                    }
                    return false
                }
                Mode.recover(ModeEnum.TOURNAMENT, "visible-deck-selection", enterStrategy = false)
                log.warn {
                    "SCREEN_RECOVERY_APPLIED screen=DECK_SELECTION next=START_MATCHING " +
                        "deck=${DeckStrategyManager.currentDeckStrategy?.name()}"
                }
                EXTRA_THREAD_POOL.schedule({
                    if (WorkTimeListener.working && !PauseStatus.isPause && !WarEx.inWar) {
                        TournamentModeStrategy.startMatching()
                    }
                }, 300, TimeUnit.MILLISECONDS)
            }

            ScreenKind.RESULT -> {
                Mode.recover(ModeEnum.GAMEPLAY, "visible-result-screen", enterStrategy = false)
                log.warn { "SCREEN_RECOVERY_APPLIED screen=RESULT next=DISMISS_STALE_RESULT" }
                val cleanupCapability = club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
                    .existingTerminalCleanupCapability()
                GameUtil.dismissStaleGameEndScreen(
                    resultAlreadyObserved = true,
                    terminalCleanupCapability = cleanupCapability,
                )
            }

            ScreenKind.RANK_PROGRESS_CONTINUATION -> {
                val cleanupCapability =
                    club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
                        .existingTerminalCleanupCapability()
                if (club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
                        .isTerminalCleanupCapabilityValid(cleanupCapability)
                ) {
                    Mode.recover(ModeEnum.GAMEPLAY, "visible-post-result-rank-progress", enterStrategy = false)
                    log.info {
                        "SCREEN_RECOVERY_APPLIED screen=RANK_PROGRESS_CONTINUATION " +
                            "action=CONTINUE_WITH_TERMINAL_CAPABILITY"
                    }
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
                if (!confirmPendingTerminalCleanupAtMatchmaking(
                        screenKind = detection.kind.code,
                        confidence = detection.confidence,
                        freshCaptureAuthorized = true,
                    )
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_MATCHMAKING_BLOCKED reason=terminal-cleanup-capability-invalid " +
                            "dispatch=false"
                    }
                    return false
                }
                if (!MatchmakingQueueLifecycleEvidencePolicy.propagateClassifiedSearchScreenIfAuthorized(
                        screenKind = detection.kind.code,
                        captureAuthorized = true,
                        observedAt = System.currentTimeMillis(),
                    )
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_MATCHMAKING_BLOCKED reason=screen-evidence-not-authorized " +
                            "screen=${detection.kind.code} captureAuthorized=true dispatch=false"
                    }
                    return false
                }
                Mode.recover(ModeEnum.TOURNAMENT, "visible-matchmaking-screen", enterStrategy = false)
                log.info {
                    "SCREEN_RECOVERY_APPLIED screen=MATCHMAKING action=WAIT_FOR_GAMEPLAY " +
                        "evidence=${detection.evidence} queuePreserved=true"
                }
            }

            ScreenKind.RECONNECT -> {
                // The exact current reconnect capture supersedes a prior queue
                // screen observation. Preserve independent active-game and
                // terminal checks, but do not let the historical queue latch
                // defer the bounded no-pause restart.
                val staleQueueEvidenceCleared = LifecycleTrace.supersedeActiveMatchmakingScreenForReconnect()
                if (staleQueueEvidenceCleared) {
                    log.info {
                        "SCREEN_RECOVERY_RECONNECT_QUEUE_HISTORY_CLEARED reason=exact-current-reconnect " +
                            "staleIntent=${LifecycleTrace.hasRecentMatchmakingInputIntent()} " +
                            "action=CONTINUE_RECONNECT_AGEING"
                    }
                }
                val currentSessionReady = ScreenStateRecovery.isCurrentGameSessionReadyForStart()
                val freshActiveQueue = LifecycleTrace.hasRecentActiveMatchmakingScreen()
                val activeGameEvidence = ScreenStateRecovery.hasAuthoritativeCurrentGameEvidenceForReconnect()
                val activeQueue = freshActiveQueue || currentSessionReady && WarEx.inWar
                val terminalState = GameUtil.isTerminalGameState()
                if (ReconnectRestartEvidencePolicy.shouldDefer(
                        exactReconnectVisible = true,
                        freshActiveQueue = freshActiveQueue,
                        currentSessionReady = currentSessionReady,
                        activeGame = WarEx.inWar,
                        terminalState = terminalState,
                    )
                ) {
                    log.warn {
                        "SCREEN_RECOVERY_RECONNECT_DEFERRED reason=${when { terminalState -> "terminal"; activeQueue -> "active-matchmaking"; else -> "current-session-gameplay" }} " +
                            "action=NO_INPUT_NO_RESTART activeGameEvidence=$activeGameEvidence " +
                            "staleQueueEvidenceCleared=$staleQueueEvidenceCleared " +
                            "staleMatchmakingIntentIgnored=${LifecycleTrace.hasRecentMatchmakingInputIntent()} " +
                            "staleWarLatch=${WarEx.inWar}"
                    }
                    return false
                }
                val now = System.currentTimeMillis()
                val reconnectDecision = indefiniteReconnectRestartPolicy.observe(
                    exactReconnectVisible = true,
                    nowMs = now,
                    activeQueue = activeQueue,
                    terminalState = terminalState,
                )
                when (reconnectDecision) {
                    IndefiniteReconnectRestartPolicy.Decision.RESTART_CLIENT_NO_PAUSE -> {
                        log.error {
                            "SCREEN_RECOVERY_RECONNECT_INDEFINITE decision=RESTART_CLIENT_NO_PAUSE " +
                                "thresholdMs=${IndefiniteReconnectRestartPolicy.DEFAULT_THRESHOLD_MS} " +
                                "attempt=${indefiniteReconnectRestartPolicy.attempts()} " +
                                "activeGameEvidence=$activeGameEvidence staleWarLatch=${WarEx.inWar} " +
                                "action=SHUTDOWN_HEARTHSTONE_AND_RESTART"
                        }
                        return restartClientForIndefiniteReconnect(now)
                    }
                    IndefiniteReconnectRestartPolicy.Decision.ALERT_OPERATOR -> {
                        log.error {
                            "SCREEN_RECOVERY_RECONNECT_RESTART_EXHAUSTED attempts=" +
                                "${indefiniteReconnectRestartPolicy.attempts()} action=NO_PAUSE_NO_INPUT"
                        }
                        SystemUtil.notice(
                            "Hearthstone remained on the reconnect screen after bounded client restarts. " +
                                "Automation was not paused; inspect the client and click Start to retry.",
                        )
                        return false
                    }
                    IndefiniteReconnectRestartPolicy.Decision.DEFER_ACTIVE_QUEUE,
                    IndefiniteReconnectRestartPolicy.Decision.DEFER_TERMINAL -> return false
                    IndefiniteReconnectRestartPolicy.Decision.WAIT -> Unit
                }
                if (reconnectDecision == IndefiniteReconnectRestartPolicy.Decision.WAIT) {
                    scheduleExactReconnectThresholdRecheck(now)
                }
                if (WarEx.inWar) {
                    log.warn {
                        "SCREEN_RECOVERY_RECONNECT_INDEFINITE_WAIT activeGame=true action=NO_CLICK " +
                            "elapsedThresholdMs=${IndefiniteReconnectRestartPolicy.DEFAULT_THRESHOLD_MS}"
                    }
                    return false
                }
                Mode.recover(ModeEnum.LOGIN, "visible-reconnect-screen", enterStrategy = false)
                if (!reconnectClickConsumed.get() && shouldAttemptReconnect(now)) {
                    log.warn {
                        "SCREEN_RECOVERY_APPLIED screen=RECONNECT mode=LOGIN " +
                            "action=ONE_SHOT_RECONNECT_CLICK timeoutRestartMs=" +
                            "${IndefiniteReconnectRestartPolicy.DEFAULT_THRESHOLD_MS}"
                    }
                    EXTRA_THREAD_POOL.schedule({
                        if (WorkTimeListener.working && !PauseStatus.isPause &&
                            !WarEx.inWar && !GameUtil.isTerminalGameState() &&
                            !LifecycleTrace.hasRecentActiveMatchmakingScreen() &&
                            !LifecycleTrace.hasRecentMatchmakingInputIntent() &&
                            Mode.currMode == ModeEnum.LOGIN
                        ) {
                            // This is the upstream reconnect input primitive;
                            // skip the pre-click right-click because the
                            // offline page is not a card/targeting state.
                            val point = GameUtil.RECONNECT_RECT.getCenterClickPos()
                            val preSession = !ScreenStateRecovery.isCurrentGameSessionReadyForStart()
                            val gameWindow = ScriptStatus.gameHWND
                            val pid = GameUtil.findGameProcessIdForDiagnostics()?.toInt()
                            val freshModal = if (preSession && gameWindow != null && pid != null &&
                                !LifecycleTrace.hasRecentActiveMatchmakingScreen() &&
                                !LifecycleTrace.hasRecentMatchmakingInputIntent()
                            ) {
                                confirmFreshPreSessionReconnect(pid, gameWindow)
                            } else !preSession
                            val permitIssued = !preSession || freshModal && pid != null && gameWindow != null &&
                                StartupInputReadiness.issueReconnectPermit(
                                    pid,
                                    gameWindow.toString(),
                                    point,
                                    System.currentTimeMillis(),
                                )
                            val dispatchReserved = reconnectClickConsumed.compareAndSet(false, true)
                            val accepted = if (dispatchReserved && permitIssued) {
                                MouseUtil.leftButtonClickForRecovery(
                                    point,
                                    preSessionReconnect = preSession,
                                )
                            } else false
                            log.info {
                                "SCREEN_RECOVERY_RECONNECT_DISPATCHED input=recovery-sendinput " +
                                    "accepted=$accepted oneShotReserved=$dispatchReserved preSession=$preSession freshModal=$freshModal " +
                                    "permitIssued=$permitIssued"
                            }
                            if (accepted) {
                                if (preSession && pid != null && gameWindow != null) {
                                    StartupInputReadiness.consumeReconnectPermit(
                                        pid,
                                        gameWindow.toString(),
                                        point,
                                        System.currentTimeMillis(),
                                    )
                                }
                                reconnectAcceptedAt.set(System.currentTimeMillis())
                            }
                        } else {
                            log.info { "SCREEN_RECOVERY_RECONNECT_SKIPPED reason=state-changed" }
                        }
                    }, 300, TimeUnit.MILLISECONDS)
                } else {
                    log.info {
                        "SCREEN_RECOVERY_RECONNECT_WAIT_FOR_RESTART_POLICY " +
                            "clickConsumed=${reconnectClickConsumed.get()} " +
                            "restartThresholdMs=${IndefiniteReconnectRestartPolicy.DEFAULT_THRESHOLD_MS}"
                    }
                }
            }

            ScreenKind.RECONNECT_FAILURE -> {
                // Dismiss only the known recovery dialog. Do not dispatch the
                // reconnect click and do not enter any credential/login flow.
                Mode.recover(ModeEnum.LOGIN, "visible-reconnect-failure", enterStrategy = false)
                EXTRA_THREAD_POOL.schedule({
                    if (WorkTimeListener.working && !PauseStatus.isPause && !WarEx.inWar) {
                        LoginModeStrategy.RECONNECT_RECOVERY_EXIT_RECT.lClick(false)
                        log.warn { "SCREEN_RECOVERY_APPLIED screen=RECONNECT_FAILURE action=DISMISS_RECOVERY_DIALOG" }
                    } else {
                        log.info { "SCREEN_RECOVERY_RECONNECT_FAILURE_SKIPPED reason=state-changed" }
                    }
                }, 300, TimeUnit.MILLISECONDS)
            }

            ScreenKind.LOADING -> {
                val now = System.currentTimeMillis()
                if (detection.evidence == "reconnect-slow-loading-text") {
                    val currentSessionReady = ScreenStateRecovery.isCurrentGameSessionReadyForStart()
                    val freshActiveQueue = LifecycleTrace.hasRecentActiveMatchmakingScreen()
                    val activeQueue = freshActiveQueue || currentSessionReady && WarEx.inWar
                    val terminalState = GameUtil.isTerminalGameState()
                    when (indefiniteReconnectRestartPolicy.observe(true, now, activeQueue, terminalState)) {
                        IndefiniteReconnectRestartPolicy.Decision.RESTART_CLIENT_NO_PAUSE -> {
                            log.error {
                                "SCREEN_RECOVERY_RECONNECT_INDEFINITE screen=LOADING " +
                                    "action=RESTART_CLIENT_NO_PAUSE thresholdMs=" +
                                    "${IndefiniteReconnectRestartPolicy.DEFAULT_THRESHOLD_MS} " +
                                    "attempt=${indefiniteReconnectRestartPolicy.attempts()} activeGame=${WarEx.inWar}"
                            }
                            return restartClientForIndefiniteReconnect(now)
                        }
                        IndefiniteReconnectRestartPolicy.Decision.ALERT_OPERATOR -> {
                            log.error {
                                "SCREEN_RECOVERY_RECONNECT_RESTART_EXHAUSTED screen=LOADING " +
                                    "attempts=${indefiniteReconnectRestartPolicy.attempts()} action=NO_PAUSE_NO_INPUT"
                            }
                            SystemUtil.notice(
                                "Hearthstone remained on the reconnect loading screen after bounded client restarts. " +
                                    "Automation was not paused; inspect the client and click Start to retry.",
                            )
                            return false
                        }
                        IndefiniteReconnectRestartPolicy.Decision.DEFER_ACTIVE_QUEUE,
                        IndefiniteReconnectRestartPolicy.Decision.DEFER_TERMINAL -> return false
                        IndefiniteReconnectRestartPolicy.Decision.WAIT -> {
                            log.info {
                                "SCREEN_RECOVERY_RECONNECT_INDEFINITE_WAIT screen=LOADING " +
                                    "thresholdMs=${IndefiniteReconnectRestartPolicy.DEFAULT_THRESHOLD_MS} " +
                                    "action=NO_CLICK_NO_PAUSE"
                            }
                            return false
                        }
                    }
                }
                if (!WarEx.inWar) Mode.recover(ModeEnum.STARTUP, "visible-loading-screen", enterStrategy = false)
                log.info { "SCREEN_RECOVERY_APPLIED screen=LOADING action=WAIT_FOR_CLIENT" }
            }

            else -> {
                Mode.recover(detection.mode, "visible-${detection.kind.code.lowercase(Locale.ROOT)}", enterStrategy = true)
                log.warn {
                    "SCREEN_RECOVERY_APPLIED screen=${detection.kind.code} mode=${detection.mode.name} " +
                        "action=ENTER_MODE_STRATEGY"
                }
            }
        }
        return true
    }

    private fun confirmFreshPreSessionReconnect(
        expectedPid: Int,
        expectedWindow: WinDef.HWND,
    ): Boolean {
        if (ScreenStateRecovery.isCurrentGameSessionReadyForStart() || WarEx.inWar ||
            LifecycleTrace.hasRecentActiveMatchmakingScreen() || LifecycleTrace.hasRecentMatchmakingInputIntent()
        ) return false
        val authorized = captureVerifiedGameScreen(stuckForMs = 0L, startupProbe = true) ?: return false
        if (authorized.purpose != ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE ||
            authorized.gameWindow.toString() != expectedWindow.toString() ||
            GameUtil.findGameProcessIdForDiagnostics() != expectedPid.toLong()
        ) return false
        val ocrText = runOCR(authorized.capture)
        val detection = detect(ocrText, authorized.capture.visual)
        val freshAndExact = detection?.kind == ScreenKind.RECONNECT && detection.confidence >= 94 &&
            detection.evidence in setOf("reconnect-dialog-roi", "reconnect-disconnected-text", "reconnect-dialog-structural-visual") &&
            authorized.capture.purpose == ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE &&
            GameUtil.isVerifiedCurrentGameWindow(expectedWindow) &&
            GameUtil.findGameProcessIdForDiagnostics() == expectedPid.toLong() &&
            !ScreenStateRecovery.isCurrentGameSessionReadyForStart() && !WarEx.inWar &&
            !LifecycleTrace.hasRecentActiveMatchmakingScreen() && !LifecycleTrace.hasRecentMatchmakingInputIntent()
        if (!freshAndExact) {
            log.warn {
                "SCREEN_RECOVERY_PRESESSION_RECONNECT_BLOCKED provider=UPSTREAM " +
                    "screen=${detection?.kind?.code ?: "UNKNOWN"} confidence=${detection?.confidence ?: 0} " +
                    "pid=$expectedPid hwnd=$expectedWindow action=NO_INPUT"
            }
        }
        return freshAndExact
    }
}

