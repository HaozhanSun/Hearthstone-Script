package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.status.surrender.RankEligibilityCorePolicy
import club.xiaojiawei.hsscript.status.surrender.RankEvidence
import club.xiaojiawei.hsscript.status.surrender.OfflinePreMatchRankQueuePermit
import club.xiaojiawei.hsscript.status.DeckSelectionRecoveryPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.imageio.ImageIO
import java.nio.file.Path

/** Headless regression: fresh deck-selection rank permit, plus in-game defense in depth. */
class DeckSelectionRankHoldE2ETest {
    @Serializable
    private data class Fixture(
        val scenario: String,
        val screenKind: String,
        val mode: String,
        val phase: String,
        val rankBadgeSmallRoiRaw: String,
        val rankBadgeBigRoiRaw: String,
        val rank: Int,
        val confidence: Double,
        val provider: String,
        val rankEvidenceFresh: Boolean,
        val unchangedFingerprint: String,
        val screenshot: String,
        val decisionTrace: String,
        val unresolvedAttemptsBeforeAutomaticPause: Int,
        val recoveryPauseBoundMs: Long,
    )

    @Test
    fun `rank four deck selection evidence blocks queue before any input`() {
        val fixture = readFixture()
        assertEquals("fresh-constructed-deck-selection-rank4-badge-blocked-before-queue", fixture.scenario)
        assertEquals("", fixture.rankBadgeSmallRoiRaw, "the captured tight numeral ROI was empty")
        assertEquals("4x5", fixture.rankBadgeBigRoiRaw, "replay the observed full-badge OCR token")
        assertEquals(4, fixture.rank)
        assertTrue(fixture.rankEvidenceFresh)

        val screenshot = requireNotNull(
            javaClass.classLoader.getResourceAsStream(
                Path.of("offline-ocr", "screen-recovery", fixture.screenshot).toString().replace('\\', '/'),
            ),
        ).use { requireNotNull(ImageIO.read(it)) }
        assertTrue(screenshot.width > 100 && screenshot.height > 100, "retained deck-selection image must be decodable")
        val trace = requireNotNull(
            javaClass.classLoader.getResourceAsStream(
                Path.of("offline-ocr", "screen-recovery", fixture.decisionTrace).toString().replace('\\', '/'),
            ),
        ).bufferedReader().use { it.readText() }
        assertTrue(trace.contains("rank=4") && trace.contains("action=NO_QUEUE_INPUT"))

        val now = 1_000_000L
        val preMatchQueue = OfflinePreMatchRankQueuePermit.evaluate(
            OfflinePreMatchRankQueuePermit.Evidence(
                rank = fixture.rank,
                confidence = fixture.confidence,
                capturedAtMs = now,
                outcome = OfflinePreMatchRankQueuePermit.OcrOutcome.SUCCESS,
                mode = fixture.mode,
                phase = "DECK_SELECTION",
            ),
            now,
        )
        var queueInputs = 0
        assertFalse(preMatchQueue.allowed)
        assertEquals("rank-not-exact-5-or-10", preMatchQueue.reason)
        assertEquals(0, queueInputs, "the visible rank-4 badge must block all queue input")

        val inGameRank = RankEligibilityCorePolicy.evaluate(
            evidence = RankEvidence(
                rank = fixture.rank,
                confidence = fixture.confidence,
                captureWidth = 106,
                captureHeight = 106,
                provider = fixture.provider,
                capturedAtMs = now,
                agreementCount = 1,
            ),
            expectedMode = fixture.mode,
            actualMode = fixture.mode,
            expectedInWar = true,
            inWar = true,
            nowMs = now,
        )
        assertFalse(inGameRank.eligible)
        assertEquals("rank-not-5-or-10", inGameRank.reason)

        assertFalse(DeckSelectionRecoveryPolicy.shouldApply(fixture.screenKind, fixture.mode, fixture.phase))
        assertTrue(fixture.unresolvedAttemptsBeforeAutomaticPause >= 1)
        assertTrue(fixture.recoveryPauseBoundMs <= 60_000L)
        assertEquals(
            "DECK_SELECTION|TOURNAMENT|FILL_DECK|rank-denied",
            fixture.unchangedFingerprint,
        )
    }

