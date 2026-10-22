package club.xiaojiawei.hsscript.status

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** Serial probe cadence: inspect again immediately after an input, but pace each input from its predecessor. */
internal object ResultPageRetryScheduler {
    private const val SCHEDULER_POLL_MS = 100L

    interface InputGate {
        /** Wait only for the remainder of the previous-input interval; slow observation time counts toward it. */
        fun awaitEligibility(stillAuthorized: () -> Boolean): Boolean

        fun elapsedSinceLastDispatchMillis(): Long?

        fun millisUntilEligible(): Long

        /** Call immediately after a real dispatch attempt, whether the OS accepted it or not. */
        fun recordDispatch(): Long?
    }

    fun scheduleWithFixedDelay(
        executor: ScheduledExecutorService,
        initialDelayMillis: Long,
        retryIntervalMillis: Long,
        pollIntervalMillis: Long = SCHEDULER_POLL_MS,
        probe: (InputGate) -> Unit,
    ): ScheduledFuture<*> {
        require(initialDelayMillis >= 0L)
        require(retryIntervalMillis > 0L)
        require(pollIntervalMillis > 0L)

        val lastDispatchNanos = AtomicLong(Long.MIN_VALUE)
        val nextProbeAtNanos = AtomicLong(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(initialDelayMillis))
        val dispatchInLastProbe = AtomicLong(0L)
        return executor.scheduleWithFixedDelay(
            {
                val now = System.nanoTime()
                if (now < nextProbeAtNanos.get()) return@scheduleWithFixedDelay
                val previousDispatchMarker = dispatchInLastProbe.get()
                val gate = object : InputGate {
                    override fun awaitEligibility(stillAuthorized: () -> Boolean): Boolean {
                        val lastDispatch = lastDispatchNanos.get()
                        if (lastDispatch == Long.MIN_VALUE) return stillAuthorized()
                        val deadline = lastDispatch + TimeUnit.MILLISECONDS.toNanos(retryIntervalMillis)
                        while (true) {
                            if (!stillAuthorized() || Thread.currentThread().isInterrupted) return false
                            val remaining = deadline - System.nanoTime()
                            if (remaining <= 0L) return true
                            LockSupport.parkNanos(remaining)
                        }
                    }

                    override fun recordDispatch(): Long? {
                        val previous = lastDispatchNanos.get()
                        val elapsed = previous.takeIf { it != Long.MIN_VALUE }
                            ?.let { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - it) }
                        lastDispatchNanos.set(System.nanoTime())
                        dispatchInLastProbe.incrementAndGet()
                        return elapsed
                    }

                    override fun elapsedSinceLastDispatchMillis(): Long? = lastDispatchNanos.get()
                        .takeIf { it != Long.MIN_VALUE }
                        ?.let { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - it) }

                    override fun millisUntilEligible(): Long {
                        val lastDispatch = lastDispatchNanos.get()
                        if (lastDispatch == Long.MIN_VALUE) return 0L
                        val remaining = lastDispatch + TimeUnit.MILLISECONDS.toNanos(retryIntervalMillis) - System.nanoTime()
                        return TimeUnit.NANOSECONDS.toMillis(remaining.coerceAtLeast(0L))
                    }
                }
                probe(gate)
                nextProbeAtNanos.set(
                    if (dispatchInLastProbe.get() > previousDispatchMarker) System.nanoTime()
                    else System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryIntervalMillis),
                )
            },
            initialDelayMillis,
            pollIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }
}
