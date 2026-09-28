package club.xiaojiawei.hsscript.status

import java.awt.Rectangle

internal object ScreenWatchdogCaptureBoundsPolicy {
    fun select(
        cachedGameBounds: Rectangle?,
        currentWindowBounds: Rectangle?,
        desktopBounds: Rectangle,
        betaExtensionsEnabled: Boolean,
    ): Rectangle? {
        val cached = cachedGameBounds?.takeIf(::isUsable)?.intersection(desktopBounds)
        if (cached != null && isUsable(cached)) return cached

        if (!betaExtensionsEnabled) return desktopBounds.takeIf(::isUsable)

        val current = currentWindowBounds?.intersection(desktopBounds)
        return current?.takeIf(::isUsable)
    }

    private fun isUsable(bounds: Rectangle): Boolean = bounds.width >= 400 && bounds.height >= 300
}
