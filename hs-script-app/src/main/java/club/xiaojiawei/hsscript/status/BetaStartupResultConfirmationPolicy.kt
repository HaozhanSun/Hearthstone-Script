package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.awt.image.BufferedImage
import java.util.Locale

/** Beta-only authorization for Hearthstone's exact previous-ranked-result confirmation modal. */
internal object BetaStartupResultConfirmationPolicy {
    const val DISPATCH_ACTION = "startup.beta-result-confirmation"
    const val PERMIT_LIFETIME_MS = 2_000L

    fun matches(ocrText: String): Boolean {
        val text = normalize(ocrText)
        return text.contains("游戏结果") &&
            matchesPreviousRankedWinBody(text) &&
            text.contains("确定")
    }

    /** OCR often reads the sentence but drops the small plaque title and button label. */
    fun matchesLiveModal(ocrText: String, image: BufferedImage?, centeredModalVisible: Boolean): Boolean =
        matches(ocrText) || centeredModalVisible && image != null &&
            matchesPreviousRankedWinBody(normalize(ocrText)) && hasStableConfirmButtonVisual(image)

    fun hasStableConfirmButtonVisual(image: BufferedImage): Boolean {
        if (image.width < 800 || image.height < 500) return false
        val left = (image.width * 0.435).toInt().coerceIn(0, image.width - 1)
        val right = (image.width * 0.565).toInt().coerceIn(left + 1, image.width)
        val top = (image.height * 0.565).toInt().coerceIn(0, image.height - 1)
        val bottom = (image.height * 0.650).toInt().coerceIn(top + 1, image.height)
        var goldPixels = 0
        for (y in top until bottom step 2) {
            for (x in left until right step 2) {
                val rgb = image.getRGB(x, y)
                val red = (rgb shr 16) and 0xff
                val green = (rgb shr 8) and 0xff
                val blue = rgb and 0xff
                if (red > 150 && green > 100 && blue < 125 && red - blue > 50) goldPixels++
            }
        }
        return goldPixels >= 150
    }

    fun mayAcknowledge(
        betaChannel: Boolean,
        purpose: ScreenRecoveryCapturePurpose,
        currentSessionReady: Boolean,
        activeGame: Boolean,
        activeMatchmaking: Boolean,
        mode: String?,
        trustedCurrentWindow: Boolean,
    ): Boolean = betaChannel && purpose == ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE &&
        !currentSessionReady && !activeGame && !activeMatchmaking &&
        mode.equals("LOGIN", ignoreCase = true) && trustedCurrentWindow

    /** Permit cheap recognition from an idle probe; only a new startup probe can authorize the click. */
    fun mayInspect(
        betaChannel: Boolean,
        purpose: ScreenRecoveryCapturePurpose,
        currentSessionReady: Boolean,
        activeGame: Boolean,
        activeMatchmaking: Boolean,
        mode: String?,
        trustedCurrentWindow: Boolean,
    ): Boolean = betaChannel && purpose in setOf(
        ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
        ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
    ) && !currentSessionReady && !activeGame && !activeMatchmaking &&
        mode.equals("LOGIN", ignoreCase = true) && trustedCurrentWindow

    /** Center point of the confirmation control in the observed client frame. */
    fun confirmButtonPoint(width: Int, height: Int, originX: Int = 0, originY: Int = 0): Point? {
        if (width < 800 || height < 500) return null
        return Point(originX + (width * 0.5).toInt(), originY + (height * 0.59).toInt())
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[\\s，。、“”‘’：:！!？?]"), "")

    private fun matchesPreviousRankedWinBody(normalizedText: String): Boolean =
        normalizedText.contains("你在上一场排名对战的对战中获胜") ||
            // The captured live dialog is consistently read as 煞胜 by
            // chi_sim when OCR is constrained to the single sentence line.
            normalizedText.contains("在上一场排名对战的对战中煞胜")
}
