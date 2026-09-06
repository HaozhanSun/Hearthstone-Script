package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

        assertEquals(3, rois.size)
        assertTrue(rois.all { it.bounds.x >= 0 && it.bounds.y >= 0 })
        assertTrue(rois.all { it.bounds.maxX <= 3840 && it.bounds.maxY <= 2160 })
        assertTrue(rois.all { it.bounds.width < 3840 && it.bounds.height < 2160 })
        assertEquals(
            listOf("screen-state-center", "screen-state-header", "screen-state-footer"),
            rois.map { it.name },
        )
    }
}
