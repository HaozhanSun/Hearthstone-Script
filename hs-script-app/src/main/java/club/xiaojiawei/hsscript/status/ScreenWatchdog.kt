package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.consts.CHI_SIM_DATA
import club.xiaojiawei.hsscript.consts.TESS_DATA_PATH
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.ocr.PaddleXOcrCancelledException
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.MouseUtil
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CancellationException
import net.sourceforge.tess4j.Tesseract

enum class ScreenWatchdogKind {
    WIN,
    LOST,
    RESULT,
    MATCHMAKING,
    MAIN_MENU,
    SETTINGS,
    SURRENDER_CONFIRMATION,
    GAMEPLAY,
    MULLIGAN,
    UNKNOWN,
    CAPTURE_FAILED,
}

enum class ScreenWatchdogRecoveryAction {
    CONTINUE_ACTION,
    STOP_SURRENDER_NO_ACTION,
    STOP_SURRENDER_AND_RECORD_WIN,
    STOP_SURRENDER_AND_RECORD_LOSS,
    STOP_SURRENDER_AND_CLEAR_RESULT,
    STOP_SURRENDER_AND_RECOVER_MATCHMAKING,
    STOP_SURRENDER_AND_RECOVER_MAIN_MENU,
    STOP_SURRENDER_AND_CONTINUE_UNKNOWN,
    STOP_SURRENDER_AND_RESUME_GAMEPLAY,
}

data class ScreenWatchdogObservation(
    val kind: ScreenWatchdogKind,
    val action: ScreenWatchdogRecoveryAction,
    val ocrText: String,
    val screenshotPath: String?,
    val provider: String,
    val reason: String,
)

/**
 * Last-chance screen observer for repeated recovery actions.
 *
 * The normal state machine is still driven by Power.log.  This watchdog is
 * only invoked after a state/action has repeated long enough to become
 * suspect, then captures the visible client, OCRs it through the configured
 * OCR runtime, and gives terminal/result screens priority over more clicks.
 */
object ScreenWatchdog {

    private const val OCR_MAX_WIDTH = 1280
    private val lastCaptureAt = AtomicLong(0L)

    internal data class TimingDecision(
        val shouldInspect: Boolean,
        val reason: String,
    )

    internal fun resetTimingForTest() {
        lastCaptureAt.set(0L)
    }

    internal fun shouldInspect(
        startedAt: Long,
        attempts: Int,
        now: Long = System.currentTimeMillis(),
        stuckMs: Long = ConfigUtil.getLong(ConfigEnum.SCREEN_WATCHDOG_STUCK_MS),
        maxRetries: Int = ConfigUtil.getInt(ConfigEnum.SCREEN_WATCHDOG_MAX_RETRIES),
        cooldownMs: Long = ConfigUtil.getLong(ConfigEnum.SCREEN_WATCHDOG_COOLDOWN_MS),
        bypassCooldownForMandatorySurrenderPostClick: Boolean = false,
        bypassInitialDelayForMandatorySurrender: Boolean = false,
    ): TimingDecision {
        if (!ConfigUtil.getBoolean(ConfigEnum.SCREEN_WATCHDOG_ENABLED)) {
            return TimingDecision(false, "disabled")
        }
        val stuckFor = now - startedAt
        val retryExceeded = attempts >= maxRetries.coerceAtLeast(1)
        val stuckExceeded = stuckFor >= stuckMs.coerceAtLeast(0L)
        if (!bypassInitialDelayForMandatorySurrender && !retryExceeded && !stuckExceeded) {
            return TimingDecision(false, "below-threshold stuckForMs=$stuckFor attempts=$attempts")
        }
        val previous = lastCaptureAt.get()
        if (!bypassInitialDelayForMandatorySurrender &&
            !bypassCooldownForMandatorySurrenderPostClick && previous > 0L &&
            now - previous < cooldownMs.coerceAtLeast(0L)
        ) {
            return TimingDecision(false, "cooldown remainingMs=${cooldownMs - (now - previous)}")
        }
        if (!lastCaptureAt.compareAndSet(previous, now)) {
            return TimingDecision(false, "in-flight")
        }
        val reason = if (bypassInitialDelayForMandatorySurrender) {
            "mandatory-surrender-initial-dispatch stuckForMs=$stuckFor attempts=$attempts"
        } else if (bypassCooldownForMandatorySurrenderPostClick) {
            "mandatory-surrender-post-click stuckForMs=$stuckFor attempts=$attempts"
        } else {
            "threshold stuckForMs=$stuckFor attempts=$attempts"
        }
        return TimingDecision(true, reason)
    }

    /**
     * A mandatory surrender click changes the very screen the retry worker
     * must verify. One bounded, next-tick observation may skip the shared
     * watchdog cooldown; ordinary watchdog probes remain globally throttled.
     */
    internal class MandatorySurrenderPostClickProbe {
        companion object {
            /**
             * The in-game Settings menu needs time to finish its transition
             * before its surrender button can be confirmed from fresh pixels.
             * This is intentionally separate from the global stuck-screen
             * watchdog: rank recovery must not sleep for that watchdog's
             * 30-second threshold after an intentional Settings click.
             */
            const val SETTINGS_OVERLAY_SETTLE_MS = 5_000L
            const val SETTINGS_OVERLAY_UNCERTAIN_RECHECK_MS = 2_000L
            /** scheduleWithFixedDelay uses the randomized 500 ms interval (400..600 ms). */
            const val SETTINGS_OVERLAY_MAX_PROBE_LATENCY_MS = SETTINGS_OVERLAY_SETTLE_MS + 600L

            private const val NO_SETTINGS_PROBE = 0L
            private const val SETTINGS_PROBE_IN_FLIGHT = -1L
        }

