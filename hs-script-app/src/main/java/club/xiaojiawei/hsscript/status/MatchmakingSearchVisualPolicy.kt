package club.xiaojiawei.hsscript.status

import java.awt.image.BufferedImage

/** OCR-independent signature for Hearthstone's centered searching-for-opponent modal. */
internal object MatchmakingSearchVisualPolicy {
    fun isVisible(image: BufferedImage): Boolean {
        if (image.width <= 0 || image.height <= 0) return false
        val aspect = image.width.toDouble() / image.height
        if (aspect !in 1.70..1.80) return false

        // This modal combines the animated red center panel, its lower-center
        // gold Cancel button, and a separate dark queue-time box at lower right.
        val spinner = region(image, 0.41, 0.28, 0.61, 0.66)
        val cancelButton = region(image, 0.45, 0.80, 0.59, 0.89)
        val queueTimer = region(image, 0.61, 0.84, 0.74, 0.96)
        return spinner.redRatio >= 0.20 && cancelButton.warmRatio >= 0.18 && queueTimer.darkRatio >= 0.65
    }

    private data class Signal(val redRatio: Double, val warmRatio: Double, val darkRatio: Double)

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
        var red = 0
        var warm = 0
        var dark = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val rgb = image.getRGB(x, y)
                val r = rgb shr 16 and 0xff
                val g = rgb shr 8 and 0xff
                val b = rgb and 0xff
                if (r > 100 && r > g * 1.20 && r > b * 1.20) red++
                if (r > 100 && r > g * 1.12 && r > b * 1.12) warm++
                if ((r + g + b) / 3 < 70) dark++
                samples++
                x += 2
            }
            y += 2
        }
        if (samples == 0) return Signal(0.0, 0.0, 0.0)
        return Signal(red.toDouble() / samples, warm.toDouble() / samples, dark.toDouble() / samples)
    }
}
