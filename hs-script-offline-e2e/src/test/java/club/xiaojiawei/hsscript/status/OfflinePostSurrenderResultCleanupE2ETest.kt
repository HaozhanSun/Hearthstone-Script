package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.utils.TerminalPageCleanupCoordinator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Offline replay of terminal surrender -> stuck result -> bounded cleanup retry. */
class OfflinePostSurrenderResultCleanupE2ETest {
    @Serializable
    private data class Fixture(
        val scenario: String,
        val rank: Int,
        val terminalSurrender: Boolean,
        val ownPlayState: String,
        val opponentPlayState: String,
        val powerLogConcededAt: String,
        val powerLogSameGameTerminalProof: Boolean,
        val finalGameOver: Boolean,
        val sourceScreenshotPath: String,
        val resultScreenKind: String,
        val cleanupInputsBeforeDeadline: Int,
        val cleanupProbesBeforeDeadline: Int,
        val inputDispatchAccepted: Boolean,
        val episodeDeadlineMillis: Long,
        val retryIntervalMillis: Long,
        val freshRetryCaptureAuthorized: Boolean,
        val freshRetryResultVisible: Boolean,
        val retryTerminalCapabilityValid: Boolean,
        val postRetryDestinationKind: String,
        val postRetryDestinationConfidence: Int,
        val postRetryDestinationFresh: Boolean,
    )

    @Test
    fun `same-game terminal surrender resumes expired cleanup only with fresh result and finishes on deck transition`() {
        val fixture = fixture()
        assertEquals("same-session-rank-surrender-lost-result-remains-visible-after-cleanup-deadline", fixture.scenario)
        assertTrue(fixture.terminalSurrender)
        assertEquals(4, fixture.rank)
        assertEquals("LOST", fixture.ownPlayState)
        assertEquals("WON", fixture.opponentPlayState)
        assertEquals("02:20:50", fixture.powerLogConcededAt)
        assertTrue(fixture.powerLogSameGameTerminalProof)
        assertTrue(fixture.finalGameOver)
        assertTrue(fixture.sourceScreenshotPath.endsWith("debug-20261006-022346-702-screen-recovery-18aabd8a-b513-4771-8d16-6aabc517de4b.png"))
        assertEquals("RESULT", fixture.resultScreenKind)
        assertFalse(fixture.inputDispatchAccepted, "an input log/result is not client acceptance evidence")

        var now = 0L
        val coordinator = TerminalPageCleanupCoordinator(
            maxDurationMillis = fixture.episodeDeadlineMillis,
            monotonicTimeMillis = { now },
        )
        val first = requireNotNull(coordinator.begin().ticket)
        repeat(fixture.cleanupProbesBeforeDeadline) { index ->
            assertEquals(index + 1, coordinator.nextProbe(first))
            if (index < fixture.cleanupInputsBeforeDeadline) {
                assertEquals(index + 1, coordinator.reserveInput(first))
            }
        }
        assertEquals(fixture.cleanupInputsBeforeDeadline, coordinator.snapshot().inputs)
        assertEquals(fixture.cleanupProbesBeforeDeadline, coordinator.snapshot().probes)

        now = fixture.episodeDeadlineMillis
        val deadline = coordinator.begin()
        assertEquals(TerminalPageCleanupCoordinator.BeginState.FAILED, deadline.state)
        assertEquals("episode-deadline-exceeded", coordinator.snapshot().failureReason)

        // Unsafe or stale observations reject renewal without consuming a new
        // ticket, probe, or input: pause, stale game, foreground/occlusion /
        // authority loss, non-result, or UNKNOWN.
        val failedTicket = requireNotNull(deadline.ticket)
        assertNull(coordinator.rearmAfterDeadline(failedTicket, paused = false, terminalCleanupAuthorized = false, freshCaptureAuthorized = true, resultPageVisible = true), "stale game/terminal capability")
        assertNull(coordinator.rearmAfterDeadline(failedTicket, paused = false, terminalCleanupAuthorized = true, freshCaptureAuthorized = false, resultPageVisible = true), "foreground, occlusion, or capture authority lost")
        assertNull(coordinator.rearmAfterDeadline(failedTicket, paused = false, terminalCleanupAuthorized = true, freshCaptureAuthorized = true, resultPageVisible = false), "fresh non-result observation")
        assertNull(coordinator.rearmAfterDeadline(failedTicket, paused = false, terminalCleanupAuthorized = true, freshCaptureAuthorized = true, resultPageVisible = null), "missing/UNKNOWN result proof")
        assertNull(coordinator.rearmAfterDeadline(failedTicket, paused = true, terminalCleanupAuthorized = true, freshCaptureAuthorized = true, resultPageVisible = true), "pause blocks cleanup")

        val retry = requireNotNull(
            coordinator.rearmAfterDeadline(
                failedTicket,
                paused = false,
                terminalCleanupAuthorized = fixture.retryTerminalCapabilityValid,
                freshCaptureAuthorized = fixture.freshRetryCaptureAuthorized,
                resultPageVisible = fixture.freshRetryResultVisible,
            ),
        )
        assertEquals(fixture.cleanupInputsBeforeDeadline, coordinator.snapshot().inputs, "prior/rejected sends are not refunded")
        assertEquals(fixture.cleanupProbesBeforeDeadline, coordinator.snapshot().probes, "probe budget remains cumulative")
        assertEquals(1, coordinator.snapshot().failedEpisodeRearms, "only one deadline retry is permitted")
        assertNull(coordinator.nextProbe(first), "deadline-expired worker cannot run after rearm")
        assertEquals(fixture.cleanupProbesBeforeDeadline + 1, coordinator.nextProbe(retry))
        assertEquals(25_000L, fixture.retryIntervalMillis, "live retry worker remains deliberately paced")
        assertEquals(fixture.cleanupInputsBeforeDeadline + 1, coordinator.reserveInput(retry))

        val noTransitionProof = FreshPostResultDestinationPolicy.isConfirmed(
            screenKind = null,
            confidence = 0,
            freshCaptureAuthorized = true,
        )
        assertFalse(noTransitionProof)
        assertEquals(TerminalPageCleanupCoordinator.State.RUNNING, coordinator.snapshot().state)

        val confirmedDeckTransition = FreshPostResultDestinationPolicy.isConfirmed(
            screenKind = fixture.postRetryDestinationKind,
            confidence = fixture.postRetryDestinationConfidence,
            freshCaptureAuthorized = fixture.postRetryDestinationFresh,
        )
        assertTrue(confirmedDeckTransition)
        assertTrue(coordinator.confirmDestination(retry), "completion occurs only after fresh destination policy accepts")
        assertEquals(TerminalPageCleanupCoordinator.State.COMPLETED, coordinator.snapshot().state)
        assertEquals(3, coordinator.snapshot().inputs)

        // Wrong destination, stale frame, low confidence, or unauthorized
        // capture is not transition proof and cannot release the episode.
        assertFalse(FreshPostResultDestinationPolicy.isConfirmed("HOME", 92, true))
        assertFalse(FreshPostResultDestinationPolicy.isConfirmed("DECK_SELECTION", 84, true))
        assertFalse(FreshPostResultDestinationPolicy.isConfirmed("DECK_SELECTION", 92, false))
        assertFalse(FreshPostResultDestinationPolicy.isConfirmed("RESULT", 99, true))
    }

    private fun fixture(): Fixture = requireNotNull(
        javaClass.getResourceAsStream("/offline-ocr/rank-surrender/post-surrender-result-deadline-retry.json"),
    ).bufferedReader().use { Json.decodeFromString<Fixture>(it.readText()) }

}