        private val genericPending = AtomicBoolean(false)
        private val settingsProbeDueAt = AtomicLong(NO_SETTINGS_PROBE)

        fun markClickDispatched() {
            genericPending.set(true)
        }

        fun shouldBypassCooldown(): Boolean = genericPending.get()

        fun markProbeStarted() {
            genericPending.set(false)
        }

        /** Starts the five-second Settings-overlay settle after the gear input is dispatched. */
        fun markSettingsClickDispatched(now: Long = System.currentTimeMillis()) {
            settingsProbeDueAt.set(now + SETTINGS_OVERLAY_SETTLE_MS)
        }

        /**
         * Returns a single bounded, fresh observation once the Settings
         * settle has elapsed. An uncertain image is explicitly re-armed by
         * [finishSettingsOverlayProbe] on the short safe cadence below.
         */
        fun settingsOverlayProbeTiming(now: Long = System.currentTimeMillis()): TimingDecision? {
            val dueAt = settingsProbeDueAt.get()
            when (dueAt) {
                NO_SETTINGS_PROBE -> return null
                SETTINGS_PROBE_IN_FLIGHT -> return TimingDecision(false, "mandatory-surrender-settings-probe-in-flight")
            }
            if (now < dueAt) {
                return TimingDecision(
                    false,
                    "mandatory-surrender-settings-settle remainingMs=${dueAt - now}",
                )
            }
            if (!settingsProbeDueAt.compareAndSet(dueAt, SETTINGS_PROBE_IN_FLIGHT)) {
                return TimingDecision(false, "mandatory-surrender-settings-probe-in-flight")
            }
            return TimingDecision(
                true,
                "mandatory-surrender-settings-overlay-probe settledMs=${now - (dueAt - SETTINGS_OVERLAY_SETTLE_MS)}",
            )
        }

        /**
         * A fresh Settings overlay clears the staged probe. Any uncertain
         * capture remains observe-only and schedules a short recheck instead
         * of falling back to the generic 30-second stuck threshold.
         */
        fun finishSettingsOverlayProbe(settingsConfirmed: Boolean, now: Long = System.currentTimeMillis()) {
            settingsProbeDueAt.set(
                if (settingsConfirmed) NO_SETTINGS_PROBE else now + SETTINGS_OVERLAY_UNCERTAIN_RECHECK_MS,
            )
        }
    }

