package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.bean.TesseractEx
import club.xiaojiawei.hsscript.consts.CHI_SIM_DATA
import club.xiaojiawei.hsscript.consts.TESS_DATA_PATH
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscriptbase.config.log
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File

/**
 * Reads the player's constructed-game rank from the lower-left Hearthstone
 * HUD. The parser is intentionally separate from screen capture so OCR
 * behavior can be tested without a live game window.
 */
object CurrentRankDetector {

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
    private const val RANK_DIGIT_LEFT = 0.012
    private const val RANK_DIGIT_TOP = 0.835
    private const val RANK_DIGIT_WIDTH = 0.060
    private const val RANK_DIGIT_HEIGHT = 0.105
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
        if (visualTenHint && parsed.isNotEmpty() && parsed.all { it in 1 until MAX_RANK }) return 10
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
        val rankRegion = cropRankRegion(screen)
        val tessData = File(TESS_DATA_PATH)
        val chiSim = File(tessData, "$CHI_SIM_DATA.traineddata")
        if (!chiSim.isFile) {
            log.info { "RANK_OCR_SKIPPED reason=missing-tessdata path=${chiSim.absolutePath}" }
            return null
        }

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
            rankRegion to 11,
            rankRegion to 6,
            expandedRegion to 11,
            expandedRegion to 6,
            digitRegion to 7,
            digitRegion to 13,
        )
        val ocrTexts = ocrInputs.map { (image, pageSegMode) ->
            ocrRank(image, tessData, pageSegMode)
        }
        val ocrText = ocrTexts.firstOrNull { it.isNotBlank() }.orEmpty()
        val visualTenHint = looksLikeTwoDigitRank(rankRegion)
        val rank = resolveRankCandidates(ocrTexts, visualTenHint)
        log.info {
            "RANK_OCR bounds=$bounds region=${rankRegion.width}x${rankRegion.height} " +
                "text=${ocrText.ifBlank { "<empty>" }} candidates=${ocrTexts.joinToString("|") { it.ifBlank { "<empty>" } }} " +
                "visualTenHint=$visualTenHint rank=${rank ?: "UNKNOWN"}"
        }
        Detection(rank, ocrText, bounds)
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

    private fun ocrRank(image: BufferedImage, tessData: File, pageSegMode: Int): String =
        TesseractEx().apply {
            setDatapath(tessData.absolutePath)
            setLanguage(CHI_SIM_DATA)
            setPageSegMode(pageSegMode)
            setVariable("tessedit_char_whitelist", "0123456789")
            setVariable("user_defined_dpi", "200")
        }.doOCR(scaleForOcr(image), "current-rank-psm$pageSegMode")
            .replace(Regex("\\s+"), "")

    private fun scaleForOcr(image: BufferedImage): BufferedImage {
        val width = (image.width * 2).coerceAtLeast(1)
        val height = (image.height * 2).coerceAtLeast(1)
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
