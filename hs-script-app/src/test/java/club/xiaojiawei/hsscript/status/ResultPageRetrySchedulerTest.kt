package club.xiaojiawei.hsscript.status

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResultPageRetrySchedulerTest {
    @Test
    fun `next input waits for full observation plus fixed retry delay`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            val dispatchTimes = mutableListOf<Long>()
            val observations = AtomicInteger()
            val observationMillis = 180L
            val retryMillis = 60L
            val future = ResultPageRetryScheduler.scheduleWithFixedDelay(
                executor,
                retryMillis,
                Runnable {
                    if (observations.incrementAndGet() <= 2) {
                        Thread.sleep(observationMillis)
                        synchronized(dispatchTimes) { dispatchTimes += System.nanoTime() }
                    }
                },
            )

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (synchronized(dispatchTimes) { dispatchTimes.size } < 2 && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }
            future.cancel(true)

            val elapsedMillis = synchronized(dispatchTimes) {
                TimeUnit.NANOSECONDS.toMillis(dispatchTimes[1] - dispatchTimes[0])
            }
            assertTrue(
                elapsedMillis >= observationMillis + retryMillis - 10,
                "retry should begin only after the observation finishes and its delay elapses; actual=${elapsedMillis}ms",
            )
        } finally {
            executor.shutdownNow()
        }
    }
}
