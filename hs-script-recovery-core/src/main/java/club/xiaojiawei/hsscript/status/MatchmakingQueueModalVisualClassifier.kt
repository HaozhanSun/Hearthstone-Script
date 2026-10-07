package club.xiaojiawei.hsscript.status

import java.awt.image.BufferedImage

/** Visual-only recognition for the live queue-search modal; deliberately independent of OCR. */
object MatchmakingQueueModalVisualClassifier {
    data class Evidence(
        val queueSearchModal: Boolean,
        val searchPanelRedRatio: Double,
        val searchHeaderWarmRatio: Double,
        val cancelButtonWarmRatio: Double,
    )

    fun classify(image: BufferedImage?): Evidence {
        if (image == null || image.width < 400 || image.height < 300) return Evidence(false, 0.0, 0.0, 0.0)
        val searchPanel = sample(image, left = 0.42, right = 0.60, top = 0.30, bottom = 0.65)
        // Stable title plate above the reels: unlike the cancel control, this
        // remains visible throughout the search animation.
        val searchHeader = sample(image, left = 0.36, right = 0.64, top = 0.13, bottom = 0.25)
        val cancelButton = sample(image, left = 0.45, right = 0.58, top = 0.80, bottom = 0.89)
        val confirmed = searchPanel.redRatio >= MIN_SEARCH_PANEL_RED_RATIO &&
            searchHeader.warmRatio >= MIN_SEARCH_HEADER_WARM_RATIO
        return Evidence(confirmed, searchPanel.redRatio, searchHeader.warmRatio, cancelButton.warmRatio)
    }

    private fun sample(
        image: BufferedImage,
        left: Double,
        right: Double,
        top: Double,
        bottom: Double,
    ): RegionRatios {
        val x0 = (image.width * left).toInt().coerceIn(0, image.width)
        val x1 = (image.width * right).toInt().coerceIn(x0, image.width)
        val y0 = (image.height * top).toInt().coerceIn(0, image.height)
        val y1 = (image.height * bottom).toInt().coerceIn(y0, image.height)
        val stepX = (image.width / 640).coerceAtLeast(1)
        val stepY = (image.height / 360).coerceAtLeast(1)
        var samples = 0
        var red = 0
        var warm = 0
        for (y in y0 until y1 step stepY) {
            for (x in x0 until x1 step stepX) {
                val rgb = image.getRGB(x, y)
                val r = rgb ushr 16 and 0xff
                val g = rgb ushr 8 and 0xff
                val b = rgb and 0xff
                samples++
                if (r > 70 && r > g * 1.12 && r > b * 1.12) red++
                if (r > 90 && g > 45 && r > g * 1.05 && g > b * 1.05) warm++
            }
        }
        if (samples == 0) return RegionRatios(0.0, 0.0)
        return RegionRatios(red.toDouble() / samples, warm.toDouble() / samples)
    }

    private data class RegionRatios(val redRatio: Double, val warmRatio: Double)

    private const val MIN_SEARCH_PANEL_RED_RATIO = 0.46
    private const val MIN_SEARCH_HEADER_WARM_RATIO = 0.20
}
