package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.consts.CHI_SIM_DATA
import club.xiaojiawei.hsscript.consts.TESS_DATA_PATH
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import net.sourceforge.tess4j.Tesseract

enum class ObservedTournamentMode {
    STANDARD,
    WILD,
    SWITCHING,
    UNKNOWN,
}

enum class TournamentModeConfirmationState {
    CONFIRMED,
    MISMATCH,
    SWITCHING,
    UNRECOGNIZED,
    UNSUPPORTED_TARGET,
}

data class TournamentModeObservation(
    val observedMode: ObservedTournamentMode,
    val ocrText: String,
    val evidence: String,
)

data class TournamentModeConfirmationResult(
    val state: TournamentModeConfirmationState,
    val expectedMode: RunModeEnum,
    val observedMode: ObservedTournamentMode,
    val strategyId: String?,
    val strategyName: String?,
    val deckSlot: Int?,
    val reason: String,
    val ocrText: String,
) {
    val confirmed: Boolean
        get() = state == TournamentModeConfirmationState.CONFIRMED ||
            state == TournamentModeConfirmationState.UNSUPPORTED_TARGET
}

object TournamentModeConfirmation {
    private const val OCR_MAX_WIDTH = 960
    fun classifyModeTitle(ocrText: String): ObservedTournamentMode {
        val text = ocrText.lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), "")
            .replace("标淮", "标准")
            .replace("狂里", "狂野")

        val wild = text.contains("狂野对战") || text.contains("狂野模式")
        val standard = text.contains("标准对战") || text.contains("标准模式")
        if (wild && standard) return ObservedTournamentMode.UNKNOWN
        if (wild) return ObservedTournamentMode.WILD
        if (standard) return ObservedTournamentMode.STANDARD
        if (text.contains("选择模式") || text.contains("模式选择") || text.contains("传统对战")) {
            return ObservedTournamentMode.SWITCHING
        }
        return ObservedTournamentMode.UNKNOWN
    }

    fun evaluate(
        expectedMode: RunModeEnum,
        observation: TournamentModeObservation,
        strategyId: String?,
        strategyName: String?,
        deckSlot: Int?,
    ): TournamentModeConfirmationResult {
        val expectedObservedMode = expectedMode.toObservedMode()
            ?: return TournamentModeConfirmationResult(
                state = TournamentModeConfirmationState.UNSUPPORTED_TARGET,
                expectedMode = expectedMode,
                observedMode = observation.observedMode,
                strategyId = strategyId,
                strategyName = strategyName,
                deckSlot = deckSlot,
                reason = "unsupported-target-mode",
                ocrText = observation.ocrText,
            )

        val state =
            when (observation.observedMode) {
                expectedObservedMode -> TournamentModeConfirmationState.CONFIRMED
                ObservedTournamentMode.SWITCHING -> TournamentModeConfirmationState.SWITCHING
                ObservedTournamentMode.UNKNOWN -> TournamentModeConfirmationState.UNRECOGNIZED
                else -> TournamentModeConfirmationState.MISMATCH
            }
        return TournamentModeConfirmationResult(
            state = state,
            expectedMode = expectedMode,
            observedMode = observation.observedMode,
            strategyId = strategyId,
            strategyName = strategyName,
            deckSlot = deckSlot,
            reason = when (state) {
                TournamentModeConfirmationState.CONFIRMED -> "observed-target-mode"
                TournamentModeConfirmationState.MISMATCH -> "observed-different-mode"
                TournamentModeConfirmationState.SWITCHING -> "mode-selector-still-open"
                TournamentModeConfirmationState.UNRECOGNIZED -> "mode-title-unrecognized"
                TournamentModeConfirmationState.UNSUPPORTED_TARGET -> "unsupported-target-mode"
            },
            ocrText = observation.ocrText,
        )
    }

    fun confirmBeforeDeckSelection(
        expectedMode: RunModeEnum,
        deckStrategy: DeckStrategy,
        deckSlot: Int?,
        attempts: Int = 3,
        observer: () -> TournamentModeObservation = ::observeCurrentMode,
        sleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) },
        shouldContinue: () -> Boolean = { true },
    ): Boolean {
        var lastResult: TournamentModeConfirmationResult? = null
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            if (!shouldContinue()) {
                log.info {
                    "TOURNAMENT_MODE_CONFIRMATION_ABORTED reason=state-changed-before-observation " +
                        "expectedMode=${expectedMode.name} strategy=${deckStrategy.id()} deckSlot=${deckSlot ?: "n/a"}"
                }
                return false
            }
            val observation = observer()
            if (!shouldContinue()) {
                log.info {
                    "TOURNAMENT_MODE_CONFIRMATION_ABORTED reason=state-changed-during-observation " +
                        "expectedMode=${expectedMode.name} strategy=${deckStrategy.id()} deckSlot=${deckSlot ?: "n/a"}"
                }
                return false
            }
            val result = evaluate(
                expectedMode = expectedMode,
                observation = observation,
                strategyId = deckStrategy.id(),
                strategyName = deckStrategy.name(),
                deckSlot = deckSlot,
            )
            lastResult = result
            log.info {
                "TOURNAMENT_MODE_CONFIRMATION attempt=${attempt + 1} state=${result.state} " +
                    "expectedMode=${result.expectedMode.name} observedMode=${result.observedMode.name} " +
                    "strategy=${result.strategyId ?: "n/a"} strategyName=${result.strategyName ?: "n/a"} " +
                    "deckSlot=${result.deckSlot ?: "n/a"} reason=${result.reason} " +
                    "ocr=${result.ocrText.ifBlank { "<empty>" }.take(160)}"
            }
            when (result.state) {
                TournamentModeConfirmationState.CONFIRMED,
                TournamentModeConfirmationState.UNSUPPORTED_TARGET,
                -> return true

                TournamentModeConfirmationState.MISMATCH -> {
                    if (shouldContinue()) pauseForUnsafeMode(result) else logStateChanged(result)
                    return false
                }

                TournamentModeConfirmationState.SWITCHING,
                TournamentModeConfirmationState.UNRECOGNIZED,
                -> sleeper(450)
            }
        }
        if (shouldContinue()) pauseForUnsafeMode(lastResult) else logStateChanged(lastResult)
        return false
    }

    fun observeCurrentMode(): TournamentModeObservation {
        val screen = captureScreen() ?: return TournamentModeObservation(
            observedMode = ObservedTournamentMode.UNKNOWN,
            ocrText = "",
            evidence = "capture-failed",
        )
        val titleRegion = cropTitleRegion(screen)
        val titleText = runOCR(titleRegion, "tournament-mode-title")
        return TournamentModeObservation(
            observedMode = classifyModeTitle(titleText),
            ocrText = titleText,
            evidence = "title-roi-ocr",
        )
    }

    private fun pauseForUnsafeMode(result: TournamentModeConfirmationResult?) {
        if (WarEx.inWar) {
            logStateChanged(result)
            return
        }
        val evidence = UnknownStateScreenshot.capture(
            category = UnknownStateScreenshot.CATEGORY_SCREEN_RECOVERY_UNRESOLVED,
            trigger = "tournament-mode-confirmation-exhausted",
            state = "expected=${result?.expectedMode?.name ?: "UNKNOWN"}" +
                "|observed=${result?.observedMode?.name ?: "UNKNOWN"}" +
                "|strategy=${result?.strategyId ?: "UNKNOWN"}" +
                "|deckSlot=${result?.deckSlot ?: "UNKNOWN"}",
            phase = "tournament-mode-confirmation",
            label = "mode-title-confirmation-failed",
            ocrText = result?.ocrText.orEmpty(),
            visual = result?.reason.orEmpty(),
        )
        PauseStatus.setAutomaticPause(true)
        log.warn {
            // Unknown mode text is not a user/manual pause. It is a bounded,
            // recoverable safety stop: ScreenStateRecovery is allowed to
            // inspect the active window and either re-enter the mode strategy
            // or restart a genuinely stale client. Keep the selected strategy
            // and slot in the evidence so recovery cannot silently switch
            // decks after an OCR miss.
            "TOURNAMENT_MODE_CONFIRMATION_FAILED action=PAUSE_FOR_AUTOMATIC_RECOVERY " +
                "expectedMode=${result?.expectedMode?.name ?: "n/a"} " +
                "observedMode=${result?.observedMode?.name ?: "UNKNOWN"} " +
                "strategy=${result?.strategyId ?: "n/a"} strategyName=${result?.strategyName ?: "n/a"} " +
                "deckSlot=${result?.deckSlot ?: "n/a"} reason=${result?.reason ?: "no-observation"} " +
                "ocr=${result?.ocrText?.ifBlank { "<empty>" }?.take(160) ?: "<empty>"} " +
                "boundedAttempts=true screenshot=${evidence?.file?.absolutePath ?: "not-saved"}"
        }
    }

    private fun logStateChanged(result: TournamentModeConfirmationResult?) {
        log.info {
            "TOURNAMENT_MODE_CONFIRMATION_ABORTED reason=game-started-or-state-changed " +
                "expectedMode=${result?.expectedMode?.name ?: "n/a"} " +
                "observedMode=${result?.observedMode?.name ?: "UNKNOWN"} " +
                "strategy=${result?.strategyId ?: "n/a"} deckSlot=${result?.deckSlot ?: "n/a"}"
        }
    }

    private fun RunModeEnum.toObservedMode(): ObservedTournamentMode? =
        when (this) {
            RunModeEnum.STANDARD -> ObservedTournamentMode.STANDARD
            RunModeEnum.WILD -> ObservedTournamentMode.WILD
            else -> null
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
        val candidate = if (gameRect.right - gameRect.left >= 400 && gameRect.bottom - gameRect.top >= 300) {
            Rectangle(gameRect.left, gameRect.top, gameRect.right - gameRect.left, gameRect.bottom - gameRect.top)
        } else {
            allScreens
        }
        val bounds = candidate.intersection(allScreens)
        if (bounds.width < 400 || bounds.height < 300) return null
        Robot().createScreenCapture(bounds)
    }.getOrElse { error ->
        log.warn(error) { "TOURNAMENT_MODE_CONFIRMATION_CAPTURE_FAILED" }
        null
    }

    private fun cropTitleRegion(image: BufferedImage): BufferedImage {
        // The mode title is the single line in the top centre of the deck
        // selection page (for example, 狂野对战 or 标准对战).  The previous
        // 70% x 26% crop also included the deck grid and reward panel, which
        // made Tesseract spend seconds parsing unrelated text and frequently
        // return a long, unusable string.  Keep this ROI tied to the stable
        // title geometry and leave the rest of the screen to recovery OCR.
        val x = (image.width * 0.30).toInt().coerceIn(0, image.width - 1)
        val y = 0
        val width = (image.width * 0.40).toInt().coerceAtLeast(1).coerceAtMost(image.width - x)
        val height = (image.height * 0.09).toInt().coerceAtLeast(1).coerceAtMost(image.height)
        return image.getSubimage(x, y, width, height)
    }

    private fun runOCR(
        image: BufferedImage,
        desc: String,
    ): String =
        runCatching {
            val ocrImage = resizeForOcr(image)
            // Mode confirmation is part of the normal entry path, not
            // recovery.  A PaddleX cold start can take tens of seconds and
            // would make Start/F1 feel blocked even though no game action is
            // waiting on it.  The existing local OCR is sufficient for this
            // narrow title ROI; PaddleX remains available to the bounded
            // screen-recovery and rank-diagnostic paths.
            legacyOCR(ocrImage, desc).also {
                log.info {
                    "TOURNAMENT_MODE_CONFIRMATION_OCR provider=LEGACY_FAST " +
                        "desc=$desc chars=${it.length}"
                }
            }.replace(Regex("\\s+"), "")
        }.getOrElse { error ->
            log.warn(error) { "TOURNAMENT_MODE_CONFIRMATION_OCR_FAILED desc=$desc" }
            ""
        }

    private fun legacyOCR(
        image: BufferedImage,
        desc: String,
    ): String =
        // This path is deliberately local and bounded.  TesseractEx is the
        // application's provider-aware wrapper and routes through PaddleX;
        // using it here silently turned a small title-ROI check back into a
        // slow sidecar request.  Mode confirmation is on the F1/Start
        // critical path, so use the direct legacy engine for this crop.
        Tesseract().apply {
            setDatapath(File(TESS_DATA_PATH).absolutePath)
            setLanguage(CHI_SIM_DATA)
            // The ROI is intentionally one line, so single-line segmentation
            // is both faster and less prone to borrowing glyphs from the
            // deck-selection grid.
            setPageSegMode(7)
            setVariable("user_defined_dpi", "160")
        }.doOCR(image)

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
}
