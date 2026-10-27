package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.util.Locale

/** Narrow recovery authorization for the Hearthstone Amazon Appstore sunset notice. */
internal object StartupNoticeRecoveryPolicy {
    const val DISPATCH_ACTION = "startup.notice-dismiss"
    const val PERMIT_LIFETIME_MS = 2_000L

    fun isAmazonAppstoreSunsetNotice(ocrText: String): Boolean {
        val text = normalize(ocrText)
        return text.contains("亚马逊应用商店支持调整") && text.contains("关闭")
    }

    fun mayDismiss(
        purpose: ScreenRecoveryCapturePurpose,
        currentSessionReady: Boolean,
        activeGame: Boolean,
        activeMatchmaking: Boolean,
        gameplayMode: Boolean,
        trustedCurrentWindow: Boolean,
        terminalState: Boolean = false,
    ): Boolean = purpose in setOf(
        ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
        ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY,
        ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
        ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
    ) && !activeGame && !activeMatchmaking && !gameplayMode && !terminalState && trustedCurrentWindow

    /** Center of the observed notice's dedicated Close-button ROI in client pixels. */
    fun closeButtonPoint(width: Int, height: Int, originX: Int = 0, originY: Int = 0): Point? {
        if (width < 400 || height < 300) return null
        return Point(originX + (width * 0.505).toInt(), originY + (height * 0.725).toInt())
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[\\s，。、“”‘’：:！!？?]"), "")
}