    @Test
    fun `only fresh exact five or ten dispatch once while all unsafe evidence dispatches zero`() {
        val now = 2_000_000L
        val negative = listOf(
            OfflinePreMatchRankQueuePermit.Evidence(4, 0.99, now, OfflinePreMatchRankQueuePermit.OcrOutcome.SUCCESS),
            OfflinePreMatchRankQueuePermit.Evidence(6, 0.99, now, OfflinePreMatchRankQueuePermit.OcrOutcome.SUCCESS),
            OfflinePreMatchRankQueuePermit.Evidence(9, 0.99, now, OfflinePreMatchRankQueuePermit.OcrOutcome.SUCCESS),
            OfflinePreMatchRankQueuePermit.Evidence(11, 0.99, now, OfflinePreMatchRankQueuePermit.OcrOutcome.SUCCESS),
            OfflinePreMatchRankQueuePermit.Evidence(21, 0.99, now, OfflinePreMatchRankQueuePermit.OcrOutcome.SUCCESS),
            OfflinePreMatchRankQueuePermit.Evidence(null, null, now, OfflinePreMatchRankQueuePermit.OcrOutcome.UNKNOWN),
            OfflinePreMatchRankQueuePermit.Evidence(5, 0.94, now, OfflinePreMatchRankQueuePermit.OcrOutcome.SUCCESS),
            OfflinePreMatchRankQueuePermit.Evidence(5, 0.99, now, OfflinePreMatchRankQueuePermit.OcrOutcome.FAILURE),
            OfflinePreMatchRankQueuePermit.Evidence(5, 0.99, now, OfflinePreMatchRankQueuePermit.OcrOutcome.CANCELLED),
            OfflinePreMatchRankQueuePermit.Evidence(5, 0.99, now - 5_001L, OfflinePreMatchRankQueuePermit.OcrOutcome.SUCCESS),
            OfflinePreMatchRankQueuePermit.Evidence(5, 0.99, now, OfflinePreMatchRankQueuePermit.OcrOutcome.SUCCESS, mode = "STANDARD"),
            OfflinePreMatchRankQueuePermit.Evidence(5, 0.99, now, OfflinePreMatchRankQueuePermit.OcrOutcome.SUCCESS, phase = "REPLACE_CARD"),
        )
        negative.forEach { evidence ->
            val decision = OfflinePreMatchRankQueuePermit.evaluate(evidence, now)
            var inputs = 0
            decision.permit?.dispatch(OfflinePreMatchRankQueuePermit.allowedRuntime(), now) { inputs++ }
            assertFalse(decision.allowed, "evidence=$evidence")
            assertEquals(0, inputs, "evidence=$evidence")
        }
        for (rank in listOf(5, 10)) {
            val decision = OfflinePreMatchRankQueuePermit.evaluate(
                OfflinePreMatchRankQueuePermit.Evidence(rank, 0.99, now, OfflinePreMatchRankQueuePermit.OcrOutcome.SUCCESS), now,
            )
            var inputs = 0
            assertTrue(requireNotNull(decision.permit).dispatch(OfflinePreMatchRankQueuePermit.allowedRuntime(), now) { inputs++ })
            assertFalse(decision.permit.dispatch(OfflinePreMatchRankQueuePermit.allowedRuntime(), now) { inputs++ }, "permit must be single-use")
            assertEquals(1, inputs, "rank=$rank")
        }
    }

    @Test
    fun `runtime changes after a valid permit was issued still produce zero queue input`() {
        val now = 3_000_000L
        val changedRuntimes = listOf(
            OfflinePreMatchRankQueuePermit.allowedRuntime().copy(working = false),
            OfflinePreMatchRankQueuePermit.allowedRuntime().copy(paused = true),
            OfflinePreMatchRankQueuePermit.allowedRuntime().copy(mandatorySurrenderPending = true),
            OfflinePreMatchRankQueuePermit.allowedRuntime().copy(mode = "STANDARD"),
            OfflinePreMatchRankQueuePermit.allowedRuntime().copy(inWar = true),
        )
        changedRuntimes.forEach { runtime ->
            val permit = requireNotNull(
                OfflinePreMatchRankQueuePermit.evaluate(
                    OfflinePreMatchRankQueuePermit.Evidence(5, 0.99, now, OfflinePreMatchRankQueuePermit.OcrOutcome.SUCCESS),
                    now,
                ).permit,
            )
            var inputs = 0
            assertFalse(permit.dispatch(runtime, now) { inputs++ }, "runtime=$runtime")
            assertEquals(0, inputs, "runtime=$runtime")
        }
    }

    private fun readFixture(): Fixture = Json.decodeFromString(
        javaClass.classLoader.getResourceAsStream(
            Path.of("offline-ocr", "screen-recovery", "deck-selection-rank4-hold.json")
                .toString().replace('\\', '/'),
        )?.bufferedReader()?.use { it.readText() } ?: error("Missing deck-selection rank-hold replay fixture"),
    )
}
