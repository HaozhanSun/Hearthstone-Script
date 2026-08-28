package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.TesseractEx
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.consts.CHI_SIM_DATA
import club.xiaojiawei.hsscript.consts.TESS_DATA_PATH
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.strategy.mode.TournamentModeStrategy
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
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
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

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

    private const val MAX_OCR_TEXT_LENGTH = 500
    private const val OCR_MAX_WIDTH = 1280
    private const val RESULT_CONTINUE_GRAY_LIGHT_MIN = 0.025
    private const val RESULT_BANNER_LOW_SATURATION_MIN = 0.30
    private const val RECONNECT_RETRY_INTERVAL_MS = 60_000L
    private val reconnectAttemptAt = AtomicLong(0L)

    private enum class ScreenKind(val code: String) {
        DECK_SELECTION("DECK_SELECTION"),
        HOME("HOME"),
        TOURNAMENT("TOURNAMENT"),
        MATCHMAKING("MATCHMAKING"),
        RESULT("RESULT"),
        RECONNECT("RECONNECT"),
        LOGIN("LOGIN"),
        GAME_MODE("GAME_MODE"),
        COLLECTION("COLLECTION"),
        PACK_OPENING("PACK_OPENING"),
    }

    private data class Capture(
        val image: BufferedImage,
        val bounds: Rectangle,
        val file: File?,
        val visual: VisualSignature,
    )

    private data class VisualSignature(
        val sampleHash: Long,
        val warmRatio: Double,
        val blueRatio: Double,
        val resultContinueGrayLightRatio: Double,
        val resultBannerLowSaturationRatio: Double,
    ) {
        override fun toString(): String =
            "hash=${java.lang.Long.toUnsignedString(sampleHash, 16)} " +
                "warmRatio=${"%.3f".format(Locale.ROOT, warmRatio)} " +
                "blueRatio=${"%.3f".format(Locale.ROOT, blueRatio)} " +
                "resultContinueGrayLight=${"%.3f".format(Locale.ROOT, resultContinueGrayLightRatio)} " +
                "resultBannerLowSaturation=${"%.3f".format(Locale.ROOT, resultBannerLowSaturationRatio)}"
    }

    private data class RegionSignal(
        val grayLightRatio: Double,
        val lowSaturationRatio: Double,
    )

    private data class Detection(
        val kind: ScreenKind,
        val mode: ModeEnum,
        val confidence: Int,
        val evidence: String,
    )

    /**
     * Inspect the visible client and, if possible, move the state machine to
     * the detected screen. Returns true only when a state/action was applied.
     */
    fun inspectAndRecover(
        stuckForMs: Long,
        stateFingerprint: String,
        stateStillCurrent: () -> Boolean = { true },
    ): Boolean {
        if (!WorkTimeListener.working || PauseStatus.isPause || WarEx.inWar) {
            log.info {
                "SCREEN_RECOVERY_SKIPPED reason=unsafe " +
                    "working=${WorkTimeListener.working} paused=${PauseStatus.isPause} inWar=${WarEx.inWar}"
            }
            return false
        }

        val capture = captureScreen()
        if (capture == null) {
            log.warn {
                "SCREEN_RECOVERY_FAILED reason=capture-null stuckForMs=$stuckForMs " +
                    "state=$stateFingerprint"
            }
            return false
        }

        log.warn {
            "SCREEN_RECOVERY_TRIGGER stuckForMs=$stuckForMs state=$stateFingerprint " +
                "bounds=${capture.bounds} screenshot=${capture.file?.absolutePath ?: "not-saved"} " +
                "screenshotLink=${capture.file?.toURI()?.toString() ?: "none"} " +
                "visual=${capture.visual}"
        }

        val ocrText = runOCR(capture.image)
        if (!stateStillCurrent()) {
            log.info { "SCREEN_RECOVERY_SKIPPED reason=state-changed-during-inspection state=$stateFingerprint" }
            return false
        }
        val detection = detect(ocrText, capture.visual)
        log.info {
            "SCREEN_RECOVERY_OBSERVATION " +
                "ocr=${ocrText.ifBlank { "<empty>" }.take(MAX_OCR_TEXT_LENGTH)} " +
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
                    "screenshot=${capture.file?.absolutePath ?: "not-saved"} " +
                    "screenshotLink=${capture.file?.toURI()?.toString() ?: "none"} " +
                    "unknownStateScreenshot=${evidence?.file?.absolutePath ?: "not-saved"} " +
                    "unknownStateScreenshotLink=${evidence?.link ?: "none"}"
            }
            return false
        }

        if (!stateStillCurrent()) {
            log.info { "SCREEN_RECOVERY_SKIPPED reason=state-changed-before-apply state=$stateFingerprint" }
            return false
        }
        return apply(detection)
    }

    private fun captureScreen(): Capture? = runCatching {
        if (GraphicsEnvironment.isHeadless()) return null
        val allScreens = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
            .fold(Rectangle()) { all, next -> all.union(next) }
        if (allScreens.width <= 0 || allScreens.height <= 0) return null

        // GAME_RECT is the most useful crop when the game is windowed. If it
        // is not initialized yet, use the whole desktop so startup recovery
        // still has a chance to inspect an already-open client.
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
        val image = try {
            Robot().createScreenCapture(bounds)
        } catch (_: Exception) {
            Robot().createScreenCapture(allScreens)
        }
        val saved = DebugScreenshotRing.save(image, "screen-recovery", "stale-screen")
        val file = saved?.file
        Capture(image, bounds, file, visualSignature(image))
    }.getOrElse { error ->
        log.warn(error) { "SCREEN_RECOVERY_FAILED reason=capture-exception" }
        null
    }

    private fun runOCR(image: BufferedImage): String {
        val tessData = File(TESS_DATA_PATH)
        val chiSim = File(tessData, "$CHI_SIM_DATA.traineddata")
        if (!chiSim.isFile) {
            log.info { "SCREEN_RECOVERY_OCR_SKIPPED reason=missing-tessdata path=${chiSim.absolutePath}" }
            return ""
        }
        return runCatching {
            val ocrImage = resizeForOcr(image)
            TesseractEx().apply {
                setDatapath(tessData.absolutePath)
                setLanguage(CHI_SIM_DATA)
                setPageSegMode(11)
                setVariable("user_defined_dpi", "160")
            }.doOCR(ocrImage, "screen-recovery")
                .replace(Regex("\\s+"), "")
        }.getOrElse { error ->
            log.warn(error) { "SCREEN_RECOVERY_OCR_FAILED" }
            ""
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
        return VisualSignature(
            sampleHash = hash,
            warmRatio = if (samples == 0) 0.0 else warm.toDouble() / samples,
            blueRatio = if (samples == 0) 0.0 else blue.toDouble() / samples,
            resultContinueGrayLightRatio = continueSignal.grayLightRatio,
            resultBannerLowSaturationRatio = bannerSignal.lowSaturationRatio,
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
                samples++
                x += 2
            }
            y += 2
        }
        if (samples == 0) return RegionSignal(0.0, 0.0)
        return RegionSignal(
            grayLightRatio = grayLight.toDouble() / samples,
            lowSaturationRatio = lowSaturation.toDouble() / samples,
        )
    }

    private fun detect(ocrText: String, visual: VisualSignature): Detection? {
        val text = ocrText.lowercase(Locale.ROOT)
        fun has(vararg terms: String): Boolean = terms.all { text.contains(it) }

        // More specific screens must win before generic home/login words.
        if (text.contains("选择套牌") || has("套牌", "狂野对战")) {
            return Detection(ScreenKind.DECK_SELECTION, ModeEnum.TOURNAMENT, 100, "deck-selection-title")
        }
        if (looksLikeResultText(text)) {
            return Detection(ScreenKind.RESULT, ModeEnum.GAMEPLAY, 95, "result-text")
        }
        if (looksLikeResultVisual(visual.resultContinueGrayLightRatio, visual.resultBannerLowSaturationRatio)) {
            return Detection(ScreenKind.RESULT, ModeEnum.GAMEPLAY, 92, "result-fixed-continue-visual")
        }
        if (has("寻找对手") || has("正在匹配") || has("取消匹配")) {
            return Detection(ScreenKind.MATCHMAKING, ModeEnum.TOURNAMENT, 95, "matchmaking-text")
        }
        if (text.contains("我的收藏") || text.contains("收藏管理")) {
            return Detection(ScreenKind.COLLECTION, ModeEnum.COLLECTIONMANAGER, 95, "collection-text")
        }
        if (text.contains("开包") || text.contains("卡牌包")) {
            return Detection(ScreenKind.PACK_OPENING, ModeEnum.PACKOPENING, 95, "pack-opening-text")
        }
        if (looksLikeReconnectText(text)) {
            return Detection(ScreenKind.RECONNECT, ModeEnum.LOGIN, 96, "reconnect-disconnected-text")
        }
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

    private fun shouldAttemptReconnect(now: Long): Boolean {
        while (true) {
            val previous = reconnectAttemptAt.get()
            if (previous > 0L && now - previous < RECONNECT_RETRY_INTERVAL_MS) return false
            if (reconnectAttemptAt.compareAndSet(previous, now)) return true
        }
    }

    private fun apply(detection: Detection): Boolean {
        if (WarEx.inWar) {
            log.warn { "SCREEN_RECOVERY_SKIPPED reason=war-started detected=${detection.kind.code}" }
            return false
        }

        when (detection.kind) {
            ScreenKind.DECK_SELECTION -> {
                if (DeckStrategyManager.currentDeckStrategy == null ||
                    DeckStrategyManager.currentRunMode == null
                ) {
                    log.warn { "SCREEN_RECOVERY_DECK_SELECTION_SKIPPED reason=no-selected-strategy" }
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
                GameUtil.dismissStaleGameEndScreen()
            }

            ScreenKind.MATCHMAKING -> {
                Mode.recover(ModeEnum.TOURNAMENT, "visible-matchmaking-screen", enterStrategy = false)
                log.info { "SCREEN_RECOVERY_APPLIED screen=MATCHMAKING action=WAIT_FOR_GAMEPLAY" }
            }

            ScreenKind.RECONNECT -> {
                Mode.recover(ModeEnum.LOGIN, "visible-reconnect-screen", enterStrategy = false)
                val now = System.currentTimeMillis()
                if (shouldAttemptReconnect(now)) {
                    log.warn {
                        "SCREEN_RECOVERY_APPLIED screen=RECONNECT mode=LOGIN " +
                            "action=CLICK_RECONNECT retryIntervalMs=$RECONNECT_RETRY_INTERVAL_MS"
                    }
                    EXTRA_THREAD_POOL.schedule({
                        if (WorkTimeListener.working && !PauseStatus.isPause &&
                            !WarEx.inWar && Mode.currMode == ModeEnum.LOGIN
                        ) {
                            // This is the upstream reconnect input primitive;
                            // skip the pre-click right-click because the
                            // offline page is not a card/targeting state.
                            GameUtil.RECONNECT_RECT.lClick(false)
                            log.info { "SCREEN_RECOVERY_RECONNECT_DISPATCHED" }
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
}
