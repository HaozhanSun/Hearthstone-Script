package club.xiaojiawei.hsscript.status

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Shared scheduling boundary for result-page probes and their screen observations. */
internal object ResultPageRetryScheduler {
    fun scheduleWithFixedDelay(
        executor: ScheduledExecutorService,
        intervalMillis: Long,
        probe: Runnable,
    ): ScheduledFuture<*> = executor.scheduleWithFixedDelay(
        probe,
        intervalMillis,
        intervalMillis,
        TimeUnit.MILLISECONDS,
    )
}
