package club.xiaojiawei.hsscript.status

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleOverrideLogGateTest {
    private val info = ScheduleOverrideInfo(
        source = "debug-run",
        runId = "run-1",
        deadline = Instant.parse("2026-09-02T08:30:00Z"),
        mode = "GAME_MODE",
        provider = "LEGACY",
    )

    @Test
    fun `same override is logged once and a new run may log again`() {
        val gate = ScheduleOverrideLogGate()

        assertTrue(gate.consume(info))
        assertFalse(gate.consume(info))
        assertTrue(gate.consume(info.copy(runId = "run-2")))
    }

    @Test
    fun `concurrent polling admits only one suppression log`() {
        val gate = ScheduleOverrideLogGate()
        val admitted = AtomicInteger()
        val ready = CountDownLatch(20)
        val start = CountDownLatch(1)
        val threads = List(20) {
            Thread {
                ready.countDown()
                start.await()
                if (gate.consume(info)) admitted.incrementAndGet()
            }.apply { start() }
        }

        ready.await()
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(1, admitted.get())
    }

    @Test
    fun `reset allows a later lifecycle to emit its first suppression`() {
        val gate = ScheduleOverrideLogGate()

        assertTrue(gate.consume(info))
        gate.reset()
        assertTrue(gate.consume(info))
    }
}