    fun inspectForSurrender(
        state: String,
        attempts: Int,
        trigger: String = "surrender-retry",
        mandatoryRankSurrender: Boolean = false,
        captureProvider: () -> BufferedImage? = if (mandatoryRankSurrender) {
            ::captureMandatoryRankRecoveryScreen
        } else {
            ::captureScreen
        },
        ocrProvider: (BufferedImage) -> String = ::runOCR,
    ): ScreenWatchdogObservation {
        // Keep the established watchdog as a separate, rename-only upstream
        // implementation.  The additive Beta capture/state heuristics must
        // not leak into a runtime with the Beta extension switch turned off.
        val betaRecoveryEnabled = ScreenRecoveryRuntime.isEnabled()
        if (!betaRecoveryEnabled && !mandatoryRankSurrender) {
            return UpstreamScreenWatchdog.inspectForSurrender(
                state = state,
                attempts = attempts,
                trigger = trigger,
                captureProvider = captureProvider,
                ocrProvider = ocrProvider,
            )
        }
        val runId = System.getProperty("hs.script.e2e.run-id", "normal")
        val activeGameplay = isAuthoritativeActiveGameplay(state)
        // This watchdog only classifies terminal/menu screens. Keep it on the
        // local OCR path so a PaddleX rank request can never block surrender
        // recovery or hold the action executor for a long sidecar timeout.
        val provider = "LEGACY"
        val image = runCatching { captureProvider() }.getOrElse { error ->
            log.warn(error) {
                "SCREEN_WATCHDOG_CAPTURE_FAILED runId=$runId trigger=$trigger state=$state attempts=$attempts"
            }
            null
        }
        if (image == null) {
            return ScreenWatchdogObservation(
                kind = ScreenWatchdogKind.CAPTURE_FAILED,
                action = if (activeGameplay) {
                    ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RESUME_GAMEPLAY
                } else {
                    ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_CONTINUE_UNKNOWN
                },
                ocrText = "",
                screenshotPath = null,
                provider = provider,
                reason = "capture-failed",
            )
        }

        val evidence = UnknownStateScreenshot.save(
            image = image,
            regions = listOf(
                UnknownStateScreenshot.UnknownRegion(
                    Rectangle(0, 0, image.width, image.height),
                    "screen-watchdog",
                ),
            ),
            category = UnknownStateScreenshot.CATEGORY_SCREEN_WATCHDOG,
            trigger = "screen-watchdog-$runId-$trigger",
            state = state,
            phase = "screen-watchdog",
        )
        log.warn {
            "SCREEN_WATCHDOG_CAPTURE runId=$runId trigger=$trigger state=$state attempts=$attempts " +
                "activeGameplay=$activeGameplay provider=$provider " +
                "path=${evidence?.file?.absolutePath ?: "not-saved"}"
        }

        // Run the small fixed visual probes before full-screen Tesseract.
        // On a 1920x1080 live frame OCR can take >20s; authoritative phase plus
        // a fresh, specific menu/hand signature is enough for the next
        // recovery-only step. Never let these nonterminal signatures mask an
        // authoritative Power.log terminal phase.
        val confirmationMetrics = surrenderConfirmationMetrics(image)
        val settingsMetrics = settingsOverlayMetrics(image)
        val mulliganMetrics = if (state.contains("warPhase=REPLACE_CARD", ignoreCase = true)) {
            mulliganVisualMetrics(image)
        } else {
            null
        }
        val fastVisualKind = fastVisualKindForSurrender(
            state = state,
            terminalState = isAuthoritativeTerminalState(state),
            confirmation = confirmationMetrics,
            settings = settingsMetrics,
            mulligan = mulliganMetrics,
        )
        if (fastVisualKind != null) {
            val reason = when (fastVisualKind) {
                ScreenWatchdogKind.SURRENDER_CONFIRMATION -> "fresh-surrender-confirmation-visual"
                ScreenWatchdogKind.SETTINGS -> "fresh-settings-overlay-visual-priority"
                ScreenWatchdogKind.MULLIGAN -> "authoritative-mulligan-input-and-fresh-mulligan-visual"
                else -> "fresh-visual-recovery"
            }
            val action = decide(fastVisualKind, activeGameplay)
            log.warn {
                "SCREEN_WATCHDOG_OCR runId=$runId provider=LEGACY kind=$fastVisualKind action=$action " +
                    "activeGameplay=$activeGameplay chars=0 visualFallback=true " +
                    "mandatoryRankSurrender=$mandatoryRankSurrender " +
                    "betaRecoveryExtensionsEnabled=$betaRecoveryEnabled ocrSkipped=visual-fast-path " +
                    "confirmationVisual={$confirmationMetrics} settingsVisual={$settingsMetrics} " +
                    "mulliganVisual=${mulliganMetrics ?: "not-applicable"} " +
                    "screenshot=${evidence?.file?.absolutePath ?: "not-saved"} ocr=<skipped>"
            }
            return ScreenWatchdogObservation(
                kind = fastVisualKind,
                action = action,
                ocrText = "",
                screenshotPath = evidence?.file?.absolutePath,
                provider = "LEGACY",
                reason = reason,
            )
        }

        val ocrText = runCatching { ocrProvider(image).replace(Regex("\\s+"), "") }.getOrElse { error ->
            if (error is PaddleXOcrCancelledException ||
                error is CancellationException ||
                error is InterruptedException ||
                Thread.currentThread().isInterrupted
            ) {
                log.info(error) {
                    "SCREEN_WATCHDOG_OCR_CANCELLED runId=$runId provider=$provider trigger=$trigger " +
                        "screenshot=${evidence?.file?.absolutePath ?: "not-saved"}"
                }
                return ScreenWatchdogObservation(
                    kind = ScreenWatchdogKind.UNKNOWN,
                    action = ScreenWatchdogRecoveryAction.STOP_SURRENDER_NO_ACTION,
                    ocrText = "",
                    screenshotPath = evidence?.file?.absolutePath,
                    provider = provider,
                    reason = "ocr-cancelled",
                )
            }
            log.warn(error) {
                "SCREEN_WATCHDOG_OCR_FAILED runId=$runId provider=$provider trigger=$trigger " +
                    "screenshot=${evidence?.file?.absolutePath ?: "not-saved"}"
            }
            ""
        }
        val providerUsed = "LEGACY"
        val ocrKind = classify(ocrText)
        // During a live turn, the legacy OCR can miss all board labels even
        // though the current Hearthstone capture is clear. Permit only the
        // first recovery step (open Settings) when Power.log state is still
        // an active player turn and the fresh image has both the board and
        // two-hero gameplay composition. OCR-recognized terminal/menu/dialog
        // states always take precedence over this visual fallback.
        val kind = classifyForSurrender(ocrKind, state, image)
        val visualGameplayFallback = ocrKind == ScreenWatchdogKind.UNKNOWN &&
            kind in setOf(ScreenWatchdogKind.GAMEPLAY, ScreenWatchdogKind.MULLIGAN)
        val confirmationVisual = confirmationMetrics.toString()
        val settingsVisual = settingsMetrics.toString()
        val mulliganVisual = mulliganMetrics?.toString() ?: "not-applicable"
        val reason = when (kind) {
            ScreenWatchdogKind.SURRENDER_CONFIRMATION -> if (ocrKind == ScreenWatchdogKind.SURRENDER_CONFIRMATION) {
                "ocr-and-fresh-confirmation-modal-visual"
            } else {
                "fresh-surrender-confirmation-visual"
            }
            ScreenWatchdogKind.SETTINGS -> if (ocrKind != ScreenWatchdogKind.SETTINGS) {
                "fresh-settings-overlay-visual-priority"
            } else {
                "ocr-classified"
            }
            ScreenWatchdogKind.MULLIGAN -> "authoritative-mulligan-input-and-fresh-mulligan-visual"
            ScreenWatchdogKind.GAMEPLAY -> if (visualGameplayFallback) {
                "authoritative-gameplay-and-fresh-board-visual"
            } else {
                "ocr-classified"
            }
            else -> "ocr-classified"
        }
        val action = decide(kind, activeGameplay)
        log.warn {
            "SCREEN_WATCHDOG_OCR runId=$runId provider=$providerUsed kind=$kind action=$action " +
                "activeGameplay=$activeGameplay chars=${ocrText.length} " +
                "visualFallback=$visualGameplayFallback " +
                "mandatoryRankSurrender=$mandatoryRankSurrender " +
                "betaRecoveryExtensionsEnabled=$betaRecoveryEnabled " +
                "confirmationVisual={$confirmationVisual} " +
                "settingsVisual={$settingsVisual} " +
                "mulliganVisual={$mulliganVisual} " +
                "screenshot=${evidence?.file?.absolutePath ?: "not-saved"} " +
                "ocr=${sanitize(ocrText).take(240).ifBlank { "<empty>" }}"
        }
        return ScreenWatchdogObservation(
            kind = kind,
            action = action,
            ocrText = ocrText,
            screenshotPath = evidence?.file?.absolutePath,
            provider = providerUsed,
            reason = reason,
        )
    }

