package club.xiaojiawei.hsscript.status

import java.awt.image.BufferedImage

/** Structural signature for the second, "click to open" season reward chest screen. */
internal object SeasonRankAwardChestVisualClassifier {
    data class Evidence(
        val seasonTitlePlaqueRatio: Double,
        val cyanChestGlowRatio: Double,
        val openPromptDarkRatio: Double,
        val disabledContinueButtonWarmRatio: Double,
    ) {
        val isSeasonRankAwardChest: Boolean
            get() = seasonTitlePlaqueRatio >= .30 && cyanChestGlowRatio >= .16 &&
                openPromptDarkRatio >= .02 && disabledContinueButtonWarmRatio <= .16
    }

    fun inspect(image: BufferedImage): Evidence {
        if (image.width < 800 || image.height < 450 ||
            image.width.toDouble() / image.height !in 1.65..1.90
        ) return Evidence(0.0, 0.0, 0.0, 1.0)
        return Evidence(
            seasonTitlePlaqueRatio = sample(image, .36, .64, .16, .22) { r, g, b ->
                r >= 115 && g >= 90 && b >= 55 && r >= b * 1.15 && g >= b * 1.05
            },
            // The second-stage chest has a large cyan outline, unlike the blue
            // medal on the first Continue screen or the generic AppStore card.
            cyanChestGlowRatio = sample(image, .39, .61, .40, .69) { r, g, b ->
                b >= 130 && g >= 110 && b >= r * 1.25 && g >= r * 1.15
            },
            openPromptDarkRatio = sample(image, .39, .61, .68, .76) { r, g, b ->
                (r + g + b) / 3 < 110
            },
            // On this screen the lower Continue control is visibly disabled.
            disabledContinueButtonWarmRatio = sample(image, .43, .57, .77, .87) { r, g, b ->
                r >= 135 && g >= 105 && r >= b * 1.12 && g >= b * 1.04
            },
        )
    }

    fun isSeasonRankAwardChest(image: BufferedImage): Boolean = inspect(image).isSeasonRankAwardChest

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
