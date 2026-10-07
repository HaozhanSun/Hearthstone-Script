package club.xiaojiawei.hsscript.status

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** OCR-independent, high-confidence signature for the captured Wild title only. */
object WildModeTitleVisualMatcher {
    private const val SOURCE_WIDTH = 1920.0
    private const val SOURCE_HEIGHT = 1080.0
    private const val LEFT = 649.0
    private const val TOP = 24.0
    private const val WIDTH = 120.0
    private const val HEIGHT = 36.0

    private val template: BufferedImage? by lazy {
        javaClass.getResourceAsStream("/tournament-mode-wild-title-template.png")?.use(ImageIO::read)
    }

    /** Returns a normalized pixel similarity in [0, 1], or null for an unusable capture. */
    fun confidence(screen: BufferedImage): Double? {
        val reference = template ?: return null
        if (screen.width < 640 || screen.height < 360) return null
        val x = (screen.width * LEFT / SOURCE_WIDTH).toInt()
        val y = (screen.height * TOP / SOURCE_HEIGHT).toInt()
        val width = (screen.width * WIDTH / SOURCE_WIDTH).toInt().coerceAtLeast(1)
        val height = (screen.height * HEIGHT / SOURCE_HEIGHT).toInt().coerceAtLeast(1)
        if (x < 0 || y < 0 || x + width > screen.width || y + height > screen.height) return null

        val normalized = BufferedImage(reference.width, reference.height, BufferedImage.TYPE_INT_RGB)
        val graphics = normalized.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(screen.getSubimage(x, y, width, height), 0, 0, reference.width, reference.height, null)
        } finally {
            graphics.dispose()
        }

        var absoluteDifference = 0L
        val pixels = reference.width * reference.height
        for (py in 0 until reference.height) {
            for (px in 0 until reference.width) {
                absoluteDifference += kotlin.math.abs(luminance(reference.getRGB(px, py)) - luminance(normalized.getRGB(px, py)))
            }
        }
        return 1.0 - absoluteDifference.toDouble() / (pixels * 255.0)
    }

    private fun luminance(rgb: Int): Int =
        (299 * ((rgb ushr 16) and 0xff) + 587 * ((rgb ushr 8) and 0xff) + 114 * (rgb and 0xff)) / 1000
}

/** Resolver never upgrades a conflicting, untrusted, or low-confidence observation. */
object TournamentModeVisualConfirmationPolicy {
    const val MIN_WILD_VISUAL_CONFIDENCE = 0.94

    fun resolve(
        ocrMode: String?,
        wildVisualConfidence: Double?,
        currentPid: Long?,
        capturedPid: Long?,
    ): String {
        if (currentPid == null || currentPid <= 0L || currentPid != capturedPid) return "UNKNOWN"
        if (ocrMode == "SWITCHING" || ocrMode == "AMBIGUOUS") return "UNKNOWN"
        val normalizedOcr = ocrMode?.uppercase()?.takeIf { it == "WILD" || it == "STANDARD" }
        val strongWildVisual = wildVisualConfidence != null &&
            wildVisualConfidence >= MIN_WILD_VISUAL_CONFIDENCE
        if (normalizedOcr == "STANDARD" && strongWildVisual) return "UNKNOWN"
        return normalizedOcr ?: if (ocrMode == "UNKNOWN" && strongWildVisual) "WILD" else "UNKNOWN"
    }
}
