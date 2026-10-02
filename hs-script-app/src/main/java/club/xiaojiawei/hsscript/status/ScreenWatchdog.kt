package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.consts.CHI_SIM_DATA
import club.xiaojiawei.hsscript.consts.TESS_DATA_PATH
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.ocr.PaddleXOcrCancelledException
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscriptbase.config.log
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
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
    ): TimingDecision {
        if (!ConfigUtil.getBoolean(ConfigEnum.SCREEN_WATCHDOG_ENABLED)) {
            return TimingDecision(false, "disabled")
        }
        val stuckFor = now - startedAt
        val retryExceeded = attempts >= maxRetries.coerceAtLeast(1)
        val stuckExceeded = stuckFor >= stuckMs.coerceAtLeast(0L)
        if (!retryExceeded && !stuckExceeded) {
            return TimingDecision(false, "below-threshold stuckForMs=$stuckFor attempts=$attempts")
        }
        val previous = lastCaptureAt.get()
        if (previous > 0L && now - previous < cooldownMs.coerceAtLeast(0L)) {
            return TimingDecision(false, "cooldown remainingMs=${cooldownMs - (now - previous)}")
        }
        if (!lastCaptureAt.compareAndSet(previous, now)) {
            return TimingDecision(false, "in-flight")
        }
        return TimingDecision(true, "threshold stuckForMs=$stuckFor attempts=$attempts")
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
        val settingsVisual = settingsOverlayMetrics(image).toString()
        val mulliganVisual = if (state.contains("warPhase=REPLACE_CARD", ignoreCase = true)) {
            mulliganVisualMetrics(image).toString()
        } else {
            "not-applicable"
        }
        val reason = when (kind) {
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

    private fun classifyForSurrender(
        ocrKind: ScreenWatchdogKind,
        state: String,
        image: BufferedImage,
    ): ScreenWatchdogKind {
        // Terminal and confirmation OCR is authoritative and must never be
        // displaced by a settings-looking patch elsewhere in the frame.
        if (ocrKind in setOf(
                ScreenWatchdogKind.WIN,
                ScreenWatchdogKind.LOST,
                ScreenWatchdogKind.RESULT,
                ScreenWatchdogKind.SURRENDER_CONFIRMATION,
            )
        ) return ocrKind
        if (ocrKind == ScreenWatchdogKind.SETTINGS) return ocrKind

        // OCR over the live mulligan cards can continue returning the
        // background phase after Settings opens. Override that stale phase
        // only when the centered three-button panel is positively visible.
        if (settingsOverlayMetrics(image).accepted) return ScreenWatchdogKind.SETTINGS
        if (ocrKind != ScreenWatchdogKind.UNKNOWN) return ocrKind
        if (isAuthoritativeActiveGameplay(state) && hasActiveGameplayVisual(image)) {
            return ScreenWatchdogKind.GAMEPLAY
        }
        if (isAuthoritativeMulliganInput(state) && hasMulliganVisual(image)) {
            return ScreenWatchdogKind.MULLIGAN
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
        val accepted: Boolean,
    ) {
        override fun toString(): String =
            "size=${width}x$height bannerGold=${"%.4f".format(Locale.ROOT, bannerGold)} " +
                "bannerVivid=${"%.4f".format(Locale.ROOT, bannerVivid)} " +
                "handVivid=${"%.4f".format(Locale.ROOT, handVivid)} " +
                "heroVivid=${"%.4f".format(Locale.ROOT, heroVivid)} accepted=$accepted"
    }

    private fun hasMulliganVisual(image: BufferedImage): Boolean = mulliganVisualMetrics(image).accepted

    private data class SettingsOverlayMetrics(
        val width: Int,
        val height: Int,
        val headerBeige: Double,
        val surrenderRed: Double,
        val optionsBeige: Double,
        val exitBeige: Double,
        val accepted: Boolean,
    ) {
        override fun toString(): String =
            "size=${width}x$height headerBeige=${"%.4f".format(Locale.ROOT, headerBeige)} " +
                "surrenderRed=${"%.4f".format(Locale.ROOT, surrenderRed)} " +
                "optionsBeige=${"%.4f".format(Locale.ROOT, optionsBeige)} " +
                "exitBeige=${"%.4f".format(Locale.ROOT, exitBeige)} accepted=$accepted"
    }

    /** Three small, fixed menu-button ROIs; broad Hearthstone colors alone never confirm Settings. */
    private fun settingsOverlayMetrics(image: BufferedImage): SettingsOverlayMetrics {
        if (image.width < 800 || image.height < 450) {
            return SettingsOverlayMetrics(image.width, image.height, 0.0, 0.0, 0.0, 0.0, false)
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
        val accepted = headerBeige >= 0.12 && surrenderRed >= 0.10 &&
            optionsBeige >= 0.10 && exitBeige >= 0.10
        return SettingsOverlayMetrics(
            image.width,
            image.height,
            headerBeige,
            surrenderRed,
            optionsBeige,
            exitBeige,
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
            return MulliganVisualMetrics(image.width, image.height, 0.0, 0.0, 0.0, 0.0, false)
        }
        val aspect = image.width.toDouble() / image.height
        if (aspect !in 1.55..1.90) {
            return MulliganVisualMetrics(image.width, image.height, 0.0, 0.0, 0.0, 0.0, false)
        }
        // Require the distinctive start-hand banner, a vivid row of cards,
        // and the local hero portrait; phase evidence alone never clicks.
        val banner = colorRatios(image, 0.35, 0.09, 0.66, 0.24)
        val hand = colorRatios(image, 0.20, 0.30, 0.80, 0.68)
        val hero = colorRatios(image, 0.455, 0.68, 0.545, 0.88)
        val accepted = banner.goldRatio >= 0.025 && banner.vividRatio >= 0.10 &&
            hand.vividRatio >= 0.24 && hero.vividRatio >= 0.12
        return MulliganVisualMetrics(
            image.width,
            image.height,
            banner.goldRatio,
            banner.vividRatio,
            hand.vividRatio,
            hero.vividRatio,
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

    private fun captureScreen(): BufferedImage? = captureScreen(
        preferCurrentGameWindow = ScreenRecoveryRuntime.isEnabled(),
    )

    /** Mandatory rank recovery is a narrowly gated exception to the optional general Beta recovery switch. */
    private fun captureMandatoryRankRecoveryScreen(): BufferedImage? = captureScreen(preferCurrentGameWindow = true)

    private fun captureScreen(preferCurrentGameWindow: Boolean): BufferedImage? = runCatching {
        if (GraphicsEnvironment.isHeadless()) return null
        val allScreens = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
            .fold(Rectangle()) { all, next -> all.union(next) }
        if (allScreens.width <= 0 || allScreens.height <= 0) return null
        val bounds = gameBounds(allScreens, preferCurrentGameWindow)
            ?: run {
                log.info { "SCREEN_WATCHDOG_CAPTURE_SKIPPED reason=game-bounds-unknown" }
                return null
            }
        if (bounds.width < 400 || bounds.height < 300) return null
        Robot().createScreenCapture(bounds)
    }.getOrElse { error ->
        log.warn(error) { "SCREEN_WATCHDOG_CAPTURE_FAILED reason=capture-exception" }
        null
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

    /** Resolve only the Hearthstone client bounds; never OCR the whole desktop. */
    private fun gameBounds(
        allScreens: Rectangle,
        preferCurrentGameWindow: Boolean = ScreenRecoveryRuntime.isEnabled(),
    ): Rectangle? {
        val gameRect = ScriptStatus.GAME_RECT
        val cachedGameBounds = if (gameRect.right - gameRect.left >= 400 && gameRect.bottom - gameRect.top >= 300) {
            Rectangle(
                gameRect.left,
                gameRect.top,
                gameRect.right - gameRect.left,
                gameRect.bottom - gameRect.top,
            )
        } else {
            null
        }
        val hwnd = ScriptStatus.gameHWND
        val windowRect = WinDef.RECT()
        val currentWindowBounds = hwnd
            ?.takeIf { User32.INSTANCE.IsWindow(it) }
            ?.takeIf { User32.INSTANCE.GetWindowRect(it, windowRect) }
            ?.let {
                Rectangle(
                    windowRect.left,
                    windowRect.top,
                    windowRect.right - windowRect.left,
                    windowRect.bottom - windowRect.top,
                )
            }
        return ScreenWatchdogCaptureBoundsPolicy.select(
            cachedGameBounds = cachedGameBounds,
            currentWindowBounds = currentWindowBounds,
            desktopBounds = allScreens,
            betaExtensionsEnabled = preferCurrentGameWindow,
        )
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("\\s+"), "_")
        .replace(Regex("[^A-Za-z0-9._:/,@=+\\-\\u4e00-\\u9fff]"), "_")
}
