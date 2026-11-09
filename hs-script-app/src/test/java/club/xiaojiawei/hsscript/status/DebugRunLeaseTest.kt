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

        // Recovery from a bad/expired persisted lease never auto-arms. The
        // explicit user toggle is the only action that may create a new cap.
        val explicitlyRearmed = lease.enable()
        assertEquals(DebugRunLease.State.ACTIVE, explicitlyRearmed.state)
        assertEquals(DebugRunLease.MAX_DURATION_MILLIS, explicitlyRearmed.remainingMillis)
        assertEquals(200L + DebugRunLease.MAX_DURATION_MILLIS, explicitlyRearmed.endEpochMillis)
    }

    @Test
    fun `restart can arm a fresh default thirty minute lease`() {
        var nowNanos = 0L
        var wallClock = 100L
        val lease = DebugRunLease(nanoTime = { nowNanos }, wallClockMillis = { wallClock })

        val beforeRestart = lease.enable(30_000L)
        nowNanos = 5_000_000_000L
        wallClock = 10_000L
        val afterRestart = lease.resetForRestart(defaultEnabled = true)

        assertEquals(DebugRunLease.State.ACTIVE, afterRestart.state)
        assertEquals(DebugRunLease.MAX_DURATION_MILLIS, afterRestart.remainingMillis)
        assertEquals(10_000L, afterRestart.startEpochMillis)
        assertEquals(10_000L + DebugRunLease.MAX_DURATION_MILLIS, afterRestart.endEpochMillis)
        assertNotEquals(beforeRestart.endEpochMillis, afterRestart.endEpochMillis)
    }

    @Test
    fun `restoring after process restart preserves original deadline instead of extending run`() {
        var nowNanos = 25L * 60L * 1_000_000_000L
        var nowMillis = 1_000L + 25L * 60L * 1_000L
        val lease = DebugRunLease(
            nanoTime = { nowNanos },
            wallClockMillis = { nowMillis },
        )
        val originalStart = 1_000L
        val originalEnd = originalStart + DebugRunLease.MAX_DURATION_MILLIS

        val restored = lease.restoreAbsoluteWindow(originalStart, originalEnd)

        assertEquals(DebugRunLease.State.ACTIVE, restored.state)
        assertEquals(originalStart, restored.startEpochMillis)
        assertEquals(originalEnd, restored.endEpochMillis)
        assertEquals(5L * 60L * 1_000L, restored.remainingMillis)
        nowNanos += 5L * 60L * 1_000_000_000L
        nowMillis = originalEnd
        assertEquals(DebugRunLease.State.EXPIRED, lease.snapshot().state)
    }

    @Test
    fun `expired restored run remains expired and cannot be auto rearmed`() {
        val originalStart = 1_000L
        val originalEnd = originalStart + DebugRunLease.MAX_DURATION_MILLIS
        val lease = DebugRunLease(
            nanoTime = { 40L * 60L * 1_000_000_000L },
            wallClockMillis = { originalEnd + 10L },
        )

        val restored = lease.restoreAbsoluteWindow(originalStart, originalEnd)

        assertEquals(DebugRunLease.State.EXPIRED, restored.state)
        assertEquals(0L, restored.remainingMillis)
        assertEquals(DebugRunLease.State.EXPIRED, lease.snapshot().state)
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



