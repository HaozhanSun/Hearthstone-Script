package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatchmakingQueueLifecycleTest {
    @Test
    fun `only current pid verified matchmaking starts persistent queue wait until create game`() {
        val queue = MatchmakingQueueLifecycle()
        assertEquals(
            MatchmakingQueueLifecycle.Phase.IDLE,
            queue.observeScreen(
                observedPid = 44L,
                currentPid = 45L,
                evidence = MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING,
                confidence = 95,
                captureAuthorized = true,
                processAlive = true,
                nowNanos = 1L,
            ).phase,
        )
        assertEquals(
            MatchmakingQueueLifecycle.Phase.IDLE,
            queue.observeScreen(
                observedPid = 45L,
                currentPid = 45L,
                evidence = MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING,
                confidence = 84,
                captureAuthorized = true,
                processAlive = true,
                nowNanos = 2L,
            ).phase,
        )
        assertEquals(
            MatchmakingQueueLifecycle.Phase.IDLE,
            queue.observeScreen(
                observedPid = 45L,
                currentPid = 45L,
                evidence = MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING,
                confidence = 95,
                captureAuthorized = false,
                processAlive = true,
                nowNanos = 3L,
            ).phase,
        )

        val confirmed = queue.observeScreen(
            observedPid = 45L,
            currentPid = 45L,
            evidence = MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING,
            confidence = 95,
            captureAuthorized = true,
            processAlive = true,
            nowNanos = 4L,
        )
        assertEquals(MatchmakingQueueLifecycle.Phase.QUEUE_PENDING, confirmed.phase)
        assertEquals(MatchmakingQueueLifecycle.TimeoutDisposition.DEFER_WITHOUT_INPUT, queue.timeoutDisposition(45L, true, 5L))
        assertTrue(queue.snapshotFor(45L, true, 5L).isPending)
        assertFalse(queue.snapshotFor(46L, true, 5L).isPending)
        assertFalse(queue.snapshotFor(45L, false, 5L).isPending)
        assertEquals(
            MatchmakingQueueLifecycle.TimeoutDisposition.RETRY,
            queue.timeoutDisposition(45L, true, 6L),
            "once the matching process is confirmed dead, its queue lease is cleared",
        )

        val activeQueue = MatchmakingQueueLifecycle()
        activeQueue.observeScreen(45L, 45L, MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING, 95, true, true, 4L)
        assertEquals(MatchmakingQueueLifecycle.Phase.GAME_CREATED, activeQueue.observeGameCreated(45L).phase)
        assertEquals(MatchmakingQueueLifecycle.TimeoutDisposition.STOP, activeQueue.timeoutDisposition(45L, true, 6L))
    }

    @Test
    fun `fresh verified queue exit and explicit error resolve the old timeout without input`() {
        val queue = MatchmakingQueueLifecycle()
        queue.observeScreen(45L, 45L, MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING, 95, true, true, 100L)
        assertEquals(
            MatchmakingQueueLifecycle.Phase.QUEUE_PENDING,
            queue.observeScreen(45L, 45L, MatchmakingQueueLifecycle.ScreenEvidence.UNKNOWN, 0, true, true, 50_000L).phase,
        )
        assertEquals(
            MatchmakingQueueLifecycle.Phase.QUEUE_TERMINAL,
            queue.observeScreen(45L, 45L, MatchmakingQueueLifecycle.ScreenEvidence.QUEUE_TERMINAL, 95, true, true, 50_001L).phase,
        )
        assertEquals(MatchmakingQueueLifecycle.TimeoutDisposition.STOP, queue.timeoutDisposition(45L, true, 50_002L))
        assertEquals(MatchmakingQueueLifecycle.TimeoutDisposition.RETRY, queue.timeoutDisposition(45L, false, 50_002L))
    }

    @Test
    fun `bounded monotonic deadline stops without input and cannot be extended by fresh screens`() {
        val queue = MatchmakingQueueLifecycle()
        val startedAt = 1_000L
        queue.observeScreen(45L, 45L, MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING, 95, true, true, startedAt)
        var lastObservation = startedAt
        val refreshInterval = MatchmakingQueueLifecycle.MAX_EVIDENCE_AGE_NANOS / 2L
        while (lastObservation + refreshInterval < MatchmakingQueueLifecycle.MAX_QUEUE_PENDING_NANOS - 1L) {
            lastObservation += refreshInterval
            queue.observeScreen(45L, 45L, MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING, 95, true, true, lastObservation)
        }
        val justBeforeDeadline = MatchmakingQueueLifecycle.MAX_QUEUE_PENDING_NANOS - 1L
        val beforeDeadline = queue.observeScreen(
            45L, 45L, MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING, 95, true, true, justBeforeDeadline,
        )
        assertEquals(startedAt, beforeDeadline.startedAtNanos, "fresh observations must not move the hard deadline")
        assertEquals(justBeforeDeadline, beforeDeadline.lastEvidenceAtNanos)
        assertEquals(
            MatchmakingQueueLifecycle.TimeoutDisposition.DEFER_WITHOUT_INPUT,
            queue.timeoutDisposition(45L, true, justBeforeDeadline),
        )
        assertEquals(
            MatchmakingQueueLifecycle.TimeoutDisposition.STOP,
            queue.timeoutDisposition(45L, true, MatchmakingQueueLifecycle.MAX_QUEUE_PENDING_NANOS + 1_000L),
        )
        assertEquals(
            MatchmakingQueueLifecycle.Phase.QUEUE_EXPIRED,
            queue.observeScreen(
                45L, 45L, MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING, 95, true, true,
                MatchmakingQueueLifecycle.MAX_QUEUE_PENDING_NANOS + 2_000L,
            ).phase,
            "same-screen refresh after deadline must not restart the bounded queue window",
        )
    }

    @Test
    fun `stale evidence expires and dead or replaced pid cannot refresh queue age`() {
        val queue = MatchmakingQueueLifecycle()
        queue.observeScreen(45L, 45L, MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING, 95, true, true, 1_000L)
        queue.observeScreen(45L, 45L, MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING, 95, true, false, 2_000L)
        assertEquals(
            MatchmakingQueueLifecycle.TimeoutDisposition.STOP,
            queue.timeoutDisposition(45L, true, MatchmakingQueueLifecycle.MAX_EVIDENCE_AGE_NANOS + 1_001L),
        )
        val fresh = MatchmakingQueueLifecycle()
        fresh.observeScreen(45L, 45L, MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING, 95, true, true, 1_000L)
        fresh.observeScreen(46L, 45L, MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING, 95, true, true, 20_000L)
        assertEquals(
            MatchmakingQueueLifecycle.TimeoutDisposition.STOP,
            fresh.timeoutDisposition(45L, true, MatchmakingQueueLifecycle.MAX_EVIDENCE_AGE_NANOS + 1_001L),
        )
        val dead = MatchmakingQueueLifecycle()
        dead.observeScreen(45L, 45L, MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING, 95, true, true, 1_000L)
        assertEquals(MatchmakingQueueLifecycle.Phase.IDLE, dead.snapshotFor(45L, false, 2_000L).phase)
        assertEquals(
            MatchmakingQueueLifecycle.TimeoutDisposition.RETRY,
            dead.timeoutDisposition(45L, true, 2_001L),
            "a later process reusing the PID must not inherit the stale queue lease",
        )
    }

    @Test
    fun `fresh same pid home observation converts expired queue to terminal instead of stale pending`() {
        val queue = MatchmakingQueueLifecycle()
        queue.observeScreen(45L, 45L, MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING, 95, true, true, 1_000L)
        val expired = queue.snapshotFor(
            45L,
            processAlive = true,
            nowNanos = MatchmakingQueueLifecycle.MAX_EVIDENCE_AGE_NANOS + 1_001L,
        )
        assertEquals(MatchmakingQueueLifecycle.Phase.QUEUE_EXPIRED, expired.phase)
        assertEquals(
            MatchmakingQueueLifecycle.Phase.QUEUE_TERMINAL,
            queue.observeScreen(
                observedPid = 45L,
                currentPid = 45L,
                evidence = MatchmakingQueueLifecycle.ScreenEvidence.QUEUE_TERMINAL,
                confidence = 100,
                captureAuthorized = true,
                processAlive = true,
                nowNanos = MatchmakingQueueLifecycle.MAX_EVIDENCE_AGE_NANOS + 2_000L,
            ).phase,
            "a fresh same-session HOME/deck screen is stronger exit evidence than elapsed queue age",
        )
        assertEquals(
            MatchmakingQueueLifecycle.TimeoutDisposition.STOP,
            queue.timeoutDisposition(45L, true, MatchmakingQueueLifecycle.MAX_EVIDENCE_AGE_NANOS + 3_000L),
        )
    }
}