    internal fun classifyForTest(ocrText: String): ScreenWatchdogKind = classify(ocrText)

    internal fun decideForTest(kind: ScreenWatchdogKind): ScreenWatchdogRecoveryAction = decide(kind)

    internal fun isAuthoritativeActiveGameplayForTest(state: String): Boolean =
        isAuthoritativeActiveGameplay(state)

    internal fun hasActiveGameplayVisualForTest(image: BufferedImage): Boolean =
        hasActiveGameplayVisual(image)

    internal fun classifyForSurrenderForTest(
        ocrText: String,
        state: String,
        image: BufferedImage,
    ): ScreenWatchdogKind = classifyForSurrender(classify(ocrText), state, image)

    internal fun settingsOverlayDiagnosticsForTest(image: BufferedImage): String =
        settingsOverlayMetrics(image).toString()

    internal fun hasSettingsOverlayVisualForTest(image: BufferedImage): Boolean =
        settingsOverlayMetrics(image).accepted

    internal fun surrenderConfirmationDiagnosticsForTest(image: BufferedImage): String =
        surrenderConfirmationMetrics(image).toString()

    internal fun hasSurrenderConfirmationVisualForTest(image: BufferedImage): Boolean =
        surrenderConfirmationMetrics(image).accepted

    internal fun hasSurrenderConfirmationPanelForTest(image: BufferedImage): Boolean =
        surrenderConfirmationMetrics(image).panelVisible

    internal fun fastVisualKindForSurrenderForTest(
        state: String,
        image: BufferedImage,
    ): ScreenWatchdogKind? = fastVisualKindForSurrender(
        state = state,
        terminalState = isAuthoritativeTerminalState(state),
        confirmation = surrenderConfirmationMetrics(image),
        settings = settingsOverlayMetrics(image),
        mulligan = if (state.contains("warPhase=REPLACE_CARD", ignoreCase = true)) {
            mulliganVisualMetrics(image)
        } else {
            null
        },
    )

    private fun fastVisualKindForSurrender(
        state: String,
        terminalState: Boolean,
        confirmation: SurrenderConfirmationMetrics,
        settings: SettingsOverlayMetrics,
        mulligan: MulliganVisualMetrics?,
    ): ScreenWatchdogKind? {
        if (terminalState) return null
        val activeGameplay = isAuthoritativeActiveGameplay(state)
        val activeMulligan = isAuthoritativeMulliganInput(state)
        if (!activeGameplay && !activeMulligan) return null
        if (confirmation.accepted) return ScreenWatchdogKind.SURRENDER_CONFIRMATION
        // Partial confirmation panels must still go through full OCR and fail
        // closed; never misroute them to the Settings surrender button.
        if (confirmation.panelVisible) return null
        if (settings.accepted) return ScreenWatchdogKind.SETTINGS
        if (activeMulligan && mulligan?.accepted == true) return ScreenWatchdogKind.MULLIGAN
        return null
    }

    private fun isAuthoritativeTerminalState(state: String): Boolean {
        val fields = state.lowercase(Locale.ROOT)
            .split('|', ';', ' ', ',')
            .filter { it.isNotBlank() }
            .toSet()
        return fields.contains("warphase=game_over") ||
            fields.contains("step=final_gameover") ||
            fields.contains("won=true") || fields.contains("lost=true") || fields.contains("conceded=true")
    }

    private fun classifyForSurrender(
        ocrKind: ScreenWatchdogKind,
        state: String,
        image: BufferedImage,
    ): ScreenWatchdogKind {
        // Terminal OCR is authoritative and must never be displaced by a
        // nonterminal overlay signature.
        if (ocrKind in setOf(
                ScreenWatchdogKind.WIN,
                ScreenWatchdogKind.LOST,
                ScreenWatchdogKind.RESULT,
            )
        ) return ocrKind

        val confirmation = surrenderConfirmationMetrics(image)
        if (confirmation.accepted) return ScreenWatchdogKind.SURRENDER_CONFIRMATION
        // A partially obscured confirmation panel must fail closed rather
        // than falling through to the visible Settings menu behind it.
        if (confirmation.panelVisible || ocrKind == ScreenWatchdogKind.SURRENDER_CONFIRMATION) {
            return ScreenWatchdogKind.UNKNOWN
        }
        // The authoritative Mulligan phase persists behind the Settings
        // overlay. Trust the fresh overlay only when its visual signature is
        // present; OCR text alone must never authorize the surrender button.
        if (settingsOverlayMetrics(image).accepted) return ScreenWatchdogKind.SETTINGS
        if (isAuthoritativeMulliganInput(state) && hasMulliganVisual(image)) {
            return ScreenWatchdogKind.MULLIGAN
        }
        if (ocrKind == ScreenWatchdogKind.SETTINGS) return ScreenWatchdogKind.UNKNOWN
        if (ocrKind != ScreenWatchdogKind.UNKNOWN) return ocrKind
        if (isAuthoritativeActiveGameplay(state) && hasActiveGameplayVisual(image)) {
            return ScreenWatchdogKind.GAMEPLAY
        }
        return ocrKind
    }

