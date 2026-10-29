package club.xiaojiawei.hsscript.status

import java.awt.image.BufferedImage

/** Identifies Battle.net home chrome that can resemble Hearthstone queue colors. */
internal object BattleNetLauncherVisualPolicy {
    fun isVisible(image: BufferedImage): Boolean {
        if (image.width <= 0 || image.height <= 0) return false
        val aspect = image.width.toDouble() / image.height
        if (aspect !in 1.70..1.80) return false
        // Require the launcher mark and its separate lower-left blue status control.
        return blueRatio(image, 0.04, 0.03, 0.08, 0.10) >= 0.025 &&
            blueRatio(image, 0.04, 0.80, 0.20, 0.90) >= 0.07
    }

    private fun blueRatio(image: BufferedImage, left: Double, top: Double, right: Double, bottom: Double): Double {
        val x0 = (image.width * left).toInt().coerceIn(0, image.width)
        val x1 = (image.width * right).toInt().coerceIn(x0, image.width)
        val y0 = (image.height * top).toInt().coerceIn(0, image.height)
        val y1 = (image.height * bottom).toInt().coerceIn(y0, image.height)
        var samples = 0
        var blue = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val rgb = image.getRGB(x, y)
                val r = rgb shr 16 and 0xff
                val g = rgb shr 8 and 0xff
                val b = rgb and 0xff
                if (b > 140 && b > g * 1.12 && g > r * 1.18) blue++
                samples++
                x += 2
            }
            y += 2
        }
        return if (samples == 0) 0.0 else blue.toDouble() / samples
    }
}
