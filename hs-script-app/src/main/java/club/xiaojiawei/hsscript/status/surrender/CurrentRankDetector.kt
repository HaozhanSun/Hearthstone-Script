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

        val ocrText = TesseractEx().apply {
            setDatapath(tessData.absolutePath)
            setLanguage(CHI_SIM_DATA)
            setPageSegMode(11)
            setVariable("tessedit_char_whitelist", "0123456789")
            setVariable("user_defined_dpi", "200")
        }.doOCR(scaleForOcr(rankRegion), "current-rank")
            .replace(Regex("\\s+"), "")
        val rank = parseRankText(ocrText)
        log.info {
            "RANK_OCR bounds=$bounds region=${rankRegion.width}x${rankRegion.height} " +
                "text=${ocrText.ifBlank { "<empty>" }} rank=${rank ?: "UNKNOWN"}"
        }
        Detection(rank, ocrText, bounds)
    }.getOrElse { error ->
        log.warn(error) { "RANK_OCR_FAILED" }
        null
    }

    private fun cropRankRegion(image: BufferedImage): BufferedImage {
        val x = (image.width * RANK_REGION_LEFT).toInt().coerceIn(0, image.width - 1)
        val y = (image.height * RANK_REGION_TOP).toInt().coerceIn(0, image.height - 1)
        val width = (image.width * RANK_REGION_WIDTH).toInt().coerceAtLeast(1)
            .coerceAtMost(image.width - x)
        val height = (image.height * RANK_REGION_HEIGHT).toInt().coerceAtLeast(1)
            .coerceAtMost(image.height - y)
        return image.getSubimage(x, y, width, height)
    }

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
