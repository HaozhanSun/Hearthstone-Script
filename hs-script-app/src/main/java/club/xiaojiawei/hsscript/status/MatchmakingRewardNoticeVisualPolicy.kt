package club.xiaojiawei.hsscript.status

import java.awt.image.BufferedImage

/** OCR-independent signature for the four-card startup notice captured during Wild matchmaking. */
internal object MatchmakingRewardNoticeVisualPolicy {
    private data class Signal(
        val blueRatio: Double,
        val warmRatio: Double,
        val darkRatio: Double,
    )

    fun isVisible(image: BufferedImage): Boolean {
        if (image.width <= 0 || image.height <= 0) return false
        val aspect = image.width.toDouble() / image.height
        if (aspect !in 1.70..1.80) return false

        // The captured modal has four blue-marked card headers in one centered row.
        // A deck-selection page or the existing start-error fixture has at most one
        // matching badge in these four fixed normalized locations.
        val cardHeaderRegions = listOf(
            region(image, 0.29, 0.31, 0.33, 0.37),
            region(image, 0.40, 0.31, 0.44, 0.37),
            region(image, 0.50, 0.31, 0.54, 0.37),
            region(image, 0.61, 0.31, 0.65, 0.37),
        )
        val markedCards = cardHeaderRegions.count { it.blueRatio >= 0.10 }

        // The same capture places its only compact gold action at the lower center.
        // Known start-error dialogs put that button higher and fail this region.
        val footerButton = region(image, 0.47, 0.70, 0.54, 0.75)
        val footerText = region(image, 0.38, 0.56, 0.62, 0.67)
        return markedCards >= 3 && footerButton.warmRatio >= 0.48 &&
            footerButton.darkRatio in 0.20..0.60 && footerText.warmRatio >= 0.85
    }

    private fun region(
        image: BufferedImage,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): Signal {
        val x0 = (image.width * left).toInt().coerceIn(0, image.width)
        val x1 = (image.width * right).toInt().coerceIn(x0, image.width)
        val y0 = (image.height * top).toInt().coerceIn(0, image.height)
        val y1 = (image.height * bottom).toInt().coerceIn(y0, image.height)
        var samples = 0
        var blue = 0
        var warm = 0
        var dark = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val rgb = image.getRGB(x, y)
                val red = rgb shr 16 and 0xff
                val green = rgb shr 8 and 0xff
                val blueChannel = rgb and 0xff
                val average = (red + green + blueChannel) / 3.0
                if (blueChannel > 80 && blueChannel > red * 1.18 && blueChannel > green * 1.05) blue++
                if (red > 70 && red > green * 1.12 && red > blueChannel * 1.12) warm++
                if (average < 70) dark++
                samples++
                x += 2
            }
            y += 2
        }
        if (samples == 0) return Signal(0.0, 0.0, 0.0)
        return Signal(
            blueRatio = blue.toDouble() / samples,
            warmRatio = warm.toDouble() / samples,
            darkRatio = dark.toDouble() / samples,
        )
    }
}
