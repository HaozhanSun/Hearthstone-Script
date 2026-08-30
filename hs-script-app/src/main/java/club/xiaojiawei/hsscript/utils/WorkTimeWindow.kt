package club.xiaojiawei.hsscript.utils

import java.time.LocalTime

object WorkTimeWindow {
    private const val SECONDS_PER_DAY = 24 * 60 * 60

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
