package club.xiaojiawei.hsscript.status

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DebugRunLeaseTest {
    @Test
    fun `default is disabled and gate does not change ordinary schedule`() {
        var nowNanos = 0L
        val lease = DebugRunLease(nanoTime = { nowNanos })

        assertEquals(DebugRunLease.State.DISABLED, lease.snapshot().state)
        assertFalse(DebugRunLease.effectiveCanWork(false, lease.isActive()))
        assertTrue(DebugRunLease.effectiveCanWork(true, lease.isActive()))
    }

    @Test
    fun `active lease bypasses closed schedule and expiry removes bypass`() {
        var nowNanos = 0L
        val lease = DebugRunLease(nanoTime = { nowNanos })

        assertFalse(DebugRunLease.effectiveCanWork(false, lease.isActiveWithoutExpiring()))

        val active = lease.enable(DebugRunLease.MAX_DURATION_MILLIS)
        assertEquals(DebugRunLease.State.ACTIVE, active.state)
        assertEquals(DebugRunLease.MAX_DURATION_MILLIS, active.remainingMillis)
        assertTrue(DebugRunLease.effectiveCanWork(false, lease.isActiveWithoutExpiring()))

        nowNanos = DebugRunLease.MAX_DURATION_MILLIS * 1_000_000L
        assertTrue(lease.expireIfNeeded())
        assertEquals(DebugRunLease.State.EXPIRED, lease.snapshot().state)
        assertFalse(DebugRunLease.effectiveCanWork(false, lease.isActiveWithoutExpiring()))
    }

    @Test
    fun `enable is capped and repeated enable does not renew deadline`() {
        var nowNanos = 10L
        val lease = DebugRunLease(nanoTime = { nowNanos }, wallClockMillis = { 1_000L })

        val first = lease.enable(DebugRunLease.MAX_DURATION_MILLIS * 2)
        nowNanos += 1_000_000_000L
        val second = lease.enable(1L)

        assertEquals(DebugRunLease.State.ACTIVE, first.state)
        assertEquals(first.endEpochMillis, second.endEpochMillis)
        assertEquals(DebugRunLease.MAX_DURATION_MILLIS - 1_000L, second.remainingMillis)
    }

    @Test
    fun `monotonic expiry is independent from wall clock jumps`() {
        var nowNanos = 0L
        var wallClock = 10_000L
        val expiredCallbacks = AtomicInteger()
        val lease = DebugRunLease(
            nanoTime = { nowNanos },
            wallClockMillis = { wallClock },
            onExpired = { expiredCallbacks.incrementAndGet() },
        )

        lease.enable(5_000L)
        wallClock = 1L
        nowNanos = 4_999_000_000L
        assertTrue(lease.isActive())
        nowNanos = 5_000_000_000L
        assertTrue(lease.expireIfNeeded())
        assertEquals(DebugRunLease.State.EXPIRED, lease.snapshot().state)
        assertEquals(1, expiredCallbacks.get())
    }

    @Test
    fun `disable cancels lease and stale expiry cannot reactivate it`() {
        var nowNanos = 0L
        val expiredCallbacks = AtomicInteger()
        val lease = DebugRunLease(
            nanoTime = { nowNanos },
            onExpired = { expiredCallbacks.incrementAndGet() },
        )

        lease.enable(5_000L)
        lease.disable()
        nowNanos = 10_000_000_000L

        assertEquals(DebugRunLease.State.DISABLED, lease.snapshot().state)
        assertFalse(lease.expireIfNeeded())
        assertEquals(0, expiredCallbacks.get())
    }

    @Test
    fun `restart clears live deadline and requires a new toggle`() {
        var nowNanos = 0L
        var wallClock = 100L
        val lease = DebugRunLease(nanoTime = { nowNanos }, wallClockMillis = { wallClock })

        val beforeRestart = lease.enable(30_000L)
        wallClock = 200L
        val afterRestart = lease.resetForRestart()

        assertNotEquals(0L, beforeRestart.endEpochMillis)
        assertEquals(DebugRunLease.State.DISABLED, afterRestart.state)
        assertEquals(0L, afterRestart.startEpochMillis)
        assertEquals(DebugRunLease.State.DISABLED, lease.snapshot().state)
    }

    @Test
    fun `concurrent enable requests share one deadline`() {
        var nowNanos = 0L
        val lease = DebugRunLease(nanoTime = { nowNanos })
        val results = Collections.synchronizedList(mutableListOf<DebugRunLease.Snapshot>())
        val ready = CountDownLatch(20)
        val start = CountDownLatch(1)
        val threads = List(20) {
            Thread {
                ready.countDown()
                start.await()
                results += lease.enable(30_000L)
            }.apply { start() }
        }

        ready.await()
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(1, results.map { it.endEpochMillis }.distinct().size)
        assertEquals(DebugRunLease.State.ACTIVE, lease.snapshot().state)
    }
}



