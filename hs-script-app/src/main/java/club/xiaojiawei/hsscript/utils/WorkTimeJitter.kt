package club.xiaojiawei.hsscript.utils

import java.time.LocalTime
import java.util.Random

/**
 * Produces independent, bounded offsets for the two endpoints of a schedule
 * window.  The caller is responsible for caching the result for the lifetime
 * of a schedule day; generating a new value on every polling tick would make
 * the effective schedule move while it is being evaluated.
 */
object WorkTimeJitter {
    const val MAX_JITTER_SECONDS: Int = 86_400

    data class Window(
        val start: LocalTime,
        val end: LocalTime,
        val startOffsetSeconds: Int,
        val endOffsetSeconds: Int,
    )

    fun normalizeSeconds(value: Int): Int = value.coerceIn(0, MAX_JITTER_SECONDS)

    fun randomOffset(
        maxSeconds: Int,
        random: Random = Random(),
    ): Int {
        val bound = normalizeSeconds(maxSeconds)
        if (bound == 0) return 0
        return random.nextInt(bound * 2 + 1) - bound
    }

    /**
     * Jitters each endpoint independently.  Clamping at the day boundaries
     * prevents a 00:00/23:59 schedule from silently becoming a cross-midnight
     * interval, which the existing schedule model does not represent.
     */
    fun jitterWindow(
        start: LocalTime,
        end: LocalTime,
        maxSeconds: Int,
        random: Random = Random(),
    ): Window {
        val startOffset = randomOffset(maxSeconds, random)
        val endOffset = randomOffset(maxSeconds, random)
        return Window(
            start = shiftWithinDay(start, startOffset),
            end = shiftWithinDay(end, endOffset),
            startOffsetSeconds = startOffset,
            endOffsetSeconds = endOffset,
        )
    }

    private fun shiftWithinDay(time: LocalTime, offsetSeconds: Int): LocalTime {
        val shifted = time.toSecondOfDay().toLong() + offsetSeconds
        return LocalTime.ofSecondOfDay(shifted.coerceIn(0L, 86_399L))
    }
}