    private fun isAuthoritativeMulliganInput(state: String): Boolean {
        val fields = state.lowercase(Locale.ROOT)
            .split('|', ';', ' ', ',')
            .filter { it.isNotBlank() }
            .toSet()
        return fields.contains("mode=gameplay") &&
            fields.contains("inwar=true") &&
            fields.contains("warphase=replace_card") &&
            fields.contains("mymulliganinput=true")
    }

    internal fun hasMulliganVisualForTest(image: BufferedImage): Boolean = hasMulliganVisual(image)

    internal fun mulliganVisualDiagnosticsForTest(image: BufferedImage): String =
        mulliganVisualMetrics(image).toString()

    private data class MulliganVisualMetrics(
        val width: Int,
        val height: Int,
        val bannerGold: Double,
        val bannerVivid: Double,
        val handVivid: Double,
        val heroVivid: Double,
        val cardVivid: List<Double>,
        val visibleCardCount: Int,
        val animationFallback: Boolean,
        val accepted: Boolean,
    ) {
        override fun toString(): String =
            "size=${width}x$height bannerGold=${"%.4f".format(Locale.ROOT, bannerGold)} " +
                "bannerVivid=${"%.4f".format(Locale.ROOT, bannerVivid)} " +
                "handVivid=${"%.4f".format(Locale.ROOT, handVivid)} " +
                "heroVivid=${"%.4f".format(Locale.ROOT, heroVivid)} " +
                "cardVivid=${cardVivid.joinToString(",") { "%.4f".format(Locale.ROOT, it) }} " +
                "visibleCardCount=$visibleCardCount animationFallback=$animationFallback accepted=$accepted"
    }

    private fun hasMulliganVisual(image: BufferedImage): Boolean = mulliganVisualMetrics(image).accepted

    private data class SettingsOverlayMetrics(
        val width: Int,
        val height: Int,
        val headerBeige: Double,
        val surrenderRed: Double,
        val optionsBeige: Double,
        val exitBeige: Double,
        val frameTop: Double,
        val frameBottom: Double,
        val frameLeft: Double,
        val frameRight: Double,
        val accepted: Boolean,
    ) {
        override fun toString(): String =
            "size=${width}x$height headerBeige=${"%.4f".format(Locale.ROOT, headerBeige)} " +
                "surrenderRed=${"%.4f".format(Locale.ROOT, surrenderRed)} " +
                "optionsBeige=${"%.4f".format(Locale.ROOT, optionsBeige)} " +
                "exitBeige=${"%.4f".format(Locale.ROOT, exitBeige)} " +
                "frameTop=${"%.4f".format(Locale.ROOT, frameTop)} " +
                "frameBottom=${"%.4f".format(Locale.ROOT, frameBottom)} " +
                "frameLeft=${"%.4f".format(Locale.ROOT, frameLeft)} " +
                "frameRight=${"%.4f".format(Locale.ROOT, frameRight)} accepted=$accepted"
    }

    private data class SurrenderConfirmationMetrics(
        val width: Int,
        val height: Int,
        val titleBeige: Double,
        val bodyPanelGray: Double,
        val warningYellow: Double,
        val acceptButtonBeige: Double,
        val continueButtonBeige: Double,
        val acceptCheckGreen: Double,
        val continueCrossRed: Double,
        val frameTop: Double,
        val frameBottom: Double,
        val frameLeft: Double,
        val frameRight: Double,
        val panelVisible: Boolean,
        val accepted: Boolean,
    ) {
        override fun toString(): String =
            "size=${width}x$height titleBeige=${"%.4f".format(Locale.ROOT, titleBeige)} " +
                "bodyPanelGray=${"%.4f".format(Locale.ROOT, bodyPanelGray)} " +
                "warningYellow=${"%.4f".format(Locale.ROOT, warningYellow)} " +
                "acceptButtonBeige=${"%.4f".format(Locale.ROOT, acceptButtonBeige)} " +
                "continueButtonBeige=${"%.4f".format(Locale.ROOT, continueButtonBeige)} " +
                "acceptCheckGreen=${"%.4f".format(Locale.ROOT, acceptCheckGreen)} " +
                "continueCrossRed=${"%.4f".format(Locale.ROOT, continueCrossRed)} " +
                "frameTop=${"%.4f".format(Locale.ROOT, frameTop)} " +
                "frameBottom=${"%.4f".format(Locale.ROOT, frameBottom)} " +
                "frameLeft=${"%.4f".format(Locale.ROOT, frameLeft)} " +
                "frameRight=${"%.4f".format(Locale.ROOT, frameRight)} " +
                "panelVisible=$panelVisible accepted=$accepted"
    }

