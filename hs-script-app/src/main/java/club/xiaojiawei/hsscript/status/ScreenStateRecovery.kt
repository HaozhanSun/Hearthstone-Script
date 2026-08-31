package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.TesseractEx
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.consts.CHI_SIM_DATA
import club.xiaojiawei.hsscript.consts.TESS_DATA_PATH
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.strategy.mode.LoginModeStrategy
import club.xiaojiawei.hsscript.strategy.mode.TournamentModeStrategy
import club.xiaojiawei.hsscript.enums.WindowEnum
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.MouseUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscript.utils.WindowUtil
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javafx.application.Platform

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
    /**
     * Screen recovery can be requested by both startup probing and the
     * lifecycle monitor.  PaddleX launches a fresh Python process per call;
     * overlapping probes contend for the CPU and can turn a healthy OCR run
     * into a false 180-second timeout.  Keep the first probe authoritative and
     * fail the duplicate closed without starting a second sidecar.
     */
    private val ocrInFlight = OcrInFlightGate()
    private val reconnectAttemptAt = AtomicLong(0L)

    private enum class ScreenKind(val code: String) {
        DECK_SELECTION("DECK_SELECTION"),
        HOME("HOME"),
        TOURNAMENT("TOURNAMENT"),
        MATCHMAKING("MATCHMAKING"),
        RESULT("RESULT"),
        RECONNECT("RECONNECT"),
        RECONNECT_FAILURE("RECONNECT_FAILURE"),
        LOGIN("LOGIN"),
        GAME_MODE("GAME_MODE"),
        COLLECTION("COLLECTION"),
        PACK_OPENING("PACK_OPENING"),
        LOADING("LOADING"),
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
        val loadingCentralDarkRatio: Double,
        val resultContinueGrayLightRatio: Double,
        val resultBannerLowSaturationRatio: Double,
    ) {
        override fun toString(): String =
            "hash=${java.lang.Long.toUnsignedString(sampleHash, 16)} " +
                "warmRatio=${"%.3f".format(Locale.ROOT, warmRatio)} " +
                "blueRatio=${"%.3f".format(Locale.ROOT, blueRatio)} " +
                "loadingCentralDark=${"%.3f".format(Locale.ROOT, loadingCentralDarkRatio)} " +
                "resultContinueGrayLight=${"%.3f".format(Locale.ROOT, resultContinueGrayLightRatio)} " +
                "resultBannerLowSaturation=${"%.3f".format(Locale.ROOT, resultBannerLowSaturationRatio)}"
    }

    private data class RegionSignal(
        val grayLightRatio: Double,
        val lowSaturationRatio: Double,
        val darkRatio: Double,
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

        val ocrText = runOCRIfAvailable(capture.image, "screen-recovery") ?: return false
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
            PauseStatus.isPause = true
            log.warn {
                "SCREEN_RECOVERY_UNRESOLVED confidence=${detection?.confidence ?: 0} " +
                    "screenshot=${capture.file?.absolutePath ?: "not-saved"} " +
                    "screenshotLink=${capture.file?.toURI()?.toString() ?: "none"} " +
                    "unknownStateScreenshot=${evidence?.file?.absolutePath ?: "not-saved"} " +
                    "unknownStateScreenshotLink=${evidence?.link ?: "none"}"
            }
            log.error {
                "SCREEN_RECOVERY_PAUSE_ACTIVE reason=unresolved-ocr-or-visual-state " +
                    "ocrFailure=${ocrText.isBlank()} confidence=${detection?.confidence ?: 0}"
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
        val image = withMainOverlaySuppressed {
            try {
                Robot().createScreenCapture(bounds)
            } catch (_: Exception) {
                Robot().createScreenCapture(allScreens)
            }
        } ?: return null
        val saved = DebugScreenshotRing.save(image, "screen-recovery", "stale-screen")
        val file = saved?.file
        Capture(image, bounds, file, visualSignature(image))
    }.getOrElse { error ->
        log.warn(error) { "SCREEN_RECOVERY_FAILED reason=capture-exception" }
        null
    }

    /**
     * The main JavaFX window is deliberately always-on-top so its controls
     * remain usable while Hearthstone is full-screen.  A desktop Robot
     * capture therefore includes the script's own log panel whenever the
     * game is under it.  OCR then sees words such as "我的收藏" from that
     * panel and can steer recovery into the wrong screen.  Hide only that
     * overlay for the single capture and restore its prior visibility on the
     * FX thread.  If the transition cannot be confirmed, fail closed rather
     * than classify a contaminated screenshot.
     */
    private fun <T> withMainOverlaySuppressed(block: () -> T): T? {
        val stage = WindowUtil.getStage(WindowEnum.MAIN) ?: return block()
        val wasShowing = AtomicBoolean(false)
        val originalAlwaysOnTop = AtomicBoolean(false)
        val originalOpacity = AtomicReference(1.0)
        val suppressFailed = AtomicBoolean(false)
        val hidden = CountDownLatch(1)
        if (Platform.isFxApplicationThread()) {
            wasShowing.set(stage.isShowing)
            originalAlwaysOnTop.set(stage.isAlwaysOnTop)
            originalOpacity.set(stage.opacity)
            if (wasShowing.get()) {
                runCatching {
                    stage.isAlwaysOnTop = false
                    stage.opacity = 0.0
                    stage.hide()
                }
                    .onFailure {
                        suppressFailed.set(true)
                        log.warn(it) { "SCREEN_RECOVERY_OVERLAY_SUPPRESS_FAILED" }
                    }
            }
            if (suppressFailed.get()) return null
            return try {
                log.info { "SCREEN_RECOVERY_OVERLAY_SUPPRESSED showing=true" }
                block()
            } finally {
                if (wasShowing.get()) {
                    stage.opacity = originalOpacity.get()
                    stage.isAlwaysOnTop = originalAlwaysOnTop.get()
                    stage.show()
                }
            }
        }

        Platform.runLater {
            runCatching {
                wasShowing.set(stage.isShowing)
                originalAlwaysOnTop.set(stage.isAlwaysOnTop)
                originalOpacity.set(stage.opacity)
                if (wasShowing.get()) {
                    stage.isAlwaysOnTop = false
                    stage.opacity = 0.0
                    stage.hide()
                }
            }.onFailure { error ->
                suppressFailed.set(true)
                log.warn(error) { "SCREEN_RECOVERY_OVERLAY_SUPPRESS_FAILED" }
            }
            hidden.countDown()
        }
        if (!hidden.await(2, TimeUnit.SECONDS)) {
            log.warn { "SCREEN_RECOVERY_OVERLAY_SUPPRESS_FAILED reason=fx-timeout" }
            return null
        }
        if (suppressFailed.get()) return null
        if (wasShowing.get()) Thread.sleep(250)

        return try {
            if (wasShowing.get()) {
                log.info { "SCREEN_RECOVERY_OVERLAY_SUPPRESSED showing=true" }
            }
            block()
        } finally {
            if (wasShowing.get()) {
                val restored = CountDownLatch(1)
                Platform.runLater {
                    runCatching {
                        stage.opacity = originalOpacity.get()
                        stage.isAlwaysOnTop = originalAlwaysOnTop.get()
                        stage.show()
                    }
                        .onFailure { error -> log.warn(error) { "SCREEN_RECOVERY_OVERLAY_RESTORE_FAILED" } }
                    restored.countDown()
                }
                if (!restored.await(2, TimeUnit.SECONDS)) {
                    log.warn { "SCREEN_RECOVERY_OVERLAY_RESTORE_FAILED reason=fx-timeout" }
                }
            }
        }
    }

    private fun runOCR(image: BufferedImage): String {
        val tessData = File(TESS_DATA_PATH)
        val chiSim = File(tessData, "$CHI_SIM_DATA.traineddata")
        if (OcrRuntime.isLegacySelected() && !chiSim.isFile) {
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

    private fun runOCRIfAvailable(image: BufferedImage, purpose: String): String? {
        if (!ocrInFlight.tryAcquire()) {
            log.info { "SCREEN_RECOVERY_SKIPPED reason=ocr-in-flight purpose=$purpose" }
            return null
        }
        return try {
            runOCR(image)
        } finally {
            ocrInFlight.release()
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
        return VisualSignature(
            sampleHash = hash,
            warmRatio = if (samples == 0) 0.0 else warm.toDouble() / samples,
            blueRatio = if (samples == 0) 0.0 else blue.toDouble() / samples,
            loadingCentralDarkRatio = loadingCenterSignal.darkRatio,
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
        var dark = 0
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
                samples++
                x += 2
            }
            y += 2
        }
        if (samples == 0) return RegionSignal(0.0, 0.0, 0.0)
        return RegionSignal(
            grayLightRatio = grayLight.toDouble() / samples,
            lowSaturationRatio = lowSaturation.toDouble() / samples,
            darkRatio = dark.toDouble() / samples,
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
        // The live client uses "搜寻对手" while some localized/client builds
        // use "寻找对手". OCR also commonly separates the cancel label, so
        // accept both forms but require a matchmaking-specific phrase.
        if (looksLikeMatchmakingText(text)) {
            return Detection(ScreenKind.MATCHMAKING, ModeEnum.TOURNAMENT, 95, "matchmaking-text")
        }
        if (looksLikeCollectionText(text)) {
            return Detection(ScreenKind.COLLECTION, ModeEnum.COLLECTIONMANAGER, 95, "collection-text")
        }
        // Reward pages advertise unopened packs too.  A bare "卡牌包" is
        // therefore not evidence that the pack-opening scene is visible.
        if (looksLikePackOpeningText(text)) {
            return Detection(ScreenKind.PACK_OPENING, ModeEnum.PACKOPENING, 95, "pack-opening-text")
        }
        // This must precede the generic login/reconnect matcher. The failure
        // dialog asks for a game restart and does not expose a login action.
        if (looksLikeReconnectFailureText(text)) {
            return Detection(ScreenKind.RECONNECT_FAILURE, ModeEnum.LOGIN, 97, "reconnect-failure-text")
        }
        if (looksLikeReconnectText(text)) {
            return Detection(ScreenKind.RECONNECT, ModeEnum.LOGIN, 96, "reconnect-disconnected-text")
        }
        // The hub's dark central menu resembles the loading card-back visual
        // signature. A strong multi-label hub OCR observation must therefore
        // win before the generic visual loading fallback.
        if (looksLikeHubText(text)) {
            return Detection(ScreenKind.HOME, ModeEnum.HUB, 92, "hub-navigation-text")
        }
        if (looksLikeLoadingText(text)) {
            return Detection(ScreenKind.LOADING, ModeEnum.STARTUP, 88, "loading-text")
        }
        if (looksLikeLoadingVisual(
                centralDarkRatio = visual.loadingCentralDarkRatio,
                warmRatio = visual.warmRatio,
                blueRatio = visual.blueRatio,
            )
        ) {
            return Detection(ScreenKind.LOADING, ModeEnum.STARTUP, 87, "loading-card-back-visual")
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
     * The Hearthstone hub always contains a navigation button labelled
     * "我的收藏". That label alone is therefore not evidence that the
     * collection screen is visible. Require a collection-only control or
     * title that is present after the hub button has been opened.
     */
    internal fun looksLikeCollectionText(ocrText: String): Boolean {
        val text = ocrText.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return text.contains("收藏管理") ||
            text.contains("我的套牌") ||
            text.contains("卡牌制作")
    }

    /**
     * The hub exposes several mode/navigation labels at once. Requiring a
     * cluster avoids treating a single "传统对战" label on a mode page as
     * the hub while still recognizing the live localized main menu.
     */
    internal fun looksLikeHubText(ocrText: String): Boolean {
        val text = ocrText.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        val hubTerms = listOf("传统对战", "酒馆战棋", "竞技模式", "其他模式", "开包", "我的收藏", "商店")
        return hubTerms.count(text::contains) >= 3
    }

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
        // The hub has a navigation button labelled "开包". Only text that
        // describes the opened pack interaction is specific to this screen.
        return text.contains("打开卡牌包") ||
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
     * not consume it. The caller needs a tri-state result: false means a
     * different known screen is visible, true means the result page remains,
     * and null means capture/OCR was inconclusive. A null must never be
     * treated as proof that the result was dismissed.
     */
    internal fun isResultVisibleForRecovery(): Boolean? = runCatching {
        val capture = captureScreen() ?: return@runCatching null
        val ocrText = runOCRIfAvailable(capture.image, "result-postcheck") ?: return@runCatching null
        val detection = detect(ocrText, capture.visual)
        when {
            detection == null || detection.confidence < 85 -> null
            detection.kind == ScreenKind.RESULT -> true
            else -> false
        }
    }.getOrElse { error ->
        log.warn(error) { "SCREEN_RECOVERY_RESULT_POSTCHECK_FAILED" }
        null
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
                            val accepted = MouseUtil.leftButtonClickForRecovery(
                                GameUtil.RECONNECT_RECT.getCenterClickPos(),
                            )
                            log.info {
                                "SCREEN_RECOVERY_RECONNECT_DISPATCHED input=recovery-sendinput " +
                                    "accepted=$accepted"
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
                Mode.recover(ModeEnum.STARTUP, "visible-loading-screen", enterStrategy = false)
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
}

/** Small thread-safe gate for the one-sidecar-at-a-time recovery invariant. */
internal class OcrInFlightGate {
    private val inFlight = AtomicBoolean(false)

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() {
        inFlight.set(false)
    }

}
