package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.bean.TesseractEx
import club.xiaojiawei.hsscript.consts.CHI_SIM_DATA
import club.xiaojiawei.hsscript.consts.TESS_DATA_PATH
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.status.UnknownStateScreenshot
import club.xiaojiawei.hsscriptbase.config.log
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale

/**
 * Reads the player's constructed-game rank from the lower-left Hearthstone
 * HUD. The parser is intentionally separate from screen capture so OCR
 * behavior can be tested without a live game window.
 */
object CurrentRankDetector {

    /** Ordered from below the configured Silver 10 floor to above it. */
    enum class RankTier(val order: Int) {
        UNKNOWN(-1),
        BRONZE(0),
        SILVER(1),
        GOLD(2),
        PLATINUM(3),
        DIAMOND(4),
        LEGEND(5),
    }

    private const val MIN_RANK = 1
    private const val MAX_RANK = 10
    private const val RANK_REGION_LEFT = 0.0
    // The rank badge is the small numeric shield at the extreme lower-left.
    // Keep the crop away from the player name, timer, and other HUD numbers.
    private const val RANK_REGION_TOP = 0.82
    private const val RANK_REGION_WIDTH = 0.075
    private const val RANK_REGION_HEIGHT = 0.13
    private const val RANK_EXPANDED_LEFT = 0.0
    private const val RANK_EXPANDED_TOP = 0.78
    private const val RANK_EXPANDED_WIDTH = 0.12
    private const val RANK_EXPANDED_HEIGHT = 0.20
    // The full badge contains the portrait, shield ornament, rank numeral,
    // and (at this resolution) the first pixels of the player name.  Feeding
    // that whole image to Tesseract produces values such as 939/51/191.
    // This inner window is centered on the white numeral row in the 1920x1080
    // Hearthstone HUD and deliberately excludes the portrait and name.
    private const val RANK_DIGIT_LEFT = 0.018
    private const val RANK_DIGIT_TOP = 0.880
    private const val RANK_DIGIT_WIDTH = 0.018
    private const val RANK_DIGIT_HEIGHT = 0.027
    // The stylized 10 badge is frequently OCR'd as a lone 1.  This narrow
    // inner window excludes the shield border and the player name, leaving
    // only the numeral row for a conservative two-digit layout check.
    private const val RANK_VISUAL_LEFT = 0.26
    private const val RANK_VISUAL_TOP = 0.32
    private const val RANK_VISUAL_WIDTH = 0.30
    private const val RANK_VISUAL_HEIGHT = 0.25
    private const val RANK_TWO_DIGIT_MIN_SPAN = 24

    data class Detection(
        val rank: Int?,
        val tier: RankTier,
        val ocrText: String,
        val captureBounds: Rectangle,
    )

    /** Parse only valid constructed ranks, preventing unrelated HUD numbers from becoming a decision. */
    internal fun parseRankText(rawText: String): Int? {
        val normalized = rawText.map { char ->
            when (char) {
                in '０'..'９' -> ('0'.code + (char.code - '０'.code)).toChar()
                else -> char
            }
        }.joinToString("")
        val numericRuns = Regex("\\d{1,2}")
            .findAll(normalized)
            .map { it.value }
            .toList()
        if (numericRuns.any { it == "10" }) return 10

        // A single clean digit is required for ranks 1..9.  Taking the first
        // valid substring from noisy OCR such as "01404" can turn unrelated
        // HUD numbers into a false surrender decision.
        val digitsOnly = normalized.filter(Char::isDigit)
        if (digitsOnly.length != 1) return null
        return digitsOnly.toIntOrNull()?.takeIf { it in MIN_RANK until MAX_RANK }
    }

    /** Parse an explicit localized/English league name when it is visible. */
    internal fun parseTierText(rawText: String): RankTier {
        val text = rawText.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return when {
            text.contains("白银") || text.contains("白銀") || text.contains("silver") -> RankTier.SILVER
            text.contains("黄金") || text.contains("黃金") || text.contains("gold") -> RankTier.GOLD
            text.contains("青铜") || text.contains("青銅") || text.contains("bronze") -> RankTier.BRONZE
            text.contains("白金") || text.contains("platinum") -> RankTier.PLATINUM
            text.contains("钻石") || text.contains("鑽石") || text.contains("diamond") -> RankTier.DIAMOND
            text.contains("传说") || text.contains("傳說") || text.contains("legend") -> RankTier.LEGEND
            else -> RankTier.UNKNOWN
        }
    }

