package club.xiaojiawei.hsscript.status

import java.awt.Rectangle

/** Fixed, bounded menu-state crops used before GAME_RECT is available. */
internal object ScreenStateRoiSelector {
    data class Roi(val name: String, val bounds: Rectangle)

    enum class Strategy {
        LEGACY_SELECTED,
        PADDLEX_TARGETED,
        LEGACY_FALLBACK,
        SKIP_UNSAFE,
    }

    data class Plan(val strategy: Strategy)

    private data class NormalizedRoi(
        val name: String,
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    )

    // Hearthstone menu text is concentrated in these bands. They deliberately
    // exclude most desktop edges, taskbars, and neighboring windows.
    private val normalized = listOf(
        NormalizedRoi("screen-state-center", 0.18, 0.08, 0.82, 0.88),
        NormalizedRoi("screen-state-header", 0.12, 0.02, 0.88, 0.36),
        NormalizedRoi("screen-state-footer", 0.12, 0.64, 0.88, 0.98),
    )

    fun plan(
        gameRectKnown: Boolean,
        gameWindowKnown: Boolean,
        looksLikeHearthstone: Boolean,
        paddleXSelected: Boolean,
        legacyFallbackAllowed: Boolean,
    ): Plan = when {
        !paddleXSelected -> Plan(Strategy.LEGACY_SELECTED)
        gameRectKnown || gameWindowKnown || looksLikeHearthstone -> Plan(Strategy.PADDLEX_TARGETED)
        legacyFallbackAllowed -> Plan(Strategy.LEGACY_FALLBACK)
        else -> Plan(Strategy.SKIP_UNSAFE)
    }

    fun select(width: Int, height: Int): List<Roi> {
        if (width <= 0 || height <= 0) return emptyList()
        return normalized.map { roi ->
            Roi(
                roi.name,
                Rectangle(
                    (width * roi.left).toInt().coerceAtLeast(0),
                    (height * roi.top).toInt().coerceAtLeast(0),
                    ((width * roi.right).toInt() - (width * roi.left).toInt()).coerceAtLeast(1),
                    ((height * roi.bottom).toInt() - (height * roi.top).toInt()).coerceAtLeast(1),
                ),
            )
        }
    }

    internal fun normalizedBoundsForTest(width: Int, height: Int): List<Rectangle> =
        select(width, height).map { it.bounds }
}
