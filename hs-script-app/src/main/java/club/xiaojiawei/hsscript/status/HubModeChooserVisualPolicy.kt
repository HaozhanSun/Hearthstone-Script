package club.xiaojiawei.hsscript.status

import java.awt.image.BufferedImage

/** Detects the HUB's four stacked mode buttons, not the queue-search modal. */
internal object HubModeChooserVisualPolicy {
    // The last button's center on the 16:9 HUB chooser is near 0.515. Sampling
    // at 0.525 starts below its bright face and mostly sees the dark frame.
    private val buttonBands = listOf(0.285, 0.365, 0.445, 0.495)

    fun isVisible(image: BufferedImage): Boolean {
        if (image.width <= 0 || image.height <= 0) return false
        val centerLeft = (image.width * 0.39).toInt()
        val centerRight = (image.width * 0.61).toInt()
        val bandHeight = (image.height * 0.035).toInt().coerceAtLeast(1)
        return buttonBands.all { centerY ->
            val y0 = (image.height * centerY).toInt().coerceIn(0, image.height)
            val y1 = (y0 + bandHeight).coerceAtMost(image.height)
            var samples = 0
            var brightNeutral = 0
            var y = y0
            while (y < y1) {
                var x = centerLeft
                while (x < centerRight) {
                    val rgb = image.getRGB(x, y)
                    val r = rgb shr 16 and 0xff
                    val g = rgb shr 8 and 0xff
                    val b = rgb and 0xff
                    if (minOf(r, g, b) >= 150 && maxOf(r, g, b) - minOf(r, g, b) <= 95) {
                        brightNeutral++
                    }
                    samples++
                    x += 2
                }
                y += 2
            }
            samples > 0 && brightNeutral.toDouble() / samples >= 0.24
        }
    }
}
