package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscript.status.DebugRunLease
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Rectangle

class BetaNeverSurrenderOverrideTest {
    private val startedAt = 1_000_000L

    @Test
    fun `fresh verified numeric rank temporarily continues and expiry restores normal rank decision`() {
        val lease = lease(startedAt)
        val override = BetaNeverSurrenderOverride(
            betaBuild = true,
            neverSurrenderEnabled = { true },
            debugRunLease = { lease },
        )
        val rankNine = detection(rank = 9, capturedAt = startedAt + 1_000)
        val expiry = lease.endEpochMillis

        assertTrue(override.active(expiry - 1))
        assertTrue(override.allowsTemporaryContinue(rankNine, startedAt + 1_001))
        assertFalse(override.active(expiry))
        assertFalse(override.allowsTemporaryContinue(rankNine, expiry))

        SurrenderPolicy.resetForNewGame()
        try {
            val expiredDecision = SurrenderPolicy.evaluateMulliganRankEvidence(
                detection = detection(rank = 9, capturedAt = expiry - 1_000),
                actualMode = ModeEnum.GAMEPLAY.name,
                inWar = true,
                nowMs = expiry,
                persistentStreakDecision = null,
                betaOverride = override,
            )
            assertTrue(expiredDecision?.shouldSurrender == true)
            assertFalse(SurrenderPolicy.currentRankContinueAuthorized())
        } finally {
            SurrenderPolicy.resetForNewGame()
        }
    }

    @Test
    fun `override requires beta never surrender setting active capped lease and fresh strong numeric rank proof`() {
        val activeLease = lease(startedAt)
        val beta = BetaNeverSurrenderOverride(true, { true }, { activeLease })
        val toggleOff = BetaNeverSurrenderOverride(true, { false }, { activeLease })
        val nonBeta = BetaNeverSurrenderOverride(false, { true }, { activeLease })
        val inactiveLease = BetaNeverSurrenderOverride(true, { true }, { lease(startedAt, DebugRunLease.State.DISABLED) })
        val shortenedLease = BetaNeverSurrenderOverride(
            true,
            { true },
            { lease(startedAt, durationMs = DebugRunLease.MAX_DURATION_MILLIS - 1L) },
        )
        val now = startedAt + 1_000

        assertTrue(beta.allowsTemporaryContinue(detection(9, now), now))
        assertFalse(toggleOff.allowsTemporaryContinue(detection(9, now), now))
        assertFalse(nonBeta.allowsTemporaryContinue(detection(9, now), now))
        assertFalse(inactiveLease.allowsTemporaryContinue(detection(9, now), now))
        assertFalse(shortenedLease.allowsTemporaryContinue(detection(9, now), now))
        assertFalse(beta.allowsTemporaryContinue(null, now))
        assertFalse(beta.allowsTemporaryContinue(detection(null, now), now))
        assertFalse(beta.allowsTemporaryContinue(detection(11, now), now))
        assertFalse(beta.allowsTemporaryContinue(detection(9, now - 1 - BetaNeverSurrenderOverride.MAX_RANK_AGE_MS), now))
        assertFalse(beta.allowsTemporaryContinue(detection(9, now + 1), now))
        assertFalse(beta.allowsTemporaryContinue(detection(9, now, provider = "UNKNOWN"), now))
        assertFalse(beta.allowsTemporaryContinue(detection(9, now, agreementCount = 1, provider = "LEGACY"), now))
    }

    @Test
    fun `integrated mulligan policy does not bypass unresolved rank evidence`() {
        val override = BetaNeverSurrenderOverride(true, { true }, { lease(startedAt) })
        SurrenderPolicy.resetForNewGame()
        try {
            val result = SurrenderPolicy.evaluateMulliganRankEvidence(
                detection = detection(rank = null, capturedAt = startedAt + 1_000),
                actualMode = ModeEnum.GAMEPLAY.name,
                inWar = true,
                nowMs = startedAt + 1_000,
                persistentStreakDecision = null,
                betaOverride = override,
            )
            assertTrue(result == null || result.shouldSurrender.not())
            assertFalse(SurrenderPolicy.currentRankContinueAuthorized())
        } finally {
            SurrenderPolicy.resetForNewGame()
        }
    }

    private fun detection(
        rank: Int?,
        capturedAt: Long,
        provider: String = "PADDLEX",
        agreementCount: Int = 1,
        confidence: Double? = 0.95,
    ) = CurrentRankDetector.Detection(
        rank = rank,
        tier = CurrentRankDetector.RankTier.DIAMOND,
        ocrText = rank?.toString().orEmpty(),
        confidence = confidence,
        captureBounds = Rectangle(0, 0, 100, 100),
        provider = provider,
        capturedAtMs = capturedAt,
        agreementCount = agreementCount,
    )

    private fun lease(
        start: Long,
        state: DebugRunLease.State = DebugRunLease.State.ACTIVE,
        durationMs: Long = DebugRunLease.MAX_DURATION_MILLIS,
    ) = DebugRunLease.Snapshot(
        state = state,
        startEpochMillis = start,
        endEpochMillis = start + durationMs,
        remainingMillis = if (state == DebugRunLease.State.ACTIVE) durationMs else 0L,
    )
}
