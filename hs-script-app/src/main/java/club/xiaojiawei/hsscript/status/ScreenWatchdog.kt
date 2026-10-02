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
        captureProvider: () -> BufferedImage? = ::captureScreen,
        ocrProvider: (BufferedImage) -> String = ::runOCR,
    ): ScreenWatchdogObservation {
        // Keep the established watchdog as a separate, rename-only upstream
        // implementation.  The additive Beta capture/state heuristics must
        // not leak into a runtime with the Beta extension switch turned off.
        if (!ScreenRecoveryRuntime.isEnabled()) {
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
        val kind = classifyForSurrender(ocrKind, activeGameplay, image)
        val visualGameplayFallback = ocrKind == ScreenWatchdogKind.UNKNOWN && kind == ScreenWatchdogKind.GAMEPLAY
        val reason = if (visualGameplayFallback) "authoritative-gameplay-and-fresh-board-visual" else "ocr-classified"
        val action = decide(kind, activeGameplay)
        log.warn {
            "SCREEN_WATCHDOG_OCR runId=$runId provider=$providerUsed kind=$kind action=$action " +
                "activeGameplay=$activeGameplay chars=${ocrText.length} " +
                "visualFallback=$visualGameplayFallback " +
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
    ): ScreenWatchdogKind = classifyForSurrender(classify(ocrText), isAuthoritativeActiveGameplay(state), image)

    private fun classifyForSurrender(
        ocrKind: ScreenWatchdogKind,
        activeGameplay: Boolean,
        image: BufferedImage,
    ): ScreenWatchdogKind = if (
        ocrKind == ScreenWatchdogKind.UNKNOWN && activeGameplay && hasActiveGameplayVisual(image)
    ) {
        ScreenWatchdogKind.GAMEPLAY
    } else {
        ocrKind
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

    private data class RegionColorRatios(val boardRatio: Double, val vividRatio: Double)

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
                samples++
                x += 3
            }
            y += 3
        }
        if (samples == 0) return RegionColorRatios(0.0, 0.0)
        return RegionColorRatios(
            boardRatio = boardPixels.toDouble() / samples,
            vividRatio = vividPixels.toDouble() / samples,
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

    private fun captureScreen(): BufferedImage? = runCatching {
        if (GraphicsEnvironment.isHeadless()) return null
        val allScreens = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
            .fold(Rectangle()) { all, next -> all.union(next) }
        if (allScreens.width <= 0 || allScreens.height <= 0) return null
        val bounds = gameBounds(allScreens)
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
    private fun gameBounds(allScreens: Rectangle): Rectangle? {
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
            betaExtensionsEnabled = ScreenRecoveryRuntime.isEnabled(),
        )
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("\\s+"), "_")
        .replace(Regex("[^A-Za-z0-9._:/,@=+\\-\\u4e00-\\u9fff]"), "_")
}
