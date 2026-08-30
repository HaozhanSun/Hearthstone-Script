package club.xiaojiawei.hsscript.utils

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkTimeWindowTest {

    @Test
    fun `same-day window contains only times between start and end`() {
        val start = LocalTime.of(8, 39)
        val end = LocalTime.of(9, 7)

        assertFalse(WorkTimeWindow.contains(LocalTime.of(8, 38), start, end))
        assertTrue(WorkTimeWindow.contains(LocalTime.of(8, 39), start, end))
        assertTrue(WorkTimeWindow.contains(LocalTime.of(9, 7), start, end))
        assertFalse(WorkTimeWindow.contains(LocalTime.of(9, 8), start, end))
    }

    @Test
    fun `cross-midnight window contains late-night and post-midnight times`() {
        val start = LocalTime.of(23, 43)
        val end = LocalTime.of(0, 17)

        assertFalse(WorkTimeWindow.contains(LocalTime.of(23, 42), start, end))
        assertTrue(WorkTimeWindow.contains(LocalTime.of(23, 43), start, end))
        assertTrue(WorkTimeWindow.contains(LocalTime.of(23, 59), start, end))
        assertTrue(WorkTimeWindow.contains(LocalTime.of(0, 17), start, end))
        assertFalse(WorkTimeWindow.contains(LocalTime.of(0, 18), start, end))
    }

    @Test
    fun `duration and circular gaps are measured forward across midnight`() {
        assertEquals(34, WorkTimeWindow.durationMinutes(LocalTime.of(23, 43), LocalTime.of(0, 17)))
        assertEquals(72, WorkTimeWindow.gapMinutes(LocalTime.of(0, 17), LocalTime.of(1, 29)))
    }
}