    /**
     * Resolve several independent OCR passes conservatively.  Hearthstone's
     * stylized two-digit "10" is sometimes read as just "1".  A lone
     * one-digit result is therefore not strong enough to trigger surrender;
     * rank 10 wins immediately when any pass sees it, while ranks 1..9 need
     * two agreeing passes.
     */
    internal fun resolveRankCandidates(candidates: List<String>, visualTenHint: Boolean = false): Int? {
        val parsed = candidates.mapNotNull(::parseRankText)
        if (parsed.contains(10)) return 10
        // On the real rank-10 badge, all OCR passes can be contaminated by
        // the shield artwork and return only invalid multi-digit noise (for
        // example 939|51|191|91).  If the independent visual check confirms
        // a two-digit badge and OCR did see numeric pixels, that is still
        // stronger evidence for rank 10 than accepting an arbitrary lower
        // rank.  Keep the numeric-evidence requirement so an empty/blank
        // screen cannot become rank 10 from the visual hint alone.
        if (visualTenHint && parsed.isEmpty() && candidates.any { it.any(Char::isDigit) }) return 10
        // A visual hint is only allowed to recover rank 10 when OCR produced
        // no valid rank at all.  If OCR consistently sees a real lower rank
        // (for example 7/8/9), promoting it to 10 is a dangerous false safe
        // result: the surrender policy would then refuse a surrender.  The
        // explicit "10" branch above remains authoritative.
        val counts = parsed.groupingBy { it }.eachCount()
        val candidatesBelowTen = counts.entries
            .filter { (rank, count) -> rank in MIN_RANK until MAX_RANK && count >= 2 }
        if (candidatesBelowTen.isEmpty()) return null

        // A transition frame or an unrelated HUD number can produce two
        // agreeing OCR passes for one digit while other passes produce a
        // different digit (for example 2|4|4|3 on the visible rank-10
        // badge).  That is not enough evidence to surrender.  Lower-rank
        // policy is destructive, so require a single unambiguous consensus.
        val best = candidatesBelowTen.maxByOrNull { it.value } ?: return null
        val highestCount = best.value
        if (candidatesBelowTen.count { it.value == highestCount } > 1) return null
        if (parsed.any { it != best.key }) return null
        // A stylized rank-10 badge is commonly read as "1" by every OCR
        // pass when the zero is faint or cropped.  A visual-width hint can
        // also be produced by badge artwork when the numeral is absent (as
        // on the matchmaking/mulligan screen).  Neither case is sufficient
        // to distinguish rank 1 from rank 10, so repeated one-only OCR must
        // remain unresolved and must never cause an irreversible surrender.
        if (best.key == 1) return null
        return best.key
    }

    /** Capture and OCR the rank badge without touching the Hearthstone input path. */
    fun detect(): Detection? = runCatching {
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

        val screen = Robot().createScreenCapture(bounds)
        return@runCatching detectCapturedImage(screen, bounds, saveEvidence = true)
    }.getOrElse { error ->
        log.warn(error) { "RANK_OCR_FAILED" }
        null
    }

