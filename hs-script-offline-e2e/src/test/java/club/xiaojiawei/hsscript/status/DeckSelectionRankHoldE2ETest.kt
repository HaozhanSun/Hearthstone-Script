package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.status.surrender.RankEligibilityCorePolicy
import club.xiaojiawei.hsscript.status.surrender.RankEvidence
import club.xiaojiawei.hsscript.status.DeckSelectionRecoveryPolicy
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** Headless regression: rank-neutral queue, in-game numeric policy. */
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
        val unresolvedAttemptsBeforeAutomaticPause: Int,
        val recoveryPauseBoundMs: Long,
    )

    @Test
    fun `rank four badge does not block queue and is denied only after match starts`() {
        val fixture = readFixture()
        assertEquals("unchanged-constructed-deck-selection-rank4-badge-rejected-before-queue", fixture.scenario)
        assertEquals("", fixture.rankBadgeSmallRoiRaw, "the captured tight numeral ROI was empty")
        assertEquals("4x5", fixture.rankBadgeBigRoiRaw, "replay the observed full-badge OCR token")
        assertEquals(4, fixture.rank)
        assertTrue(fixture.rankEvidenceFresh)

        val now = 1_000_000L
        val preMatchQueue = MatchmakingGuardPolicy.authorizeQueueInput(
            working = true,
            paused = false,
            mandatoryRankSurrenderPending = false,
        )
        var queueInputs = 0
        assertTrue(MatchmakingGuardPolicy.dispatchIfAuthorized(preMatchQueue) { queueInputs++ })
        assertEquals(1, queueInputs, "the visible rank-4 badge is not evaluated before queue dispatch")

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

    private fun readFixture(): Fixture = Json.decodeFromString(
        javaClass.classLoader.getResourceAsStream(
            Path.of("offline-ocr", "screen-recovery", "deck-selection-rank4-hold.json")
                .toString().replace('\\', '/'),
        )?.bufferedReader()?.use { it.readText() } ?: error("Missing deck-selection rank-hold replay fixture"),
    )
}
