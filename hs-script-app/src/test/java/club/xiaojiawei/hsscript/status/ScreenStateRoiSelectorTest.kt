package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.awt.Rectangle

class ScreenStateRoiSelectorTest {
    @Test
    fun missingGameRectUsesExplicitFallbackOnlyInAutoMode() {
        assertEquals(
            ScreenStateRoiSelector.Strategy.PADDLEX_TARGETED,
            ScreenStateRoiSelector.plan(true, false, false, true, false).strategy,
        )
        assertEquals(
            ScreenStateRoiSelector.Strategy.LEGACY_FALLBACK,
            ScreenStateRoiSelector.plan(false, false, false, true, true).strategy,
        )
        assertEquals(
            ScreenStateRoiSelector.Strategy.SKIP_UNSAFE,
            ScreenStateRoiSelector.plan(false, false, false, true, false).strategy,
        )
        assertEquals(
            ScreenStateRoiSelector.Strategy.PADDLEX_TARGETED,
            ScreenStateRoiSelector.plan(false, false, true, true, false).strategy,
        )
    }

    @Test
    fun selectsBoundedScreenStateRoisInsteadOfTheWholeDesktop() {
        val rois = ScreenStateRoiSelector.select(3840, 2160)

        assertEquals(12, rois.size)
        assertTrue(rois.all { it.bounds.x >= 0 && it.bounds.y >= 0 })
        assertTrue(rois.all { it.bounds.maxX <= 3840 && it.bounds.maxY <= 2160 })
        assertTrue(rois.all { it.bounds.width < 3840 && it.bounds.height < 2160 })
        assertEquals(
            listOf(
                ScreenStateRoiSelector.TRADITIONAL_BATTLE_ROI,
                ScreenStateRoiSelector.DECK_SELECTION_TITLE_ROI,
                ScreenStateRoiSelector.RECONNECT_DIALOG_TITLE_ROI,
                ScreenStateRoiSelector.RECONNECT_DIALOG_STATUS_ROI,
                ScreenStateRoiSelector.RECONNECT_DIALOG_MESSAGE_ROI,
                ScreenStateRoiSelector.RESULT_CONTINUE_ROI,
                ScreenStateRoiSelector.YOGG_SERVER_UNAVAILABLE_ROI,
                ScreenStateRoiSelector.AMAZON_NOTICE_TITLE_ROI,
                ScreenStateRoiSelector.AMAZON_NOTICE_CLOSE_ROI,
                ScreenStateRoiSelector.BETA_RESULT_CONFIRMATION_ROI,
                "screen-state-header",
                "screen-state-footer",
            ),
            rois.map { it.name },
        )
    }

    @Test
    fun specialRoisUseTheSuppliedCaptureLocalAnchors() {
        assertEquals(
            listOf(
                Rectangle(873, 297, 173, 64),
                Rectangle(652, 108, 202, 64),
                Rectangle(844, 221, 231, 70),
                Rectangle(710, 550, 518, 65),
                Rectangle(576, 626, 806, 108),
                Rectangle(787, 977, 345, 81),
                Rectangle(1027, 280, 413, 281),
                Rectangle(806, 307, 576, 114),
                Rectangle(844, 734, 231, 97),
                Rectangle(777, 486, 442, 43),
            ),
            ScreenStateRoiSelector.targetedBoundsForTest(1920, 1080),
        )
        assertEquals(
            listOf(
                Rectangle(873, 296, 172, 65),
                Rectangle(652, 107, 201, 65),
                Rectangle(844, 221, 230, 70),
                Rectangle(710, 550, 518, 65),
                Rectangle(575, 625, 806, 108),
                Rectangle(786, 976, 346, 81),
                Rectangle(1026, 280, 413, 281),
                Rectangle(805, 307, 576, 113),
                Rectangle(844, 733, 230, 97),
                Rectangle(777, 485, 441, 43),
            ),
            ScreenStateRoiSelector.targetedBoundsForTest(1919, 1079),
        )
    }
}
