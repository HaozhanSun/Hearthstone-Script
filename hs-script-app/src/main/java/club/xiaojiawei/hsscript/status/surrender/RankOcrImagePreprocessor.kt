package club.xiaojiawei.hsscript.status.surrender

import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

/** Stable Lanczos3 upscaling for the small numeric rank ROI sent to PaddleX. */
internal object RankOcrImagePreprocessor {
    const val ALGORITHM = "LANCZOS3"

    fun upscale(image: BufferedImage, scale: Int): BufferedImage {
        require(scale >= 1) { "scale must be positive" }
        if (scale == 1) return image

        val output = BufferedImage(image.width * scale, image.height * scale, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until output.height) {
            val sourceY = (y + 0.5) / scale - 0.5
            for (x in 0 until output.width) {
                val sourceX = (x + 0.5) / scale - 0.5
                var red = 0.0
                var green = 0.0
                var blue = 0.0
                var total = 0.0
                for (sampleY in floor(sourceY - RADIUS).toInt()..ceil(sourceY + RADIUS).toInt()) {
                    val yWeight = lanczos(sourceY - sampleY)
                    if (yWeight == 0.0) continue
                    for (sampleX in floor(sourceX - RADIUS).toInt()..ceil(sourceX + RADIUS).toInt()) {
                        val weight = yWeight * lanczos(sourceX - sampleX)
                        if (weight == 0.0) continue
                        val rgb = image.getRGB(
                            sampleX.coerceIn(0, image.width - 1),
                            sampleY.coerceIn(0, image.height - 1),
                        )
                        red += (rgb ushr 16 and 0xff) * weight
                        green += (rgb ushr 8 and 0xff) * weight
                        blue += (rgb and 0xff) * weight
                        total += weight
                    }
                }
                val divisor = if (total == 0.0) 1.0 else total
                output.setRGB(x, y, (channel(red / divisor) shl 16) or
                    (channel(green / divisor) shl 8) or channel(blue / divisor))
            }
        }
        return output
    }

    private fun lanczos(value: Double): Double {
        val distance = abs(value)
        if (distance >= RADIUS) return 0.0
        if (distance < 1.0e-9) return 1.0
        val angle = Math.PI * distance
        return (sin(angle) / angle) * (sin(angle / RADIUS) / (angle / RADIUS))
    }

    private fun channel(value: Double): Int = (value.coerceIn(0.0, 255.0) + 0.5).toInt()

    private const val RADIUS = 3.0
}
