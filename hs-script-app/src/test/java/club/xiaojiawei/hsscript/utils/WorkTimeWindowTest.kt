package club.xiaojiawei.hsscript.utils

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
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

    @Test
    fun `cross-midnight window ending at 0018 is a valid next-day occurrence`() {
        val scheduleDate = LocalDate.of(2026, 8, 29)
        val start = LocalTime.of(23, 43)
        val end = LocalTime.of(0, 18)
        val occurrence = WorkTimeWindow.occurrence(scheduleDate, start, end)

        assertEquals(LocalDateTime.of(2026, 8, 29, 23, 43), occurrence.start)
        assertEquals(LocalDateTime.of(2026, 8, 30, 0, 18), occurrence.end)
        assertTrue(occurrence.crossesMidnight)
        assertEquals("cross-midnight-next-day", occurrence.interpretation)
        assertEquals(35, occurrence.durationMinutes())
        assertTrue(occurrence.contains(LocalDateTime.of(2026, 8, 30, 0, 10)))
        assertTrue(WorkTimeWindow.contains(LocalTime.of(0, 18), start, end))
    }

    @Test
    fun `screenshot-like cross-midnight boundaries are active before and after midnight`() {
        val occurrence = WorkTimeWindow.occurrence(
            LocalDate.of(2026, 8, 30),
            LocalTime.of(23, 42, 51),
            LocalTime.of(0, 16, 24),
        )

        assertTrue(occurrence.contains(LocalDateTime.of(2026, 8, 30, 23, 50)))
        assertTrue(occurrence.contains(LocalDateTime.of(2026, 8, 31, 0, 5)))
        assertFalse(occurrence.contains(LocalDateTime.of(2026, 8, 31, 0, 20)))
        assertEquals(33, occurrence.durationMinutes())
    }

    @Test
    fun `post-midnight tail belongs to previous schedule day only`() {
        val start = LocalTime.of(23, 43)
        val end = LocalTime.of(0, 18)
        val now = LocalDateTime.of(2026, 8, 30, 0, 10)

        assertTrue(WorkTimeWindow.contains(now, LocalDate.of(2026, 8, 29), start, end))
        assertFalse(WorkTimeWindow.contains(now, LocalDate.of(2026, 8, 30), start, end))
        assertEquals(84780, WorkTimeWindow.occurrence(LocalDate.of(2026, 8, 30), start, end).secondsUntilStart(now))
    }

    @Test
    fun `adjacent windows after cross-midnight use next-day end for gap`() {
        val previous = WorkTimeWindow.occurrence(
            LocalDate.of(2026, 8, 29),
            LocalTime.of(23, 43),
            LocalTime.of(0, 18),
        )
        val next = WorkTimeWindow.occurrence(
            LocalDate.of(2026, 8, 30),
            LocalTime.of(1, 29),
            LocalTime.of(2, 3),
        )

        assertEquals(71, WorkTimeWindow.gapMinutes(LocalTime.of(0, 18), LocalTime.of(1, 29)))
        assertEquals(71, Duration.between(previous.end, next.start).toMinutes())
    }

    @Test
    fun `start equals end keeps legacy same-day point semantics`() {
        val occurrence = WorkTimeWindow.occurrence(
            LocalDate.of(2026, 8, 30),
            LocalTime.of(3, 17),
            LocalTime.of(3, 17),
        )

        assertEquals(LocalDateTime.of(2026, 8, 30, 3, 17), occurrence.start)
        assertEquals(LocalDateTime.of(2026, 8, 30, 3, 17), occurrence.end)
        assertFalse(occurrence.crossesMidnight)
        assertEquals("same-day", occurrence.interpretation)
        assertEquals(0, occurrence.durationMinutes())
        assertTrue(occurrence.contains(LocalDateTime.of(2026, 8, 30, 3, 17)))
        assertFalse(occurrence.contains(LocalDateTime.of(2026, 8, 30, 3, 17, 1)))
    }
}
