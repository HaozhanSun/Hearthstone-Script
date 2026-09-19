package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.consts.CHI_SIM_DATA
import club.xiaojiawei.hsscript.consts.TESS_DATA_PATH
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscriptbase.config.log
import net.sourceforge.tess4j.Tesseract
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** OCR fallback for result pages whose Power.log player IDs are unavailable. */
internal object GameResultOutcomeDetector {
    fun classifyText(text: String): String? {
        val normalized = text.lowercase()
            .replace(Regex("\\s+"), "")
        return when {
            normalized.contains("胜利属于你") ||
                normalized.contains("胜利") ||
                normalized.contains("获胜") ||
                normalized.contains("victory") -> "win"
            normalized.contains("你输了") ||
                normalized.contains("失败") ||
                normalized.contains("败北") ||
                normalized.contains("defeat") ||
                normalized.contains("lost") -> "loss"
            else -> null
        }
    }

    fun classifyImage(image: BufferedImage): String? {
        val roi = resultBannerRoi(image)
        val crop = image.getSubimage(roi.x, roi.y, roi.width, roi.height)
        val recognition = runCatching {
            OcrRuntime.recognizeResult(
                image = crop,
                desc = "game-result-banner",
                roi = "game-result-banner",
                timeoutMs = 4_000L,
                legacyOcr = { legacyOcr(crop) },
            )
        }.getOrElse { error ->
            log.warn(error) { "GAME_RESULT_SCREEN_OCR_FAILED roi=$roi" }
            return null
        }
        val outcome = classifyText(recognition.text)
        log.info {
            "GAME_RESULT_SCREEN_OCR provider=${OcrRuntime.currentProvider()} roi=$roi " +
                "text=${recognition.text.replace(Regex("\\s+"), "").take(120).ifBlank { "<empty>" }} " +
                "outcome=${outcome ?: "UNKNOWN"}"
        }
        return outcome
    }

    private fun resultBannerRoi(image: BufferedImage): Rectangle {
        val x = (image.width * 0.20).toInt().coerceAtLeast(0)
        val y = (image.height * 0.08).toInt().coerceAtLeast(0)
        val right = (image.width * 0.80).toInt().coerceAtMost(image.width)
        val bottom = (image.height * 0.55).toInt().coerceAtMost(image.height)
        return Rectangle(x, y, (right - x).coerceAtLeast(1), (bottom - y).coerceAtLeast(1))
    }

    private fun legacyOcr(image: BufferedImage): String {
        val scaled = if (image.width >= 1200) image else {
            val width = 1200
            val height = (image.height * width.toDouble() / image.width).toInt().coerceAtLeast(1)
            BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { target ->
                val graphics = target.createGraphics()
                try {
                    graphics.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                    )
                    graphics.drawImage(image, 0, 0, width, height, null)
                } finally {
                    graphics.dispose()
                }
            }
        }
        return Tesseract().apply {
            setDatapath(TESS_DATA_PATH)
            setLanguage(CHI_SIM_DATA)
            setPageSegMode(11)
            setVariable("user_defined_dpi", "180")
        }.doOCR(scaled)
    }
}
