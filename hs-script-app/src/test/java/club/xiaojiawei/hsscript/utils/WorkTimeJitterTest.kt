package club.xiaojiawei.hsscript.utils

import java.time.LocalTime
import java.util.ArrayDeque
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkTimeJitterTest {

    @Test
    fun `each endpoint receives its own offset within configured bound`() {
        val random =
            object : Random() {
                private val values = ArrayDeque(listOf(160, 130))

                override fun nextInt(bound: Int): Int = values.removeFirst()
            }

        val window =
            WorkTimeJitter.jitterWindow(
                start = LocalTime.of(2, 0),
                end = LocalTime.of(6, 0),
                maxSeconds = 150,
                random = random,
            )

        assertEquals(10, window.startOffsetSeconds)
        assertEquals(-20, window.endOffsetSeconds)
        assertEquals(LocalTime.of(2, 0, 10), window.start)
        assertEquals(LocalTime.of(5, 59, 40), window.end)
        assertTrue(window.startOffsetSeconds != window.endOffsetSeconds)
    }

    @Test
    fun `zero and invalid bounds are safe`() {
        assertEquals(0, WorkTimeJitter.normalizeSeconds(-1))
        assertEquals(WorkTimeJitter.MAX_JITTER_SECONDS, WorkTimeJitter.normalizeSeconds(Int.MAX_VALUE))

        val window =
            WorkTimeJitter.jitterWindow(
                start = LocalTime.MIDNIGHT,
                end = LocalTime.of(23, 59),
                maxSeconds = -10,
                random = Random(1),
            )

        assertEquals(LocalTime.MIDNIGHT, window.start)
        assertEquals(LocalTime.of(23, 59), window.end)
        assertEquals(0, window.startOffsetSeconds)
        assertEquals(0, window.endOffsetSeconds)
    }

    @Test
    fun `offsets clamp instead of wrapping to another day`() {
        val random =
            object : Random() {
                override fun nextInt(bound: Int): Int = 0
            }

        val window =
            WorkTimeJitter.jitterWindow(
                start = LocalTime.MIDNIGHT,
                end = LocalTime.of(23, 59, 59),
                maxSeconds = 150,
                random = random,
            )

        assertEquals(LocalTime.of(0, 0, 0), window.start)
        assertEquals(LocalTime.of(23, 57, 29), window.end)
        assertEquals(-150, window.startOffsetSeconds)
        assertEquals(-150, window.endOffsetSeconds)
    }

    @Test
    fun `six periods consume twelve independent endpoint draws`() {
        var drawCount = 0
        val random =
            object : Random() {
                override fun nextInt(bound: Int): Int {
                    drawCount++
                    return drawCount % bound
                }
            }

        repeat(6) { index ->
            WorkTimeJitter.jitterWindow(
                start = LocalTime.of(index + 1, 0),
                end = LocalTime.of(index + 1, 30),
                maxSeconds = 150,
                random = random,
            )
        }

        assertEquals(12, drawCount)
    }
}
