package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.status.MatchmakingQueueLifecycle
import club.xiaojiawei.hsscript.status.MatchmakingQueueModalVisualClassifier
import club.xiaojiawei.hsscript.status.PowerLogActiveMatchProbe
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.imageio.ImageIO

/** Offline replay of verified queue screen -> CREATE_GAME -> current-game rank handling. */
class OfflineMatchmakingQueueLifecycleE2ETest {
    @Test
    fun `incident queue modal screenshot overrides generic deck classification and survives empty log until mulligan`() {
        fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream(
            "/fixtures/matchmaking-queue/$name",
        )).use(ImageIO::read)

        val liveQueueFrame = fixture("v4.16.594-queue-search-modal.png")
        val deckSelectionFrame = fixture("v4.16.594-deck-selection-negative.png")
        val positive = MatchmakingQueueModalVisualClassifier.classify(liveQueueFrame)
        val negative = MatchmakingQueueModalVisualClassifier.classify(deckSelectionFrame)
        assertTrue(positive.queueSearchModal, "real matchmaking modal must be visually recognized")
        assertFalse(negative.queueSearchModal, "deck selection background must not start/refresh queue state")

        val queue = MatchmakingQueueLifecycle()
        val currentPid = 104_164L
        val queueInputs = 1 // The one already-authorized queue click from the run ledger.
        val emptyPowerLog = PowerLogActiveMatchProbe.assess(emptySequence())
        assertFalse(emptyPowerLog.gameCreated)

        val pending = queue.observeScreen(
            observedPid = currentPid,
            currentPid = currentPid,
            evidence = MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING,
            confidence = 96,
            captureAuthorized = true,
            processAlive = true,
            nowNanos = 1_000_000_000L,
        )
        assertEquals(MatchmakingQueueLifecycle.Phase.QUEUE_PENDING, pending.phase)
        repeat(44) { attempt ->
            val observationAt = 2_000_000_000L + attempt * 1_000_000_000L
            queue.observeScreen(
                observedPid = currentPid,
                currentPid = currentPid,
                evidence = MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING,
                confidence = 96,
                captureAuthorized = true,
                processAlive = true,
                nowNanos = observationAt,
            )
            assertEquals(
                MatchmakingQueueLifecycle.TimeoutDisposition.DEFER_WITHOUT_INPUT,
                queue.timeoutDisposition(currentPid, true, observationAt),
                "fresh visually verified modal must survive startup probe attempt $attempt",
            )
        }
        assertEquals(1, queueInputs, "passive visual confirmation never repeats the matchmaking click")

        val createGame = PowerLogActiveMatchProbe.assess(sequenceOf("CREATE_GAME", "BEGIN_MULLIGAN"))
        assertTrue(createGame.gameCreated)
        queue.observeGameCreated(currentPid)
        assertEquals(
            MatchmakingQueueLifecycle.Phase.GAME_CREATED,
            queue.snapshotFor(currentPid, true, 47_000_000_000L).phase,
        )
        assertEquals(
            MatchmakingQueueLifecycle.TimeoutDisposition.STOP,
            queue.timeoutDisposition(currentPid, true, 47_000_000_000L),
            "authoritative CREATE_GAME/mulligan ends startup queue waiting instead of destructive relaunch",
        )

        val rankFive = RankEligibilityCorePolicy.evaluate(
            evidence = RankEvidence(5, 0.99, 104, 108, "PADDLEX", 48_000L, 1),
            expectedMode = "GAMEPLAY",
            actualMode = "GAMEPLAY",
            expectedInWar = true,
            inWar = true,
            nowMs = 48_000L,
        )
        assertTrue(rankFive.eligible, "active rank preflight proceeds once authoritative match evidence exists")
    }

    @Test
    fun `queue OCR with empty Power log waits without another input then rank four arms active-game surrender`() {
        val queue = MatchmakingQueueLifecycle()
        val currentPid = 97_212L
        var queueInputs = 0
        val authorization = MatchmakingGuardPolicy.authorizeQueueInput(
            working = true,
            paused = false,
            mandatoryRankSurrenderPending = false,
        )
        assertTrue(MatchmakingGuardPolicy.dispatchIfAuthorized(authorization) { queueInputs++ })
        assertEquals(1, queueInputs, "the normal queue callback is sent once")

        val emptyLog = PowerLogActiveMatchProbe.assess(emptySequence())
        assertFalse(emptyLog.gameCreated)
        assertEquals(
            MatchmakingQueueLifecycle.Phase.QUEUE_PENDING,
            queue.observeScreen(
                observedPid = currentPid,
                currentPid = currentPid,
                evidence = MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING,
                confidence = 95,
                captureAuthorized = true,
                processAlive = true,
                nowNanos = 1_000L,
            ).phase,
        )
        repeat(49) { attempt ->
            assertEquals(
                MatchmakingQueueLifecycle.TimeoutDisposition.DEFER_WITHOUT_INPUT,
                queue.timeoutDisposition(currentPid, processAlive = true, nowNanos = 2_000L + attempt),
                "passive probe $attempt must not timeout or issue cancellation/retry input",
            )
        }
        assertEquals(1, queueInputs, "screen observations cannot synthesize extra queue clicks")

        val createGame = PowerLogActiveMatchProbe.assess(sequenceOf("CREATE_GAME"))
        assertTrue(createGame.gameCreated)
        queue.observeGameCreated(currentPid)
        assertEquals(MatchmakingQueueLifecycle.Phase.GAME_CREATED, queue.snapshotFor(currentPid, true, 3_000L).phase)
        assertEquals(MatchmakingQueueLifecycle.TimeoutDisposition.STOP, queue.timeoutDisposition(currentPid, true, 3_000L))

        val now = 10_000L
        val rankFour = RankEligibilityCorePolicy.evaluate(
            evidence = RankEvidence(4, 0.99, 104, 108, "PADDLEX", now, 1),
            expectedMode = "GAMEPLAY",
            actualMode = "GAMEPLAY",
            expectedInWar = true,
            inWar = true,
            nowMs = now,
        )
        assertFalse(rankFour.eligible)
        assertEquals("rank-not-5-or-10-or-legendary-20-plus", rankFour.reason)
        val rankTicket = MulliganRankDispatchBarrier.beginCurrentGame()
        assertNotNull(MulliganRankDispatchBarrier.requireSurrender(rankTicket))
        assertEquals(MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED, MulliganRankDispatchBarrier.currentState())
    }

    @Test
    fun `queue error or visible cancellation resolves old timer while unknown evidence preserves wait`() {
        val queue = MatchmakingQueueLifecycle()
        val pid = 97_212L
        queue.observeScreen(pid, pid, MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING, 95, true, true, 1L)
        assertEquals(
            MatchmakingQueueLifecycle.Phase.QUEUE_PENDING,
            queue.observeScreen(pid, pid, MatchmakingQueueLifecycle.ScreenEvidence.UNKNOWN, 0, true, true, 50_000L).phase,
        )
        assertEquals(MatchmakingQueueLifecycle.TimeoutDisposition.DEFER_WITHOUT_INPUT, queue.timeoutDisposition(pid, true, 50_000L))
        queue.observeScreen(pid, pid, MatchmakingQueueLifecycle.ScreenEvidence.QUEUE_TERMINAL, 95, true, true, 50_001L)
        assertEquals(MatchmakingQueueLifecycle.Phase.QUEUE_TERMINAL, queue.snapshotFor(pid, true, 50_002L).phase)
        assertEquals(MatchmakingQueueLifecycle.TimeoutDisposition.STOP, queue.timeoutDisposition(pid, true, 50_002L))
    }
}
