package club.xiaojiawei.hsscript.status

import java.awt.image.BufferedImage

/** Structural signature for the centered season-rank award modal. */
internal object SeasonRankAwardVisualClassifier {
    data class Evidence(
        val blueRankMedalRatio: Double,
        val continueButtonRatio: Double,
        val centeredParchmentRatio: Double,
        val seasonTitlePlaqueRatio: Double,
    ) {
        val isSeasonRankAward: Boolean
            get() = blueRankMedalRatio >= .18 && continueButtonRatio >= .32 &&
                centeredParchmentRatio >= .30 && seasonTitlePlaqueRatio >= .34
    }

    fun inspect(image: BufferedImage): Evidence {
        if (image.width < 800 || image.height < 450 ||
            image.width.toDouble() / image.height !in 1.65..1.90
        ) return Evidence(0.0, 0.0, 0.0, 0.0)
        return Evidence(
            blueRankMedalRatio = sample(image, .43, .57, .28, .55) { r, g, b ->
                b >= 95 && b >= r * 1.12 && b >= g * .92
            },
            continueButtonRatio = sample(image, .43, .57, .77, .87) { r, g, b ->
                r >= 135 && g >= 105 && r >= b * 1.12 && g >= b * 1.04
            },
            centeredParchmentRatio = sample(image, .35, .65, .27, .75) { r, g, b ->
                r >= 145 && g >= 125 && b >= 80 && r >= b * 1.12 && g >= b * 1.04
            },
            seasonTitlePlaqueRatio = sample(image, .36, .64, .16, .22) { r, g, b ->
                r >= 115 && g >= 90 && b >= 55 && r >= b * 1.15 && g >= b * 1.05
            },
        )
    }

    fun isSeasonRankAward(image: BufferedImage): Boolean = inspect(image).isSeasonRankAward

    private fun sample(
        image: BufferedImage,
        left: Double,
        right: Double,
        top: Double,
        bottom: Double,
        predicate: (Int, Int, Int) -> Boolean,
    ): Double {
        val x0 = (image.width * left).toInt(); val x1 = (image.width * right).toInt()
        val y0 = (image.height * top).toInt(); val y1 = (image.height * bottom).toInt()
        var matched = 0; var count = 0
        for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) {
            val rgb = image.getRGB(x, y)
            if (predicate(rgb ushr 16 and 0xff, rgb ushr 8 and 0xff, rgb and 0xff)) matched++
            count++
        }
        return if (count == 0) 0.0 else matched.toDouble() / count
    }
}
