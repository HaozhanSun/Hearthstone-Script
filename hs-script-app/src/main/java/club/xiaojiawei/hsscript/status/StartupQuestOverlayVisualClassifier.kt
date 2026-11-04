package club.xiaojiawei.hsscript.status

import java.awt.image.BufferedImage

/** OCR-independent contract for the startup one-card and post-match two-card quest modals. */
internal object StartupQuestOverlayVisualClassifier {
    data class Evidence(
        val titleGoldRatio: Double,
        val leftCardParchmentRatio: Double,
        val rightCardParchmentRatio: Double,
        val cardGutterDarkRatio: Double,
        val dimmedHubRatio: Double,
        val singleQuestCardParchmentRatio: Double,
        val completionHeaderPurpleRatio: Double,
    ) {
        val isQuestOverlay: Boolean
            get() = dimmedHubRatio >= MIN_DIMMED_HUB_RATIO &&
                (titleGoldRatio >= MIN_TITLE_GOLD_RATIO &&
                    (leftCardParchmentRatio >= MIN_CARD_PARCHMENT_RATIO &&
                    rightCardParchmentRatio >= MIN_CARD_PARCHMENT_RATIO &&
                    cardGutterDarkRatio >= MIN_CARD_GUTTER_DARK_RATIO ||
                    singleQuestCardParchmentRatio >= MIN_CARD_PARCHMENT_RATIO) ||
                    completionHeaderPurpleRatio >= MIN_COMPLETION_HEADER_PURPLE_RATIO &&
                    singleQuestCardParchmentRatio >= MIN_CARD_PARCHMENT_RATIO)
    }

    private const val MIN_TITLE_GOLD_RATIO = 0.26
    private const val MIN_CARD_PARCHMENT_RATIO = 0.34
    private const val MIN_CARD_GUTTER_DARK_RATIO = 0.55
    private const val MIN_DIMMED_HUB_RATIO = 0.20
    private const val MIN_COMPLETION_HEADER_PURPLE_RATIO = 0.30

    fun inspect(image: BufferedImage): Evidence {
        val aspect = image.width.toDouble() / image.height.coerceAtLeast(1)
        if (image.width < 800 || image.height < 450 || aspect !in 1.65..1.90) {
            return Evidence(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        return Evidence(
            titleGoldRatio = sample(image, .36, .64, .18, .30, ::isGold),
            leftCardParchmentRatio = sample(image, .33, .49, .35, .66, ::isParchment),
            rightCardParchmentRatio = sample(image, .51, .67, .35, .66, ::isParchment),
            cardGutterDarkRatio = sample(image, .49, .51, .35, .66) { isDark(it) },
            dimmedHubRatio = sample(image, .18, .82, .76, .94) { isDimmed(it) },
            singleQuestCardParchmentRatio = sample(image, .42, .58, .35, .66, ::isParchment),
            completionHeaderPurpleRatio = sample(image, .37, .63, .385, .435, ::isQuestPurple),
        )
    }

    fun isQuestOverlay(image: BufferedImage): Boolean =
        inspect(image).isQuestOverlay &&
            !HubModeChooserVisualPolicy.isVisible(image) &&
            // Card-back rewards share enough of the old purple/card geometry
            // to match this broad heuristic.  Exclude the actual identity
            // directly; rank progression has its own existing classifier and
            // must not become a proxy for a different reward popup.
            !CardBackRewardOverlayVisualClassifier.isCardBackReward(image) &&
            !SeasonRankAwardVisualClassifier.isSeasonRankAward(image) &&
            !SeasonRankAwardChestVisualClassifier.isSeasonRankAwardChest(image) &&
            !SeasonRankAwardRewardsVisualClassifier.isRewardPresentation(image)

    private fun sample(
        image: BufferedImage,
        left: Double,
        right: Double,
        top: Double,
        bottom: Double,
        predicate: (Int) -> Boolean,
    ): Double {
        val x0 = (image.width * left).toInt().coerceIn(0, image.width)
        val x1 = (image.width * right).toInt().coerceIn(x0, image.width)
        val y0 = (image.height * top).toInt().coerceIn(0, image.height)
        val y1 = (image.height * bottom).toInt().coerceIn(y0, image.height)
        var matched = 0
        var count = 0
        for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) {
            if (predicate(image.getRGB(x, y))) matched++
            count++
        }
        return if (count == 0) 0.0 else matched.toDouble() / count
    }

    private fun isGold(rgb: Int): Boolean {
        val red = rgb ushr 16 and 0xff
        val green = rgb ushr 8 and 0xff
        val blue = rgb and 0xff
        return red >= 125 && green >= 75 && red >= green * 0.88 && green >= blue * 1.18
    }

    private fun isParchment(rgb: Int): Boolean {
        val red = rgb ushr 16 and 0xff
        val green = rgb ushr 8 and 0xff
        val blue = rgb and 0xff
        return red >= 125 && green >= 105 && blue >= 65 && red >= blue * 1.14 && green >= blue * 1.05
    }

    private fun isDimmed(rgb: Int): Boolean {
        val red = rgb ushr 16 and 0xff
        val green = rgb ushr 8 and 0xff
        val blue = rgb and 0xff
        return (red + green + blue) / 3 < 105
    }

    private fun isDark(rgb: Int): Boolean {
        val red = rgb ushr 16 and 0xff
        val green = rgb ushr 8 and 0xff
        val blue = rgb and 0xff
        return (red + green + blue) / 3 < 90
    }

    /** The completion popup uses a blue-violet title ribbon instead of the gold startup title. */
    private fun isQuestPurple(rgb: Int): Boolean {
        val red = rgb ushr 16 and 0xff
        val green = rgb ushr 8 and 0xff
        val blue = rgb and 0xff
        return blue >= 95 && blue >= red * 1.30 && blue >= green * 1.35
    }
}
