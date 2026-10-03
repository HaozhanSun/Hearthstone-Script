package club.xiaojiawei.hsscript.status

import java.awt.Rectangle

/** Fixed, bounded menu-state crops used before GAME_RECT is available. */
internal object ScreenStateRoiSelector {
    const val TRADITIONAL_BATTLE_ROI = "screen-state-traditional-battle"
    const val DECK_SELECTION_TITLE_ROI = "screen-state-deck-selection-title"
    const val RECONNECT_DIALOG_TITLE_ROI = "screen-state-reconnect-dialog-title"
    const val RECONNECT_DIALOG_STATUS_ROI = "screen-state-reconnect-dialog-status"
    const val RECONNECT_DIALOG_MESSAGE_ROI = "screen-state-reconnect-dialog-message"
    const val START_GAME_ERROR_TITLE_ROI = "screen-state-start-game-error-title"
    const val START_GAME_ERROR_BODY_ROI = "screen-state-start-game-error-body"
    const val START_GAME_ERROR_CONFIRM_ROI = "screen-state-start-game-error-confirm"
    const val RESULT_CONTINUE_ROI = "screen-state-result-continue"

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
        NormalizedRoi(RECONNECT_DIALOG_TITLE_ROI, 0.410, 0.365, 0.590, 0.435),
        // The offline/reconnect dialog is centered on the Hearthstone client.
        // Keep two narrow lines rather than OCR-ing the whole dialog: the
        // status line carries "离线状态" and the message line carries
        // "游戏连接中断" on the Chinese client.
        // The reconnect-failure dialog's body is above the confirmation
        // button. The old bands started below the body (around y=.515 and
        // y=.600), so the deck-selection title won even while this dialog
        // was visible. Keep the probes narrow, but cover both body lines.
        NormalizedRoi(RECONNECT_DIALOG_STATUS_ROI, 0.300, 0.430, 0.750, 0.515),
        NormalizedRoi(RECONNECT_DIALOG_MESSAGE_ROI, 0.300, 0.475, 0.750, 0.555),
        // The localized post-game "点击继续" control is centered near the
        // bottom of the client. Keep this exact action label separate from
        // the broad footer fallback so Tesseract can use a single text line.
        NormalizedRoi(RESULT_CONTINUE_ROI, 0.410, 0.905, 0.590, 0.980),
    )

    // Kept out of normal screen recovery's OCR pass: these three crops are
    // probed only while matchmaking, before any ERROR_RECT click.
    private val startGameErrorNormalized = listOf(
        NormalizedRoi(START_GAME_ERROR_TITLE_ROI, 0.405, 0.345, 0.595, 0.425),
        NormalizedRoi(START_GAME_ERROR_BODY_ROI, 0.285, 0.430, 0.715, 0.565),
        NormalizedRoi(START_GAME_ERROR_CONFIRM_ROI, 0.425, 0.570, 0.575, 0.675),
    )

    // Secondary probes are deliberately smaller than the old center crop.
    // The center crop covered most of the client and made every recovery
    // attempt pay for a slow OCR pass even when a screen-specific anchor was
    // already sufficient. These bands are only a compatibility fallback for
    // screens that do not yet have a dedicated anchor.
    private val secondaryNormalized = listOf(
        NormalizedRoi("screen-state-header", 0.20, 0.02, 0.80, 0.20),
        NormalizedRoi("screen-state-footer", 0.20, 0.72, 0.80, 0.98),
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
        return (targetedNormalized + secondaryNormalized).map { roi ->
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

    fun selectStartGameError(width: Int, height: Int): List<Roi> {
        if (width <= 0 || height <= 0) return emptyList()
        return startGameErrorNormalized.map { roi ->
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

    fun selectSecondary(width: Int, height: Int): List<Roi> {
        if (width <= 0 || height <= 0) return emptyList()
        return secondaryNormalized.map { roi ->
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
