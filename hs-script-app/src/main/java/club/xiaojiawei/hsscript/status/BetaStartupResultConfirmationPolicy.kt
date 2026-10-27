package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.util.Locale

/** Beta-only authorization for Hearthstone's exact previous-ranked-result confirmation modal. */
internal object BetaStartupResultConfirmationPolicy {
    const val DISPATCH_ACTION = "startup.beta-result-confirmation"
    const val PERMIT_LIFETIME_MS = 2_000L

    fun matches(ocrText: String): Boolean {
        val text = normalize(ocrText)
        return text.contains("游戏结果") &&
            text.contains("你在上一场排名对战的对战中获胜") &&
            text.contains("确定")
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

    /** Center point of the confirmation control in the observed client frame. */
    fun confirmButtonPoint(width: Int, height: Int, originX: Int = 0, originY: Int = 0): Point? {
        if (width < 800 || height < 500) return null
        return Point(originX + (width * 0.5).toInt(), originY + (height * 0.59).toInt())
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[\\s，。、“”‘’：:！!？?]"), "")
}
