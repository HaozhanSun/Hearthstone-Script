package club.xiaojiawei.hsscript.statistics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

class DailySummaryStoreTest {
    private val pacific = ZoneId.of("America/Los_Angeles")

    @Test
    fun `two app sessions on one date aggregate persisted totals`() {
        val directory = Files.createTempDirectory("daily-summary-sessions")
        try {
            val date = LocalDate.of(2026, 10, 23)
            val firstInstant = date.atTime(9, 0).atZone(pacific).toInstant()
            val secondInstant = date.atTime(10, 0).atZone(pacific).toInstant()
            val path = directory.resolve("daily-summary.properties")
            DailySummaryStore(path, pacific, Clock.fixed(secondInstant, pacific)).record(
                event(id = 101, completedAt = firstInstant, result = true, duration = 12, experience = 8),
            )

            val restarted = DailySummaryStore(path, pacific, Clock.fixed(secondInstant, pacific))
            assertEquals(1, restarted.current(date).totalGames)
            assertEquals(1, restarted.current(date).wins)
            restarted.record(
                event(id = 102, completedAt = secondInstant, result = false, duration = 7, experience = 6),
            )

            assertEquals(2, restarted.current(date).totalGames)
            assertEquals(2, restarted.current(date).realGames)
            assertEquals(1, restarted.current(date).wins)
            assertEquals(19, restarted.current(date).playDurationMinutes)
            assertEquals(14, restarted.current(date).experience)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `local midnight rolls over without carrying the prior day`() {
        val directory = Files.createTempDirectory("daily-summary-rollover")
        try {
            val previousDate = LocalDate.of(2026, 10, 23)
            val currentDate = previousDate.plusDays(1)
            val previousInstant = previousDate.atTime(23, 59).atZone(pacific).toInstant()
            val currentInstant = currentDate.atStartOfDay(pacific).toInstant()
            val store = DailySummaryStore(
                directory.resolve("daily-summary.properties"),
                pacific,
                Clock.fixed(currentInstant, pacific),
            )
            store.record(event(id = 201, completedAt = previousInstant, result = true, duration = 4, experience = 3))
            store.record(event(id = 202, completedAt = currentInstant, result = false, duration = 6, experience = 4))

            assertEquals(1, store.current(previousDate).totalGames)
            assertEquals(1, store.current(currentDate).totalGames)
            assertEquals(1, store.current().totalGames)
            assertEquals(6, store.current().playDurationMinutes)
            assertEquals(4, store.current().experience)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `metrics preserve all games and exclude only explicit local surrender from real games`() {
        val directory = Files.createTempDirectory("daily-summary-metrics")
        try {
            val date = LocalDate.of(2026, 10, 23)
            val instant = date.atTime(11, 0).atZone(pacific).toInstant()
            val store = DailySummaryStore(
                directory.resolve("daily-summary.properties"),
                pacific,
                Clock.fixed(instant, pacific),
            )
            store.record(event(id = 301, completedAt = instant, result = true, surrendered = false, duration = 12, experience = 8))
            store.record(event(id = 302, completedAt = instant, result = true, surrendered = true, duration = 3, experience = 4))
            store.record(event(id = 303, completedAt = instant, result = false, surrendered = null, duration = 7, experience = 6))

            val summary = store.current()
            assertEquals(3, summary.totalGames)
            assertEquals(2, summary.realGames)
            assertEquals(1, summary.wins)
            assertEquals(22, summary.playDurationMinutes)
            assertEquals(18, summary.experience)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `startup reconciliation recovers a record committed after the last summary write`() {
        val directory = Files.createTempDirectory("daily-summary-reconcile")
        try {
            val date = LocalDate.of(2026, 10, 23)
            val firstInstant = date.atTime(12, 0).atZone(pacific).toInstant()
            val secondInstant = date.atTime(12, 30).atZone(pacific).toInstant()
            val path = directory.resolve("daily-summary.properties")
            val first = event(id = 401, completedAt = firstInstant, result = true, duration = 5, experience = 2)
            val second = event(id = 402, completedAt = secondInstant, result = false, duration = 8, experience = 4)
            DailySummaryStore(path, pacific, Clock.fixed(secondInstant, pacific)).record(first)

            val restarted = DailySummaryStore(path, pacific, Clock.fixed(secondInstant, pacific))
            restarted.bootstrap(date, listOf(first, second))
            restarted.bootstrap(date, listOf(first, second))

            assertEquals(2, restarted.current(date).totalGames)
            assertEquals(13, restarted.current(date).playDurationMinutes)
            assertEquals(6, restarted.current(date).experience)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reset clears only today and remains cleared across restart`() {
        val directory = Files.createTempDirectory("daily-summary-reset")
        try {
            val previousDate = LocalDate.of(2026, 10, 22)
            val currentDate = previousDate.plusDays(1)
            val currentInstant = currentDate.atTime(12, 0).atZone(pacific).toInstant()
            val oldToday = event(
                id = 501,
                completedAt = currentDate.atTime(9, 0).atZone(pacific).toInstant(),
                result = true,
                duration = 10,
                experience = 4,
            )
            val previous = event(
                id = 502,
                completedAt = previousDate.atTime(18, 0).atZone(pacific).toInstant(),
                result = false,
                duration = 8,
                experience = 3,
            )
            val path = directory.resolve("daily-summary.properties")
            val store = DailySummaryStore(path, pacific, Clock.fixed(currentInstant, pacific))
            store.record(oldToday)
            store.record(previous)

            store.clear()

            assertEquals(0, store.current(currentDate).totalGames)
            assertEquals(1, store.current(previousDate).totalGames)

            val restarted = DailySummaryStore(path, pacific, Clock.fixed(currentInstant, pacific))
            restarted.bootstrap(currentDate, listOf(oldToday))
            assertEquals(0, restarted.current(currentDate).totalGames)
            assertEquals(1, restarted.current(previousDate).totalGames)

            val newToday = event(
                id = 503,
                completedAt = currentDate.atTime(13, 0).atZone(pacific).toInstant(),
                result = true,
                duration = 6,
                experience = 2,
            )
            restarted.record(newToday)
            assertEquals(1, restarted.current(currentDate).totalGames)
            assertEquals(1, restarted.current(currentDate).wins)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun event(
        id: Int,
        completedAt: java.time.Instant,
        result: Boolean,
        surrendered: Boolean? = false,
        duration: Int,
        experience: Int,
    ) = DailyGameSummaryEvent(id, result, surrendered, duration, experience, completedAt)
}
