package club.xiaojiawei.hsscript.status

import java.awt.image.BufferedImage

/** Structural signature for the post-open seasonal reward presentation with its Continue control. */
internal object SeasonRankAwardRewardsVisualClassifier {
    data class Evidence(
        val seasonPlaqueRatio: Double,
        val rankBadgeRatio: Double,
        val activeContinueRatio: Double,
        val rewardChestRatios: List<Double>,
    ) {
        val isRewardPresentation: Boolean
            get() = seasonPlaqueRatio >= .34 && rankBadgeRatio >= .14 &&
                activeContinueRatio >= .25 && rewardChestRatios.count { it >= .018 } >= 4
    }

    fun inspect(image: BufferedImage): Evidence {
        if (image.width < 800 || image.height < 450 ||
            image.width.toDouble() / image.height !in 1.65..1.90
        ) return Evidence(0.0, 0.0, 0.0, emptyList())

        val rewardChestRegions = listOf(
            doubleArrayOf(.45, .59, .14, .34), // upper center
            doubleArrayOf(.24, .39, .28, .51), // left
            doubleArrayOf(.65, .80, .28, .51), // right
            doubleArrayOf(.30, .45, .68, .93), // lower left
            doubleArrayOf(.59, .75, .68, .93), // lower right
        )
        return Evidence(
            seasonPlaqueRatio = sample(image, .36, .64, .16, .22) { r, g, b ->
                r >= 115 && g >= 90 && b >= 55 && r >= b * 1.15 && g >= b * 1.05
            },
            rankBadgeRatio = sample(image, .46, .54, .54, .68) { r, g, b ->
                b >= 95 && b >= r * 1.12 && b >= g * .92
            },
            activeContinueRatio = sample(image, .43, .57, .77, .87) { r, g, b ->
                r >= 135 && g >= 105 && r >= b * 1.12 && g >= b * 1.04
            },
            rewardChestRatios = rewardChestRegions.map { (left, right, top, bottom) ->
                sample(image, left, right, top, bottom) { r, g, b ->
                    b >= 100 && b >= r * 1.18 && b >= g * .90
                }
            },
        )
    }

    fun isRewardPresentation(image: BufferedImage): Boolean = inspect(image).isRewardPresentation

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