    /** Recognize the centered surrender-confirmation modal, not the board/menu behind it. */
    private fun surrenderConfirmationMetrics(image: BufferedImage): SurrenderConfirmationMetrics {
        if (image.width < 800 || image.height < 450) {
            return SurrenderConfirmationMetrics(
                image.width, image.height, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, false, false,
            )
        }
        val titleBeige = colorRatio(image, 0.438, 0.382, 0.562, 0.435) { red, green, blue ->
            red >= 100 && green >= 80 && blue >= 55 &&
                red - blue <= 125 && red >= green && green >= blue * 0.78
        }
        val bodyPanelGray = colorRatio(image, 0.315, 0.445, 0.685, 0.565) { red, green, blue ->
            red in 25..155 && green in 25..155 && blue in 25..155 &&
                maxOf(red, green, blue) - minOf(red, green, blue) <= 55
        }
        val warningYellow = colorRatio(image, 0.326, 0.462, 0.376, 0.542) { red, green, blue ->
            red >= 135 && green >= 90 && red > green * 1.12 && green > blue * 1.22
        }
        val acceptButtonBeige = colorRatio(image, 0.381, 0.576, 0.494, 0.631) { red, green, blue ->
            red >= 135 && green >= 105 && blue >= 65 &&
                red - blue <= 115 && red >= green && green >= blue * 0.82
        }
        val continueButtonBeige = colorRatio(image, 0.505, 0.576, 0.617, 0.631) { red, green, blue ->
            red >= 135 && green >= 105 && blue >= 65 &&
                red - blue <= 115 && red >= green && green >= blue * 0.82
        }
        val acceptCheckGreen = colorRatio(image, 0.388, 0.589, 0.411, 0.619) { red, green, blue ->
            green >= 90 && green > red * 1.20 && green > blue * 0.72
        }
        val continueCrossRed = colorRatio(image, 0.512, 0.589, 0.535, 0.619) { red, green, blue ->
            red >= 110 && red > green * 1.25 && red > blue * 1.10
        }
        // The title/body color ROIs overlap normal card art and mulligan speech
        // bubbles. Require the distinctive large, four-sided dialog frame as
        // well, so a false partial panel cannot suppress the live phase.
        val darkNeutral: (Int, Int, Int) -> Boolean = { red, green, blue ->
            red in 15..135 && green in 15..135 && blue in 15..135 &&
                maxOf(red, green, blue) - minOf(red, green, blue) <= 45
        }
        val frameTop = colorRatio(image, 0.295, 0.379, 0.705, 0.404, darkNeutral)
        val frameBottom = colorRatio(image, 0.295, 0.590, 0.705, 0.615, darkNeutral)
        val frameLeft = colorRatio(image, 0.290, 0.395, 0.315, 0.605, darkNeutral)
        val frameRight = colorRatio(image, 0.685, 0.395, 0.710, 0.605, darkNeutral)
        val panelVisible = titleBeige >= 0.12 && bodyPanelGray >= 0.30 && warningYellow >= 0.015 &&
            frameTop >= 0.60 && frameBottom >= 0.40 && frameLeft >= 0.60 && frameRight >= 0.40
        val choicesVisible = acceptButtonBeige >= 0.10 && continueButtonBeige >= 0.10 &&
            acceptCheckGreen >= 0.02 && continueCrossRed >= 0.02
        return SurrenderConfirmationMetrics(
            image.width,
            image.height,
            titleBeige,
            bodyPanelGray,
            warningYellow,
            acceptButtonBeige,
            continueButtonBeige,
            acceptCheckGreen,
            continueCrossRed,
            frameTop,
            frameBottom,
            frameLeft,
            frameRight,
            panelVisible,
            panelVisible && choicesVisible,
        )
    }

    /** Three small, fixed menu-button ROIs; broad Hearthstone colors alone never confirm Settings. */
    private fun settingsOverlayMetrics(image: BufferedImage): SettingsOverlayMetrics {
        if (image.width < 800 || image.height < 450) {
            return SettingsOverlayMetrics(
                image.width, image.height,
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                false,
            )
        }
        val headerBeige = colorRatio(image, 0.424, 0.259, 0.587, 0.298) { red, green, blue ->
            red >= 135 && green >= 110 && blue >= 70 &&
                red - blue <= 125 && red >= green && green >= blue * 0.80
        }
        val surrenderRed = colorRatio(image, 0.445, 0.323, 0.571, 0.381) { red, green, blue ->
            red >= 125 && red > green * 1.35 && green > blue * 1.12
        }
        val optionsBeige = colorRatio(image, 0.445, 0.418, 0.571, 0.477) { red, green, blue ->
            red >= 135 && green >= 105 && blue >= 65 &&
                red - blue <= 115 && red >= green && green >= blue * 0.82
        }
        val exitBeige = colorRatio(image, 0.445, 0.510, 0.571, 0.569) { red, green, blue ->
            red >= 135 && green >= 105 && blue >= 65 &&
                red - blue <= 115 && red >= green && green >= blue * 0.82
        }
        // The four button ROIs overlap Hearthstone's tan board/card art. Require
        // the centered, four-sided menu frame as well, or an active board can
        // be mistaken for Settings and route a surrender click to the wrong UI.
        val darkNeutral: (Int, Int, Int) -> Boolean = { red, green, blue ->
            red in 15..135 && green in 15..135 && blue in 15..135 &&
                maxOf(red, green, blue) - minOf(red, green, blue) <= 45
        }
        val frameTop = colorRatio(image, 0.398, 0.255, 0.602, 0.278, darkNeutral)
        val frameBottom = colorRatio(image, 0.398, 0.595, 0.602, 0.622, darkNeutral)
        val frameLeft = colorRatio(image, 0.398, 0.278, 0.418, 0.595, darkNeutral)
        val frameRight = colorRatio(image, 0.582, 0.278, 0.602, 0.595, darkNeutral)
        val accepted = headerBeige >= 0.12 && surrenderRed >= 0.10 &&
            optionsBeige >= 0.10 && exitBeige >= 0.10 &&
            frameTop >= 0.55 && frameBottom >= 0.55 &&
            frameLeft >= 0.55 && frameRight >= 0.55
        return SettingsOverlayMetrics(
            image.width,
            image.height,
            headerBeige,
            surrenderRed,
            optionsBeige,
            exitBeige,
            frameTop,
            frameBottom,
            frameLeft,
            frameRight,
            accepted,
        )
    }

