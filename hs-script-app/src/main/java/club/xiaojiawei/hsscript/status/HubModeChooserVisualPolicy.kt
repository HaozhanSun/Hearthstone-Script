package club.xiaojiawei.hsscript.status

import java.awt.image.BufferedImage

/** Detects the HUB's four stacked mode buttons, not the queue-search modal. */
internal object HubModeChooserVisualPolicy {
    // Sample the four button faces. Hearthstone's rendered parchment is often
    // too brown/dim for a bright-neutral threshold, so use a broad neutral
    // luminance band and positions centered on the actual button faces.
    private val buttonBands = listOf(0.285, 0.365, 0.435, 0.495)

    fun isVisible(image: BufferedImage): Boolean {
        if (image.width <= 0 || image.height <= 0) return false
        if (!hasButtonPanelContrast(image)) return false
        val centerLeft = (image.width * 0.39).toInt()
        val centerRight = (image.width * 0.61).toInt()
        val bandHeight = (image.height * 0.035).toInt().coerceAtLeast(1)
        val faces = buttonBands.map { centerY ->
            val y0 = (image.height * centerY).toInt().coerceIn(0, image.height)
            val y1 = (y0 + bandHeight).coerceAtMost(image.height)
            var samples = 0
            var brightNeutral = 0
            var luminanceSum = 0.0
            var y = y0
            while (y < y1) {
                var x = centerLeft
                while (x < centerRight) {
                    val rgb = image.getRGB(x, y)
                    val r = rgb shr 16 and 0xff
                    val g = rgb shr 8 and 0xff
                    val b = rgb and 0xff
                    if (minOf(r, g, b) >= 70 && maxOf(r, g, b) - minOf(r, g, b) <= 120) {
                        brightNeutral++
                    }
                    luminanceSum += 299 * r + 587 * g + 114 * b
                    samples++
                    x += 2
                }
                y += 2
            }
            if (samples == 0) return false
            ButtonFace(
                neutralFraction = brightNeutral.toDouble() / samples,
                meanLuminance = luminanceSum / samples / 1000.0,
            )
        }
        if (faces.any { it.neutralFraction < MIN_NEUTRAL_FACE_FRACTION }) return false

        // Colorful result art can contain four pale horizontal regions at the
        // same coordinates. A real chooser presents four separate button
        // faces, each brighter than the inter-button background around it.
        val gaps = buttonBands.zipWithNext { upper, lower -> (upper + lower) / 2.0 }
            .map { centerY -> meanLuminance(image, centerLeft, centerRight, centerY, bandHeight) }
        return faces.indices.all { index ->
            val adjacentGaps = buildList {
                if (index > 0) add(gaps[index - 1])
                if (index < gaps.size) add(gaps[index])
            }
            val surroundingBackground = adjacentGaps.average()
            faces[index].meanLuminance - surroundingBackground >= MIN_FACE_TO_GAP_CONTRAST
        }
    }

    private fun meanLuminance(
        image: BufferedImage,
        x0: Int,
        x1: Int,
        centerY: Double,
        height: Int,
    ): Double {
        val y0 = (image.height * centerY).toInt().coerceIn(0, image.height)
        val y1 = (y0 + height).coerceAtMost(image.height)
        var sum = 0.0
        var samples = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val rgb = image.getRGB(x, y)
                sum += 299 * (rgb shr 16 and 0xff) + 587 * (rgb shr 8 and 0xff) + 114 * (rgb and 0xff)
                samples++
                x += 2
            }
            y += 2
        }
        return if (samples == 0) 0.0 else sum / samples / 1000.0
    }

    private data class ButtonFace(val neutralFraction: Double, val meanLuminance: Double)

    /** Uniform loading/placeholder frames can satisfy every luminance band. */
    private fun hasButtonPanelContrast(image: BufferedImage): Boolean {
        val x0 = (image.width * 0.35).toInt()
        val x1 = (image.width * 0.65).toInt()
        val y0 = (image.height * 0.25).toInt()
        val y1 = (image.height * 0.56).toInt()
        var samples = 0
        var sum = 0.0
        var sumSquares = 0.0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val rgb = image.getRGB(x, y)
                val luminance = 299 * (rgb shr 16 and 0xff) +
                    587 * (rgb shr 8 and 0xff) + 114 * (rgb and 0xff)
                samples++
                sum += luminance
                sumSquares += luminance.toDouble() * luminance
                x += 4
            }
            y += 4
        }
        if (samples == 0) return false
        val variance = (sumSquares / samples - (sum / samples) * (sum / samples)).coerceAtLeast(0.0)
        return kotlin.math.sqrt(variance) >= MIN_PANEL_LUMINANCE_DEVIATION
    }

    private const val MIN_PANEL_LUMINANCE_DEVIATION = 18.0
    private const val MIN_NEUTRAL_FACE_FRACTION = 0.24
    private const val MIN_FACE_TO_GAP_CONTRAST = 10.0
}