    /**
     * Run the same OCR pipeline against a supplied screenshot.  Keeping this
     * separate from Robot capture makes the real saved rank screenshots
     * usable as deterministic regression fixtures.
     */
    internal fun detectCapturedImage(
        screen: BufferedImage,
        bounds: Rectangle = Rectangle(0, 0, screen.width, screen.height),
        saveEvidence: Boolean = false,
    ): Detection? = runCatching {
        val rankRegion = cropRankRegion(screen)
        val tessData = File(TESS_DATA_PATH)
        val chiSim = File(tessData, "$CHI_SIM_DATA.traineddata")
        if (OcrRuntime.isLegacySelected() && !chiSim.isFile) {
            log.info { "RANK_OCR_SKIPPED reason=missing-tessdata path=${chiSim.absolutePath}" }
            return null
        }
        // The badge contains Arabic numerals.  Tesseract's English model is
        // substantially more reliable for the outlined 10 than the Chinese
        // model, while the Chinese model remains a compatible fallback for
        // installations that have not downloaded eng.traineddata yet.
        val rankLanguage = if (File(tessData, "eng.traineddata").isFile) "eng" else CHI_SIM_DATA

        val expandedRegion = crop(
            screen,
            RANK_EXPANDED_LEFT,
            RANK_EXPANDED_TOP,
            RANK_EXPANDED_WIDTH,
            RANK_EXPANDED_HEIGHT,
        )
        val digitRegion = crop(
            screen,
            RANK_DIGIT_LEFT,
            RANK_DIGIT_TOP,
            RANK_DIGIT_WIDTH,
            RANK_DIGIT_HEIGHT,
        )
        val ocrInputs = listOf(
            // Rank OCR must only see the small numeral window.  Running OCR
            // on the full badge was the source of 939/51/191/91 noise from
            // the portrait and shield artwork.
            digitRegion to 6,
            digitRegion to 7,
            digitRegion to 8,
            digitRegion to 10,
            digitRegion to 11,
            digitRegion to 13,
        )
        val ocrTexts = ocrInputs.map { (image, pageSegMode) ->
            ocrRank(image, tessData, pageSegMode, rankLanguage)
        }
        val ocrText = ocrTexts.firstOrNull { it.isNotBlank() }.orEmpty()
        val visualTenHint = !java.lang.Boolean.getBoolean("rank.disable.visual.hint") &&
            looksLikeTwoDigitRank(rankRegion)
        val rank = resolveRankCandidates(ocrTexts, visualTenHint)
        val tierOcrTexts = listOf(rankRegion, expandedRegion).map { image ->
            ocrTier(image, tessData)
        }
        val tierFromOcr = tierOcrTexts
            .asSequence()
            .map(::parseTierText)
            .firstOrNull { it !== RankTier.UNKNOWN }
            ?: RankTier.UNKNOWN
        val tier = if (tierFromOcr !== RankTier.UNKNOWN) {
            tierFromOcr
        } else {
            detectTierVisual(rankRegion)
        }
        log.info {
            "RANK_OCR bounds=$bounds region=${rankRegion.width}x${rankRegion.height} " +
                "text=${ocrText.ifBlank { "<empty>" }} candidates=${ocrTexts.joinToString("|") { it.ifBlank { "<empty>" } }} " +
                "tierCandidates=${tierOcrTexts.joinToString("|") { it.ifBlank { "<empty>" } }} " +
                "visualTenHint=$visualTenHint tier=${tier.name} rank=${rank ?: "UNKNOWN"}"
        }
        if (saveEvidence && (tier === RankTier.UNKNOWN || rank == null)) {
            val evidence = UnknownStateScreenshot.save(
                image = screen,
                regions = listOf(
                    UnknownStateScreenshot.UnknownRegion(
                        Rectangle(
                            0,
                            (screen.height * RANK_REGION_TOP).toInt(),
                            (screen.width * RANK_REGION_WIDTH).toInt(),
                            (screen.height * RANK_REGION_HEIGHT).toInt(),
                        ),
                        "rank-badge-unresolved",
                    ),
                ),
                category = "rank-detection",
                trigger = "rank-ocr-unresolved",
                state = "rank=${rank ?: "UNKNOWN"}|tier=${tier.name}",
                phase = "pre-mulligan-rank-check",
                ocrText = (ocrTexts + tierOcrTexts).joinToString("|") { it.ifBlank { "<empty>" } },
            )
            log.warn {
                "RANK_OCR_EVIDENCE rank=${rank ?: "UNKNOWN"} tier=${tier.name} " +
                    "path=${evidence?.file?.absolutePath ?: "not-saved"} " +
                    "link=${evidence?.link ?: "none"}"
            }
        }
        Detection(rank, tier, ocrText, bounds)
    }.getOrElse { error ->
        log.warn(error) { "RANK_OCR_FAILED" }
        null
    }

    private fun cropRankRegion(image: BufferedImage): BufferedImage {
        return crop(image, RANK_REGION_LEFT, RANK_REGION_TOP, RANK_REGION_WIDTH, RANK_REGION_HEIGHT)
    }

