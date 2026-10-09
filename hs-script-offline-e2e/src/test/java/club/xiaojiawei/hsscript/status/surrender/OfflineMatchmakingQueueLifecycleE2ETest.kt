package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.status.MatchmakingQueueLifecycle
import club.xiaojiawei.hsscript.status.MatchmakingQueueModalVisualClassifier
import club.xiaojiawei.hsscript.status.PowerLogActiveMatchProbe
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
        val animatedBlankCancelFrame = fixture("v4.16.595-queue-search-modal-animation-blank-cancel.png")
        val deckSelectionFrame = fixture("v4.16.594-deck-selection-negative.png")
        val positive = MatchmakingQueueModalVisualClassifier.classify(liveQueueFrame)
        val animated = MatchmakingQueueModalVisualClassifier.classify(animatedBlankCancelFrame)
        val negative = MatchmakingQueueModalVisualClassifier.classify(deckSelectionFrame)
        assertTrue(positive.queueSearchModal, "real matchmaking modal must be visually recognized")
        assertTrue(animated.queueSearchModal, "animated queue reels and a blank cancel control remain positive")
        assertTrue(animated.cancelButtonWarmRatio < 0.34, "fixture must prove cancel fill is absent")
        assertTrue(animated.searchPanelRedRatio > 0.50)
        assertTrue(animated.searchHeaderWarmRatio > 0.24)
        assertFalse(negative.queueSearchModal, "deck selection background must not start/refresh queue state")
        assertTrue(negative.searchPanelRedRatio < 0.46)
        assertTrue(negative.searchHeaderWarmRatio < 0.20)

        // The evidence contract is normalized to the captured client frame,
        // not a fixed pixel resolution. Exercise the same composition at 2/3 scale.
        val resized = java.awt.image.BufferedImage(1280, 720, java.awt.image.BufferedImage.TYPE_INT_RGB)
        resized.createGraphics().also { graphics ->
            graphics.drawImage(animatedBlankCancelFrame, 0, 0, resized.width, resized.height, null)
            graphics.dispose()
        }
        assertTrue(MatchmakingQueueModalVisualClassifier.classify(resized).queueSearchModal)
        val resizedDeck = java.awt.image.BufferedImage(1280, 720, java.awt.image.BufferedImage.TYPE_INT_RGB)
        resizedDeck.createGraphics().also { graphics ->
            graphics.drawImage(deckSelectionFrame, 0, 0, resizedDeck.width, resizedDeck.height, null)
            graphics.dispose()
        }
        assertFalse(MatchmakingQueueModalVisualClassifier.classify(resizedDeck).queueSearchModal)

        val queue = MatchmakingQueueLifecycle()
        val currentPid = 104_164L
        var queueInputs = 0
        val preQueue = OfflinePreMatchRankQueuePermit.evaluate(
            OfflinePreMatchRankQueuePermit.Evidence(
                rank = 5,
                confidence = 0.99,
                capturedAtMs = 1_000L,
                outcome = OfflinePreMatchRankQueuePermit.OcrOutcome.SUCCESS,
            ),
            nowMs = 1_000L,
        )
        assertTrue(requireNotNull(preQueue.permit).dispatch(OfflinePreMatchRankQueuePermit.allowedRuntime(), 1_000L) { queueInputs++ })
        assertEquals(1, queueInputs, "only the fresh exact-rank permit may start queue tracking")
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
    fun `rank four deck-selection evidence prevents matchmaking before any queue state exists`() {
        val now = 10_000L
        var queueInputs = 0
        val authorization = OfflinePreMatchRankQueuePermit.evaluate(
            OfflinePreMatchRankQueuePermit.Evidence(
                rank = 4,
                confidence = 0.99,
                capturedAtMs = now,
                outcome = OfflinePreMatchRankQueuePermit.OcrOutcome.SUCCESS,
            ),
            now,
        )
        authorization.permit?.dispatch(OfflinePreMatchRankQueuePermit.allowedRuntime(), now) { queueInputs++ }
        assertFalse(authorization.allowed)
        assertEquals("rank-not-exact-5-or-10", authorization.reason)
        assertEquals(0, queueInputs, "rank four must not create a queue callback or an active-game lifecycle")
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
