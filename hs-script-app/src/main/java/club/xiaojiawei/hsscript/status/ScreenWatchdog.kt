package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.TesseractEx
import club.xiaojiawei.hsscript.consts.CHI_SIM_DATA
import club.xiaojiawei.hsscript.consts.TESS_DATA_PATH
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class ScreenWatchdogKind {
    WIN,
    LOST,
    RESULT,
    MATCHMAKING,
    MAIN_MENU,
    GAMEPLAY,
    UNKNOWN,
    CAPTURE_FAILED,
}

enum class ScreenWatchdogRecoveryAction {
    CONTINUE_ACTION,
    STOP_SURRENDER_AND_RECORD_WIN,
    STOP_SURRENDER_AND_RECORD_LOSS,
    STOP_SURRENDER_AND_CLEAR_RESULT,
    STOP_SURRENDER_AND_RECOVER_MATCHMAKING,
    STOP_SURRENDER_AND_RECOVER_MAIN_MENU,
    STOP_SURRENDER_AND_PAUSE_UNKNOWN,
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
        val runId = System.getProperty("hs.script.e2e.run-id", "normal")
        val provider = OcrRuntime.currentProvider().name
        val image = runCatching { captureProvider() }.getOrElse { error ->
            log.warn(error) {
                "SCREEN_WATCHDOG_CAPTURE_FAILED runId=$runId trigger=$trigger state=$state attempts=$attempts"
            }
            null
        }
        if (image == null) {
            return ScreenWatchdogObservation(
                kind = ScreenWatchdogKind.CAPTURE_FAILED,
                action = ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_PAUSE_UNKNOWN,
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
                "provider=$provider path=${evidence?.file?.absolutePath ?: "not-saved"}"
        }

        val ocrText = runCatching { ocrProvider(image).replace(Regex("\\s+"), "") }.getOrElse { error ->
            log.warn(error) {
                "SCREEN_WATCHDOG_OCR_FAILED runId=$runId provider=$provider trigger=$trigger " +
                    "screenshot=${evidence?.file?.absolutePath ?: "not-saved"}"
            }
            ""
        }
        val kind = classify(ocrText)
        val action = decide(kind)
        log.warn {
            "SCREEN_WATCHDOG_OCR runId=$runId provider=$provider kind=$kind action=$action " +
                "chars=${ocrText.length} screenshot=${evidence?.file?.absolutePath ?: "not-saved"} " +
                "ocr=${sanitize(ocrText).take(240).ifBlank { "<empty>" }}"
        }
        return ScreenWatchdogObservation(
            kind = kind,
            action = action,
            ocrText = ocrText,
            screenshotPath = evidence?.file?.absolutePath,
            provider = provider,
            reason = "ocr-classified",
        )
    }

    internal fun classifyForTest(ocrText: String): ScreenWatchdogKind = classify(ocrText)

    internal fun decideForTest(kind: ScreenWatchdogKind): ScreenWatchdogRecoveryAction = decide(kind)

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

    private fun decide(kind: ScreenWatchdogKind): ScreenWatchdogRecoveryAction = when (kind) {
        ScreenWatchdogKind.WIN -> ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECORD_WIN
        ScreenWatchdogKind.LOST -> ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECORD_LOSS
        ScreenWatchdogKind.RESULT -> ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_CLEAR_RESULT
        ScreenWatchdogKind.MATCHMAKING -> ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECOVER_MATCHMAKING
        ScreenWatchdogKind.MAIN_MENU -> ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECOVER_MAIN_MENU
        ScreenWatchdogKind.GAMEPLAY -> ScreenWatchdogRecoveryAction.CONTINUE_ACTION
        ScreenWatchdogKind.UNKNOWN,
        ScreenWatchdogKind.CAPTURE_FAILED,
        -> ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_PAUSE_UNKNOWN
    }

    private fun captureScreen(): BufferedImage? = runCatching {
        if (GraphicsEnvironment.isHeadless()) return null
        val allScreens = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
            .fold(Rectangle()) { all, next -> all.union(next) }
        if (allScreens.width <= 0 || allScreens.height <= 0) return null
        val gameRect = ScriptStatus.GAME_RECT
        val candidate = if (gameRect.right - gameRect.left >= 400 &&
            gameRect.bottom - gameRect.top >= 300
        ) {
            Rectangle(
                gameRect.left,
                gameRect.top,
                gameRect.right - gameRect.left,
                gameRect.bottom - gameRect.top,
            )
        } else {
            allScreens
        }
        val bounds = candidate.intersection(allScreens)
        if (bounds.width < 400 || bounds.height < 300) return null
        Robot().createScreenCapture(bounds)
    }.getOrElse { error ->
        log.warn(error) { "SCREEN_WATCHDOG_CAPTURE_FAILED reason=capture-exception" }
        null
    }

    private fun runOCR(image: BufferedImage): String {
        val ocrImage = resizeForOcr(image)
        return TesseractEx().apply {
            setDatapath(File(TESS_DATA_PATH).absolutePath)
            setLanguage(CHI_SIM_DATA)
            setPageSegMode(11)
            setVariable("user_defined_dpi", "160")
        }.doOCR(ocrImage, "screen-watchdog")
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
