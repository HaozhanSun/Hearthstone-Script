package club.xiaojiawei.hsscript.status

import java.awt.Rectangle

/** Fixed, bounded menu-state crops used before GAME_RECT is available. */
internal object ScreenStateRoiSelector {
    const val TRADITIONAL_BATTLE_ROI = "screen-state-traditional-battle"
    const val DECK_SELECTION_TITLE_ROI = "screen-state-deck-selection-title"

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

    // These two labels are stable, screen-specific anchors. Coordinates are
    // normalized to the captured Hearthstone client image (the same local
    // coordinate space as Capture.image), not to the desktop. They are kept
    // separate from the broad fallback crops so a positive match does not
    // require OCR of the whole client.
    //
    // traditional-battle: 1920x1080 -> x=873..1046, y=297..361. The label
    // sits inside the upper button of the hub's central mode selector.
    // deck-selection-title: 1919x1079 -> x=652..853, y=107..172. The title
    // is centered above the deck cards in the supplied deck-selection frame.
    private val targetedNormalized = listOf(
        NormalizedRoi(TRADITIONAL_BATTLE_ROI, 0.455, 0.275, 0.545, 0.335),
        NormalizedRoi(DECK_SELECTION_TITLE_ROI, 0.340, 0.100, 0.445, 0.160),
    )

    // Hearthstone menu text for other screens is concentrated in these bands.
    // They deliberately exclude most desktop edges, taskbars, and neighboring
    // windows.
    private val fallbackNormalized = listOf(
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
        return (targetedNormalized + fallbackNormalized).map { roi ->
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

    fun selectTargeted(width: Int, height: Int): List<Roi> {
        if (width <= 0 || height <= 0) return emptyList()
        return targetedNormalized.map { roi ->
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

    internal fun targetedBoundsForTest(width: Int, height: Int): List<Rectangle> =
        selectTargeted(width, height).map { it.bounds }

    internal fun normalizedBoundsForTest(width: Int, height: Int): List<Rectangle> =
        select(width, height).map { it.bounds }
}