    /**
     * Detect the two-digit width of the rank-10 numeral when OCR drops the
     * right-hand zero.  This is deliberately only a ten hint: it never turns
     * an arbitrary one-digit OCR result into a lower rank.
     */
    internal fun looksLikeTwoDigitRank(image: BufferedImage): Boolean {
        val left = (image.width * RANK_VISUAL_LEFT).toInt().coerceIn(0, image.width - 1)
        val top = (image.height * RANK_VISUAL_TOP).toInt().coerceIn(0, image.height - 1)
        val right = (image.width * (RANK_VISUAL_LEFT + RANK_VISUAL_WIDTH))
            .toInt().coerceAtMost(image.width)
        val bottom = (image.height * (RANK_VISUAL_TOP + RANK_VISUAL_HEIGHT))
            .toInt().coerceAtMost(image.height)
        val occupiedColumns = (left until right).filter { x ->
            var foregroundPixels = 0
            for (y in top until bottom) {
                val color = image.getRGB(x, y)
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                val brightest = maxOf(red, green, blue)
                val darkest = minOf(red, green, blue)
                if (brightest >= 180 && brightest - darkest <= 70) {
                    foregroundPixels++
                }
            }
            foregroundPixels >= 2
        }
        if (occupiedColumns.isEmpty()) return false
        val span = occupiedColumns.last() - occupiedColumns.first() + 1
        return span >= RANK_TWO_DIGIT_MIN_SPAN && occupiedColumns.size >= RANK_TWO_DIGIT_MIN_SPAN / 2
    }

    /**
     * The in-game HUD normally shows only the numeric badge, not the league
     * word. Gold uses a warm yellow metal frame while Silver is near-neutral;
     * sample the badge perimeter and ignore the portrait/number interior.
     */
    internal fun detectTierVisual(image: BufferedImage): RankTier {
        val right = (image.width * 0.72).toInt().coerceAtMost(image.width)
        val bottom = (image.height * 0.82).toInt().coerceAtMost(image.height)
        val insetX = (image.width * 0.18).toInt()
        val insetY = (image.height * 0.18).toInt()
        var gold = 0
        var silver = 0
        var metal = 0
        for (y in 0 until bottom) {
            for (x in 0 until right) {
                if (x in insetX until right - insetX && y in insetY until bottom - insetY) continue
                val rgb = image.getRGB(x, y)
                val red = rgb shr 16 and 0xff
                val green = rgb shr 8 and 0xff
                val blue = rgb and 0xff
                val maximum = maxOf(red, green, blue)
                val minimum = minOf(red, green, blue)
                if (maximum < 130) continue
                if (red >= 135 && green >= 95 && blue <= 105 && red > blue * 1.35 && green > blue * 1.15) {
                    gold++
                    metal++
                } else if (maximum - minimum <= 32) {
                    silver++
                    metal++
                }
            }
        }
        if (metal < 12) return RankTier.UNKNOWN
        if (gold >= 12 && gold >= silver * 1.25) return RankTier.GOLD
        if (silver >= 12 && silver >= gold * 1.25) return RankTier.SILVER
        return RankTier.UNKNOWN
    }

    private fun crop(
        image: BufferedImage,
        left: Double,
        top: Double,
        widthRatio: Double,
        heightRatio: Double,
    ): BufferedImage {
        val x = (image.width * left).toInt().coerceIn(0, image.width - 1)
        val y = (image.height * top).toInt().coerceIn(0, image.height - 1)
        val width = (image.width * widthRatio).toInt().coerceAtLeast(1)
            .coerceAtMost(image.width - x)
        val height = (image.height * heightRatio).toInt().coerceAtLeast(1)
            .coerceAtMost(image.height - y)
        return image.getSubimage(x, y, width, height)
    }

