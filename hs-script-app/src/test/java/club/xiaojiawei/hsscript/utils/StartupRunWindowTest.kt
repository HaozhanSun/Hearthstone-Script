package club.xiaojiawei.hsscript.utils

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StartupRunWindowTest {

    @Test
    fun `one minute startup window expires and resumes normal schedule`() {
        val start = Instant.parse("2026-08-28T20:00:00Z")
        val window = StartupRunWindow(Clock.fixed(start, ZoneId.of("UTC"))) { "startup-run-1" }

        assertTrue(window.beginIfOutsideSchedule(durationMinutes = 1, inSchedule = false))
        assertEquals(start.plusSeconds(60), assertNotNull(window.deadline()))
        assertEquals("startup-run-1", window.snapshot(start.plusSeconds(59)).runId)
        assertTrue(window.shouldWork(inSchedule = false, now = start.plusSeconds(59)))
        assertFalse(window.shouldWork(inSchedule = false, now = start.plusSeconds(60)))
        assertFalse(window.snapshot(start.plusSeconds(60)).active)
        assertTrue(window.shouldWork(inSchedule = true, now = start.plusSeconds(60)))
    }

    @Test
    fun `starting inside schedule does not create a forced window`() {
        val window = StartupRunWindow(Clock.fixed(Instant.parse("2026-08-28T20:00:00Z"), ZoneId.of("UTC")))

        assertFalse(window.beginIfOutsideSchedule(durationMinutes = 30, inSchedule = true))
        assertFalse(window.shouldWork(inSchedule = false))
        assertTrue(window.shouldWork(inSchedule = true))
    }

    @Test
    fun `zero duration disables startup override`() {
        val window = StartupRunWindow(Clock.fixed(Instant.parse("2026-08-28T20:00:00Z"), ZoneId.of("UTC")))

        assertFalse(window.beginIfOutsideSchedule(durationMinutes = 0, inSchedule = false))
        assertFalse(window.shouldWork(inSchedule = false))
    }
}
