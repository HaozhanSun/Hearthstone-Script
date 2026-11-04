package club.xiaojiawei.hsscript.status

import java.awt.image.BufferedImage

/**
 * Structural signature for Hearthstone's post-result card-back reward popup.
 *
 * This frame can satisfy the broad Event Quest ribbon/card heuristic, but its
 * large blue-and-gold card back and blue reward ribbon identify a Continue
 * result overlay. Keep the geometry narrow so ordinary quest cards and rank
 * progression remain on their dedicated paths.
 */
internal object CardBackRewardOverlayVisualClassifier {
    data class Evidence(
        val cardBlueRatio: Double,
        val cardGoldRatio: Double,
        val ribbonBlueRatio: Double,
        val ribbonPurpleRatio: Double,
    ) {
        val isCardBackReward: Boolean
            get() = cardBlueRatio >= MIN_CARD_BLUE_RATIO &&
                cardGoldRatio >= MIN_CARD_GOLD_RATIO &&
                ribbonBlueRatio >= MIN_RIBBON_BLUE_RATIO &&
                ribbonPurpleRatio >= MIN_RIBBON_PURPLE_RATIO
    }

    private const val MIN_CARD_BLUE_RATIO = 0.35
    private const val MIN_CARD_GOLD_RATIO = 0.15
    private const val MIN_RIBBON_BLUE_RATIO = 0.35
    private const val MIN_RIBBON_PURPLE_RATIO = 0.30

    fun inspect(image: BufferedImage): Evidence {
        val aspect = image.width.toDouble() / image.height.coerceAtLeast(1)
        if (image.width < 800 || image.height < 450 || aspect !in 1.65..1.90) {
            return Evidence(0.0, 0.0, 0.0, 0.0)
        }
        return Evidence(
            cardBlueRatio = sample(image, .43, .57, .18, .55) { r, g, b ->
                b > 70 && b > r * 1.10 && b > g * .95
            },
            cardGoldRatio = sample(image, .43, .57, .18, .55) { r, g, b ->
                r > 125 && g > 75 && r > g * .88 && g > b * 1.18
            },
            ribbonBlueRatio = sample(image, .38, .62, .67, .82) { r, g, b ->
                b > 70 && b > r * 1.10 && b > g * .95
            },
            ribbonPurpleRatio = sample(image, .38, .62, .67, .82) { r, g, b ->
                b >= 95 && b >= r * 1.30 && b >= g * 1.35
            },
        )
    }

    fun isCardBackReward(image: BufferedImage): Boolean = inspect(image).isCardBackReward

    private fun sample(
        image: BufferedImage,
        left: Double,
        right: Double,
        top: Double,
        bottom: Double,
        predicate: (Int, Int, Int) -> Boolean,
    ): Double {
        val x0 = (image.width * left).toInt().coerceIn(0, image.width)
        val x1 = (image.width * right).toInt().coerceIn(x0, image.width)
        val y0 = (image.height * top).toInt().coerceIn(0, image.height)
        val y1 = (image.height * bottom).toInt().coerceIn(y0, image.height)
        var matched = 0
        var count = 0
        for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) {
            val rgb = image.getRGB(x, y)
            if (predicate(rgb ushr 16 and 0xff, rgb ushr 8 and 0xff, rgb and 0xff)) matched++
            count++
        }
        return if (count == 0) 0.0 else matched.toDouble() / count
    }
}

/** Fast result overlays must share both precedence and observation semantics at every result observer. */
internal enum class FastResultOverlayKind {
    CARD_BACK_REWARD,
    EVENT_QUEST,
}

internal object FastResultOverlayRecoveryPolicy {
    fun classify(
        continueControlVisible: Boolean,
        startupQuestOverlayVisual: Boolean,
        image: BufferedImage,
    ): FastResultOverlayKind? = when {
        // Identity is independent from action affordance.  A transition frame
        // without a visible Continue must remain a card-back reward and let
        // the normal result gate wait; it must never fall into Event Quest.
        CardBackRewardOverlayVisualClassifier.isCardBackReward(image) -> FastResultOverlayKind.CARD_BACK_REWARD
        startupQuestOverlayVisual -> FastResultOverlayKind.EVENT_QUEST
        else -> null
    }

    /** Capture authorization and timestamp come from the already-authorized client frame. */
    fun observation(
        kind: FastResultOverlayKind?,
        continueControlVisible: Boolean,
        capturedAtNanos: Long,
    ): ResultScreenObservation? = when (kind) {
        FastResultOverlayKind.CARD_BACK_REWARD -> ResultScreenObservation(
            resultVisible = true,
            captureAuthorized = true,
            visualOnlyResultEvidence = true,
            continueControlVisible = continueControlVisible,
            // This is a centered reward modal. It authorizes only the bounded
            // Continue path, never generic modal or Event Quest input.
            gameMenuOverlayVisible = true,
            capturedAtNanos = capturedAtNanos,
        )
        FastResultOverlayKind.EVENT_QUEST -> ResultScreenObservation(
            resultVisible = null,
            captureAuthorized = true,
            gameMenuOverlayVisible = true,
            capturedAtNanos = capturedAtNanos,
        )
        null -> null
    }
}
