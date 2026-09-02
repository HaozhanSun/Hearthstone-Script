package club.xiaojiawei.hsscript.utils

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

object WorkTimeWindow {
    private const val SECONDS_PER_DAY = 24 * 60 * 60

    data class Occurrence(
        val scheduleDate: LocalDate,
        val start: LocalDateTime,
        val end: LocalDateTime,
    ) {
        val crossesMidnight: Boolean
            get() = end.toLocalDate().isAfter(scheduleDate)

        val interpretation: String
            get() = if (crossesMidnight) "cross-midnight-next-day" else "same-day"

        fun contains(now: LocalDateTime): Boolean = !now.isBefore(start) && !now.isAfter(end)

        fun secondsUntilStart(now: LocalDateTime): Long = Duration.between(now, start).seconds

        fun secondsSinceEnd(now: LocalDateTime): Long = Duration.between(end, now).seconds

        fun durationMinutes(): Long = Duration.between(start, end).toMinutes()
    }

    fun contains(
        now: LocalTime,
        start: LocalTime,
        end: LocalTime,
    ): Boolean {
        return if (start <= end) {
            now in start..end
        } else {
            now >= start || now <= end
        }
    }

    fun occurrence(
        scheduleDate: LocalDate,
        start: LocalTime,
        end: LocalTime,
    ): Occurrence {
        val startDateTime = scheduleDate.atTime(start)
        val endDate = if (start <= end) scheduleDate else scheduleDate.plusDays(1)
        return Occurrence(
            scheduleDate = scheduleDate,
            start = startDateTime,
            end = endDate.atTime(end),
        )
    }

    fun contains(
        now: LocalDateTime,
        scheduleDate: LocalDate,
        start: LocalTime,
        end: LocalTime,
    ): Boolean = occurrence(scheduleDate, start, end).contains(now)

    fun durationMinutes(
        start: LocalTime,
        end: LocalTime,
    ): Long = forwardSeconds(start, end) / 60L

    fun gapMinutes(
        end: LocalTime,
        nextStart: LocalTime,
    ): Long = forwardSeconds(end, nextStart) / 60L

    private fun forwardSeconds(
        start: LocalTime,
        end: LocalTime,
    ): Long {
        val diff = end.toSecondOfDay() - start.toSecondOfDay()
        return if (diff >= 0) diff.toLong() else (diff + SECONDS_PER_DAY).toLong()
    }
}