    private fun colorRatio(
        image: BufferedImage,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        matches: (red: Int, green: Int, blue: Int) -> Boolean,
    ): Double {
        val x0 = (image.width * left).toInt().coerceIn(0, image.width)
        val x1 = (image.width * right).toInt().coerceIn(x0, image.width)
        val y0 = (image.height * top).toInt().coerceIn(0, image.height)
        val y1 = (image.height * bottom).toInt().coerceIn(y0, image.height)
        var matched = 0
        var samples = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val rgb = image.getRGB(x, y)
                if (matches(rgb shr 16 and 0xff, rgb shr 8 and 0xff, rgb and 0xff)) matched++
                samples++
                x += 2
            }
            y += 2
        }
        return if (samples == 0) 0.0 else matched.toDouble() / samples
    }

    private fun mulliganVisualMetrics(image: BufferedImage): MulliganVisualMetrics {
        if (image.width < 800 || image.height < 450) {
            return MulliganVisualMetrics(image.width, image.height, 0.0, 0.0, 0.0, 0.0, emptyList(), 0, false, false)
        }
        val aspect = image.width.toDouble() / image.height
        if (aspect !in 1.55..1.90) {
            return MulliganVisualMetrics(image.width, image.height, 0.0, 0.0, 0.0, 0.0, emptyList(), 0, false, false)
        }
        // Normally require the distinctive start-hand banner and colorful
        // hand. During the game's transient Mulligan emote/time-warp animation
        // the banner can disappear and a neutral speech bubble can mask much
        // of the hand. In that case require at least two distinct visible card
        // tops plus a minimum residual hand signature. The caller still
        // requires authoritative Power.log Mulligan-input state, and Settings
        // / confirmation overlays are checked first.
        val banner = colorRatios(image, 0.35, 0.09, 0.66, 0.24)
        val hand = colorRatios(image, 0.20, 0.30, 0.80, 0.68)
        val hero = colorRatios(image, 0.455, 0.68, 0.545, 0.88)
        val cardVivid = listOf(
            colorRatios(image, 0.225, 0.30, 0.385, 0.425).vividRatio,
            colorRatios(image, 0.375, 0.30, 0.535, 0.425).vividRatio,
            colorRatios(image, 0.525, 0.30, 0.685, 0.425).vividRatio,
        )
        val visibleCardCount = cardVivid.count { it >= 0.08 }
        val bannerAndHand = banner.goldRatio >= 0.025 && banner.vividRatio >= 0.10 &&
            hand.vividRatio >= 0.22
        val animationFallback = !bannerAndHand && visibleCardCount >= 2 && hand.vividRatio >= 0.16
        val accepted = bannerAndHand || animationFallback
        return MulliganVisualMetrics(
            image.width,
            image.height,
            banner.goldRatio,
            banner.vividRatio,
            hand.vividRatio,
            hero.vividRatio,
            cardVivid,
            visibleCardCount,
            animationFallback,
            accepted,
        )
    }

    private fun hasActiveGameplayVisual(image: BufferedImage): Boolean {
        if (image.width < 800 || image.height < 450) return false
        // Hearthstone's live board occupies the central field and has the
        // opponent/player hero portraits at stable normalized positions.
        // Requiring all three regions avoids treating a generic warm-colored
        // menu, dialog, or desktop capture as gameplay evidence.
        val board = colorRatios(image, 0.20, 0.32, 0.80, 0.68)
        val opponentHero = colorRatios(image, 0.455, 0.08, 0.545, 0.25)
        val playerHero = colorRatios(image, 0.455, 0.68, 0.545, 0.88)
        return board.boardRatio >= 0.22 &&
            opponentHero.vividRatio >= 0.12 &&
            playerHero.vividRatio >= 0.12
    }

    private data class RegionColorRatios(val boardRatio: Double, val vividRatio: Double, val goldRatio: Double)

    private fun colorRatios(
        image: BufferedImage,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): RegionColorRatios {
        val x0 = (image.width * left).toInt().coerceIn(0, image.width)
        val x1 = (image.width * right).toInt().coerceIn(x0, image.width)
        val y0 = (image.height * top).toInt().coerceIn(0, image.height)
        val y1 = (image.height * bottom).toInt().coerceIn(y0, image.height)
        var boardPixels = 0
        var vividPixels = 0
        var goldPixels = 0
        var samples = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val rgb = image.getRGB(x, y)
                val red = rgb shr 16 and 0xff
                val green = rgb shr 8 and 0xff
                val blue = rgb and 0xff
                val maximum = maxOf(red, green, blue)
                val minimum = minOf(red, green, blue)
                if (red in 80..220 && green in 65..205 && blue in 25..165 &&
                    red >= green * 0.88 && green >= blue * 0.82 && red - blue >= 15
                ) {
                    boardPixels++
                }
                if (maximum >= 90 && maximum - minimum >= 45) vividPixels++
                if (red >= 130 && green >= 75 && red > green * 1.12 && green > blue * 1.15) goldPixels++
                samples++
                x += 3
            }
            y += 3
        }
        if (samples == 0) return RegionColorRatios(0.0, 0.0, 0.0)
        return RegionColorRatios(
            boardRatio = boardPixels.toDouble() / samples,
            vividRatio = vividPixels.toDouble() / samples,
            goldRatio = goldPixels.toDouble() / samples,
        )
    }

    private fun classify(ocrText: String): ScreenWatchdogKind {
        val text = ocrText.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        if (text.isBlank()) return ScreenWatchdogKind.UNKNOWN

        val hasContinue = text.contains("点击继续") ||
            text.contains("继续") ||
            text.contains("continue")
        if ((text.contains("胜利") || text.contains("获胜") || text.contains("victory")) && hasContinue) {
            return ScreenWatchdogKind.WIN
        }
        if ((text.contains("失败") || text.contains("败北") || text.contains("defeat") || text.contains("lost")) && hasContinue) {
            return ScreenWatchdogKind.LOST
        }
        if (ScreenStateRecovery.looksLikeResultText(text) ||
            text.contains("本局结果") ||
            text.contains("对战结束")
        ) {
            return ScreenWatchdogKind.RESULT
        }
        val surrenderLabel = text.contains("投降") || text.contains("surrender") || text.contains("concede")
        val confirmation = text.contains("确定") || text.contains("确认") ||
            text.contains("confirm") || text.contains("areyousure") || text.contains("yes")
        val cancellation = text.contains("取消") || text.contains("cancel")
        if (surrenderLabel && confirmation && cancellation) {
            return ScreenWatchdogKind.SURRENDER_CONFIRMATION
        }
        val settingsLabel = text.contains("设置") || text.contains("选项") ||
            text.contains("settings") || text.contains("options")
        if (settingsLabel && surrenderLabel) return ScreenWatchdogKind.SETTINGS
        if (ScreenStateRecovery.looksLikeMatchmakingText(text)) {
            return ScreenWatchdogKind.MATCHMAKING
        }
        if (text.contains("旅店通票") ||
            text.contains("我的收藏") ||
            text.contains("商店") && text.contains("任务") ||
            text.contains("选择模式") ||
            text.contains("狂野对战") ||
            text.contains("标准对战") ||
            text.contains("选择套牌")
        ) {
            return ScreenWatchdogKind.MAIN_MENU
        }
        if (text.contains("结束回合") ||
            text.contains("你的回合") ||
            text.contains("对手回合") ||
            text.contains("敌方回合") ||
            text.contains("法力水晶") ||
            text.contains("攻击") && text.contains("英雄")
        ) {
            return ScreenWatchdogKind.GAMEPLAY
        }
        return ScreenWatchdogKind.UNKNOWN
    }

    private fun decide(
        kind: ScreenWatchdogKind,
        activeGameplay: Boolean = false,
    ): ScreenWatchdogRecoveryAction = when (kind) {
        ScreenWatchdogKind.WIN -> ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECORD_WIN
        ScreenWatchdogKind.LOST -> ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECORD_LOSS
        ScreenWatchdogKind.RESULT -> ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_CLEAR_RESULT
        ScreenWatchdogKind.MATCHMAKING -> ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECOVER_MATCHMAKING
        ScreenWatchdogKind.MAIN_MENU -> ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECOVER_MAIN_MENU
        ScreenWatchdogKind.GAMEPLAY -> ScreenWatchdogRecoveryAction.CONTINUE_ACTION
        ScreenWatchdogKind.MULLIGAN -> ScreenWatchdogRecoveryAction.CONTINUE_ACTION
        ScreenWatchdogKind.SETTINGS,
        ScreenWatchdogKind.SURRENDER_CONFIRMATION,
        ScreenWatchdogKind.UNKNOWN,
        ScreenWatchdogKind.CAPTURE_FAILED,
        -> if (activeGameplay) {
            ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RESUME_GAMEPLAY
        } else {
            ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_CONTINUE_UNKNOWN
        }
    }

    private fun isAuthoritativeActiveGameplay(state: String): Boolean {
        val fields = state.lowercase(Locale.ROOT)
            .split('|', ';', ' ', ',')
            .filter { it.isNotBlank() }
            .toSet()
        return fields.contains("mode=gameplay") &&
            fields.contains("inwar=true") &&
            fields.contains("warphase=game_turn") &&
            fields.contains("myturn=true")
    }

    private fun captureScreen(): BufferedImage? = captureCurrentGameWindow()

    /** Mandatory rank recovery is a narrowly gated exception to the optional general Beta recovery switch. */
    private fun captureMandatoryRankRecoveryScreen(): BufferedImage? = captureCurrentGameWindow()

    private fun captureCurrentGameWindow(): BufferedImage? {
        val hwnd = ScriptStatus.gameHWND
        if (hwnd == null || !GameUtil.isAliveOfGame() || !GameUtil.isVerifiedCurrentGameWindow(hwnd)) {
            log.warn { "SCREEN_WATCHDOG_CAPTURE_REJECTED reason=current-game-window-unverified hwnd=${hwnd ?: "none"}" }
            return null
        }
        val captureResult = MouseUtil.withRecoveryForeground(hwnd) {
            val current = ScriptStatus.gameHWND
                ?.takeIf { it.toString() == hwnd.toString() }
                ?.takeIf(GameUtil::isVerifiedCurrentGameWindow)
            if (current == null) {
                null
            } else {
                ScreenRecoveryWindowCapture.capture(current)?.image
            }
        }
        if (!captureResult.foregroundConfirmed || captureResult.value == null) {
            log.warn {
                "SCREEN_WATCHDOG_CAPTURE_REJECTED reason=${if (captureResult.foregroundConfirmed) "pixel-authority-failed" else "foreground-unconfirmed"} " +
                    "hwnd=$hwnd"
            }
            return null
        }
        return captureResult.value
    }

    private fun runOCR(image: BufferedImage): String {
        val ocrImage = resizeForOcr(image)
        return Tesseract().apply {
            setDatapath(File(TESS_DATA_PATH).absolutePath)
            setLanguage(CHI_SIM_DATA)
            setPageSegMode(11)
            setVariable("user_defined_dpi", "160")
        }.doOCR(ocrImage)
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

    private fun sanitize(value: String): String = value
        .replace(Regex("\\s+"), "_")
        .replace(Regex("[^A-Za-z0-9._:/,@=+\\-\\u4e00-\\u9fff]"), "_")
}
