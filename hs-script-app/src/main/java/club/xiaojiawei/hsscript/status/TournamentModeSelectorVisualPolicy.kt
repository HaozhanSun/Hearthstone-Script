package club.xiaojiawei.hsscript.status

import java.awt.image.BufferedImage

/** Structural proof for the three-card Wild/Standard/Casual modal over the deck page. */
internal object TournamentModeSelectorVisualPolicy {
    fun isVisible(image: BufferedImage): Boolean {
        if (image.width < 800 || image.height < 450) return false
        // This Hearthstone selector has two parchment side cards and a blue
        // selected Standard card in the center. The fixed three-column layout
        // distinguishes it from generic dialogs and the separate HUB chooser.
        val left = sample(image, .25, .40, .54, .78)
        val center = sample(image, .43, .58, .48, .76)
        val right = sample(image, .60, .75, .54, .78)
        val leftCrest = sample(image, .25, .40, .22, .53)
        val centerCrest = sample(image, .43, .58, .20, .50)
        val rightCrest = sample(image, .60, .75, .22, .53)
        return left.warm >= .62 && left.blue <= .08 &&
            center.blue >= .40 && center.warm <= .30 &&
            right.warm >= .62 && right.blue <= .12 &&
            leftCrest.warm >= .28 && centerCrest.warm >= .40 && rightCrest.warm >= .45
    }

    private fun sample(image: BufferedImage, left: Double, right: Double, top: Double, bottom: Double): Colors {
        val x0 = (image.width * left).toInt().coerceIn(0, image.width)
        val x1 = (image.width * right).toInt().coerceIn(x0, image.width)
        val y0 = (image.height * top).toInt().coerceIn(0, image.height)
        val y1 = (image.height * bottom).toInt().coerceIn(y0, image.height)
        var samples = 0
        var warm = 0
        var blue = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val rgb = image.getRGB(x, y)
                val r = rgb ushr 16 and 0xff
                val g = rgb ushr 8 and 0xff
                val b = rgb and 0xff
                if (r > 70 && r > g * 1.12 && r > b * 1.12) warm++
                if (b > 80 && b > r * 1.18 && b > g * 1.05) blue++
                samples++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        if (samples == 0) return Colors(0.0, 0.0)
        return Colors(warm.toDouble() / samples, blue.toDouble() / samples)
    }

    private data class Colors(val warm: Double, val blue: Double)

    private const val SAMPLE_STEP = 8
}