    private fun ocrRank(image: BufferedImage, tessData: File, pageSegMode: Int, language: String): String {
        fun run(
            input: BufferedImage,
            suffix: String,
            whitelist: String = "0123456789",
        ): String = TesseractEx().apply {
            setDatapath(tessData.absolutePath)
            setLanguage(language)
            setPageSegMode(pageSegMode)
            setVariable("tessedit_char_whitelist", whitelist)
            setVariable("user_defined_dpi", "300")
        }.doOCR(input, "current-rank-psm$pageSegMode-$suffix")
            .replace(Regex("\\s+"), "")

        if (pageSegMode == 10) {
            // The badge's outlined 1 and 0 touch visually after scaling. OCR
            // each half of the same tight box as one character, then join
            // only a clean 1+0 pair. This is still OCR-only recognition and
            // avoids any template/image matching.
            // The numeral 1 is narrower than the 0.  Splitting the same
            // tight OCR input gives Tesseract a single-character view when
            // the full two-character badge is difficult to segment.
            val split = (image.width * 0.45).toInt().coerceIn(1, image.width - 1)
            val overlap = 2
            val left = image.getSubimage(0, 0, (split + overlap).coerceAtMost(image.width), image.height)
            val rightStart = (split - overlap).coerceAtLeast(0)
            val right = image.getSubimage(rightStart, 0, image.width - rightStart, image.height)
            val leftTexts = listOf(
                run(prepareRankOcrImage(left), "left-one-mask", whitelist = "1"),
                run(scaleForOcr(left, 4), "left-one-raw", whitelist = "1"),
            )
            val rightTexts = listOf(
                run(prepareRankOcrImage(right), "right-zero-mask", whitelist = "0"),
                run(scaleForOcr(right, 4), "right-zero-raw", whitelist = "0"),
            )
            val leftIsOne = leftTexts.any { text -> text.filter(Char::isDigit) == "1" }
            val rightIsZero = rightTexts.any { text -> text.filter(Char::isDigit) == "0" }
            if (leftIsOne && rightIsZero) return "10"
        }

        // Keep both views inside the same tight digit box.  The mask removes
        // the colored badge background, while the raw view preserves the
        // antialiased outline when the glyph is too thin for thresholding.
        return listOf(
            run(prepareRankOcrImage(image), "mask"),
            run(scaleForOcr(image, 4), "raw"),
        ).firstOrNull(String::isNotBlank).orEmpty()
    }

    private fun ocrTier(image: BufferedImage, tessData: File): String =
        TesseractEx().apply {
            setDatapath(tessData.absolutePath)
            setLanguage(CHI_SIM_DATA)
            setPageSegMode(11)
            setVariable("user_defined_dpi", "200")
        }.doOCR(scaleForOcr(image), "current-tier")
            .replace(Regex("\\s+"), "")

    /**
     * Keep the OCR input limited to the numeral box, but make the white
     * outlined glyph readable against the colored badge background.  This is
     * deliberately plain BufferedImage processing; it does not use a visual
     * template or OpenCV.
     */
    private fun prepareRankOcrImage(image: BufferedImage): BufferedImage {
        val scale = 4
        val padding = 20
        val scaled = BufferedImage(
            image.width * scale + padding * 2,
            image.height * scale + padding * 2,
            BufferedImage.TYPE_BYTE_GRAY,
        )
        val graphics = scaled.createGraphics()
        graphics.color = java.awt.Color.WHITE
        graphics.fillRect(0, 0, scaled.width, scaled.height)
        graphics.dispose()
        for (y in padding until scaled.height - padding) {
            for (x in padding until scaled.width - padding) {
                val source = image.getRGB((x - padding) / scale, (y - padding) / scale)
                val red = source shr 16 and 0xff
                val green = source shr 8 and 0xff
                val blue = source and 0xff
                val brightest = maxOf(red, green, blue)
                val darkest = minOf(red, green, blue)
                // The numeral has a bright, nearly neutral face.  Darken
                // those pixels and leave the colored/dark badge as white so
                // Tesseract sees a clean black-on-white digit mask.
                val foreground = brightest >= 155 && brightest - darkest <= 105
                val value = if (foreground) 0 else 255
                val gray = (value shl 16) or (value shl 8) or value
                scaled.setRGB(x, y, gray)
            }
        }
        return scaled
    }

    private fun scaleForOcr(image: BufferedImage, scale: Int = 2): BufferedImage {
        val width = (image.width * scale).coerceAtLeast(1)
        val height = (image.height * scale).coerceAtLeast(1)
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = scaled.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC,
            )
            graphics.drawImage(image, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return scaled
    }
}
