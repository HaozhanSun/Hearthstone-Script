package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage

/** Narrow visual contract for the Old Gods promotional quest popup. */
internal object EventQuestPromoOverlayVisualPolicy {
    const val DISPATCH_ACTION = "recovery.startup-quest-overlay.promo-close"

    fun isVisible(image: BufferedImage): Boolean {
        if (!StartupQuestOverlayVisualClassifier.isQuestOverlay(image)) return false
        val signal = sample(image, .45, .55, .69, .80)
        return signal.brightRatio >= .12 && signal.goldRatio >= .14 && signal.darkRatio <= .75
    }

    fun closeButtonPoint(clientBounds: Rectangle): Point? {
        if (clientBounds.width <= 0 || clientBounds.height <= 0) return null
        return Point(clientBounds.x + clientBounds.width / 2, clientBounds.y + (clientBounds.height * .745).toInt())
    }

    private data class Signal(val brightRatio: Double, val goldRatio: Double, val darkRatio: Double)

    private fun sample(image: BufferedImage, left: Double, right: Double, top: Double, bottom: Double): Signal {
        val x0 = (image.width * left).toInt().coerceIn(0, image.width)
        val x1 = (image.width * right).toInt().coerceIn(x0, image.width)
        val y0 = (image.height * top).toInt().coerceIn(0, image.height)
        val y1 = (image.height * bottom).toInt().coerceIn(y0, image.height)
        var count = 0
        var bright = 0
        var gold = 0
        var dark = 0
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
            val rgb = image.getRGB(x, y)
            val red = rgb ushr 16 and 0xff
            val green = rgb ushr 8 and 0xff
            val blue = rgb and 0xff
            val maximum = maxOf(red, green, blue)
            val average = (red + green + blue) / 3.0
            count++
            if (maximum >= 170) bright++
            if (red >= 125 && green >= 75 && red >= green * .88 && green >= blue * 1.18) gold++
            if (average < 70) dark++
        }
        if (count == 0) return Signal(0.0, 0.0, 0.0)
        return Signal(bright.toDouble() / count, gold.toDouble() / count, dark.toDouble() / count)
    }
}
