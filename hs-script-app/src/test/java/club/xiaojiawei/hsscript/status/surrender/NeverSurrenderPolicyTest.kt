package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.status.DebugRunLease
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class NeverSurrenderPolicyTest {

    @Test
    fun `setting is effective only for beta-derived channels`() {
        assertTrue(NeverSurrenderPolicy.enabledForChannel("beta", true))
        assertTrue(NeverSurrenderPolicy.enabledForChannel(" BETA ", true))
        assertTrue(NeverSurrenderPolicy.enabledForChannel("release-candidate", true))
        assertFalse(NeverSurrenderPolicy.enabledForChannel("stable", true))
        assertFalse(NeverSurrenderPolicy.enabledForChannel("unknown", true))
        assertFalse(NeverSurrenderPolicy.enabledForChannel("beta", false))
    }

    @Test
    fun `temporary rank override requires beta never-surrender and active debug lease together`() {
        assertTrue(NeverSurrenderPolicy.temporaryRankOverrideActive("beta", true, true))
        assertFalse(NeverSurrenderPolicy.temporaryRankOverrideActive("release-candidate", true, true))
        assertFalse(NeverSurrenderPolicy.temporaryRankOverrideActive("stable", true, true))
        assertFalse(NeverSurrenderPolicy.temporaryRankOverrideActive("beta", false, true))
        assertFalse(NeverSurrenderPolicy.temporaryRankOverrideActive("beta", true, false))
        assertFalse(NeverSurrenderPolicy.temporaryRankOverrideActive("unknown", true, true))
    }

    @Test
    fun `unresolved rank is held only during the temporary override`() {
        assertTrue(
            NeverSurrenderPolicy.holdUnresolvedRankForTemporaryOverride(
                "rank-ocr-unresolved",
                overrideActive = true,
            ),
        )
        assertFalse(
            NeverSurrenderPolicy.holdUnresolvedRankForTemporaryOverride(
                "rank-ocr-unresolved",
                overrideActive = false,
            ),
        )
        assertFalse(
            NeverSurrenderPolicy.holdUnresolvedRankForTemporaryOverride(
                "current-rank-not-5-or-10",
                overrideActive = true,
            ),
            "a confidently read non-target rank continues to follow the rank policy",
        )
    }

    @Test
    fun `real debug lease state activates and expires the override at its monotonic deadline`() {
        var nowNanos = 10L
        val lease = DebugRunLease(
            maxDurationMillis = 100L,
            nanoTime = { nowNanos },
            wallClockMillis = { 1_000L },
        )
        assertEquals(DebugRunLease.State.ACTIVE, lease.enable().state)
        assertTrue(
            NeverSurrenderPolicy.temporaryRankOverrideActive(
                "beta", true, lease.snapshot().state == DebugRunLease.State.ACTIVE,
            ),
        )
        nowNanos += 100_000_000L
        val expiredState = lease.snapshot().state
        assertEquals(DebugRunLease.State.EXPIRED, expiredState)
        assertFalse(
            NeverSurrenderPolicy.temporaryRankOverrideActive(
                "beta", true, expiredState == DebugRunLease.State.ACTIVE,
            ),
        )
    }

    @Test
    fun `rank seven remains fail closed instead of being played when never surrender is enabled`() {
        assertTrue(NeverSurrenderPolicy.rankIsIneligible(7))
        assertTrue(NeverSurrenderPolicy.rankIsIneligible(233))
        assertTrue(NeverSurrenderPolicy.rankIsIneligible(0))
        assertFalse(NeverSurrenderPolicy.rankIsIneligible(5))
        assertFalse(NeverSurrenderPolicy.rankIsIneligible(10))
        assertTrue(SurrenderPolicy.evaluateCurrentRank(7)!!.shouldSurrender)
    }

    @Test
    fun `temporary authorization expires back to the known mandatory rank decision`() {
        SurrenderPolicy.resetForNewGame()
        val now = System.currentTimeMillis()
        val detection = CurrentRankDetector.Detection(
            rank = 8,
            tier = CurrentRankDetector.RankTier.DIAMOND,
            ocrText = "8",
            confidence = 0.99,
            captureBounds = java.awt.Rectangle(5, 6, 80, 90),
            provider = "PADDLEX",
            capturedAtMs = now,
            agreementCount = 1,
        )
        try {
            assertNull(
                SurrenderPolicy.evaluateMulliganRankEvidence(
                    detection = detection,
                    actualMode = "GAMEPLAY",
                    inWar = true,
                    nowMs = now,
                    persistentStreakDecision = null,
                    temporaryRankOverrideActive = true,
                ),
                "fresh rank 8 should continue only while the lease is active",
            )
            assertTrue(SurrenderPolicy.currentRankContinueAuthorized(temporaryRankOverrideActive = true))
            val activeDecision = SurrenderPolicy.currentRankContinuationDecision(temporaryRankOverrideActive = true)
            assertEquals("rank-continue-authorized", activeDecision?.ruleId)
            assertFalse(activeDecision?.shouldSurrender == true)

            assertFalse(SurrenderPolicy.currentRankContinueAuthorized(temporaryRankOverrideActive = false))
            val expiredDecision = SurrenderPolicy.currentRankContinuationDecision(temporaryRankOverrideActive = false)
            assertEquals("current-rank-not-5-or-10", expiredDecision?.ruleId)
            assertTrue(expiredDecision?.shouldSurrender == true)
            assertEquals(8, expiredDecision?.currentRank)
        } finally {
            SurrenderPolicy.resetForNewGame()
        }
    }

    @Test
    fun `mandatory rank rules are exempt from the ordinary never surrender kill switch`() {
        assertTrue(NeverSurrenderPolicy.isMandatoryRankRule("current-rank-not-5-or-10"))
        assertTrue(NeverSurrenderPolicy.isMandatoryRankRule("current-rank-is-not-silver-target"))
        assertTrue(NeverSurrenderPolicy.isMandatoryRankRule("rank-ocr-unresolved"))
        assertFalse(NeverSurrenderPolicy.isMandatoryRankRule("opponent-hero-not-original"))
        assertTrue(NeverSurrenderPolicy.isMandatoryRankDispatch("current-rank", "rank-ocr-unresolved"))
        assertTrue(
            NeverSurrenderPolicy.isMandatoryRankDispatch(
                "mulligan-rank-preflight",
                "current-rank-not-5-or-10",
            ),
        )
        assertFalse(NeverSurrenderPolicy.isMandatoryRankDispatch("opponent-hero", "rank-ocr-unresolved"))
        assertFalse(NeverSurrenderPolicy.isMandatoryRankDispatch("mulligan-rank-preflight", "opponent-hero-not-original"))
        assertFalse(NeverSurrenderPolicy.shouldBlock(enabled = true, mandatoryRank = true))
        assertTrue(NeverSurrenderPolicy.shouldBlock(enabled = true, mandatoryRank = false))
        assertFalse(NeverSurrenderPolicy.shouldBlock(enabled = false, mandatoryRank = false))
        assertFalse(NeverSurrenderPolicy.blockSurrender("rank-policy", mandatoryRank = true))
    }

    @Test
    fun `streak guard thresholds retain seven-concession block and five-win surrender semantics`() {
        val surrenderGuard = SurrenderPolicy.evaluatePersistentStreakGuard(
            PersistentStreakSnapshot(consecutiveSurrenders = 7, consecutiveWins = 0),
        )
        val winGuard = SurrenderPolicy.evaluatePersistentStreakGuard(
            PersistentStreakSnapshot(consecutiveSurrenders = 0, consecutiveWins = 5),
        )
        assertTrue(surrenderGuard?.reason?.contains("threshold=7") == true)
        assertTrue(winGuard?.reason?.contains("threshold=5") == true)
        assertFalse(SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 7, consecutiveWins = 0),
        )?.shouldSurrender == true)
        assertTrue(SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 0, consecutiveWins = 5),
        )?.shouldSurrender == true)
    }

    @Test
    fun `never surrender cannot bypass seven-concession surrender block`() {
        val decision = SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 7, consecutiveWins = 0),
        )!!

        assertFalse(decision.shouldSurrender)
        assertTrue(decision.blocksAutomaticSurrender)
        assertTrue(
            SurrenderPolicy.applyNeverSurrenderStreakPolicy(decision, neverSurrenderEnabled = true)
                ?.blocksAutomaticSurrender == true,
        )
    }

    @Test
    fun `beta setting is visible and uses the persisted config switch`() {
        val root = repositoryRoot()
        val fxml = Files.readString(root.resolve("hs-script-app/src/main/resources/fxml/settings/strategySettings.fxml"))
        val configEnum = Files.readString(root.resolve("hs-script-app/src/main/java/club/xiaojiawei/hsscript/enums/ConfigEnum.kt"))
        val gameUtil = Files.readString(root.resolve("hs-script-app/src/main/java/club/xiaojiawei/hsscript/utils/GameUtil.kt"))
        assertTrue(fxml.contains("Never Surrender（永不投降）"))
        assertTrue(fxml.contains("beta=\"true\""))
        assertTrue(fxml.contains("config=\"NEVER_SURRENDER\""))
        assertTrue(fxml.contains("对方英雄非原皮投降"))
        assertTrue(fxml.contains("config=\"OPPONENT_HERO_NON_ORIGINAL_SURRENDER\""))
        val surrenderSectionStart = fxml.indexOf("text=\"投降\"")
        val toggleLabel = fxml.indexOf("对方英雄非原皮投降")
        val surrenderSectionEnd = fxml.indexOf("</TitledPane>", surrenderSectionStart)
        assertTrue(surrenderSectionStart >= 0)
        assertTrue(toggleLabel > surrenderSectionStart && toggleLabel < surrenderSectionEnd)
        assertTrue(configEnum.contains("NEVER_SURRENDER"))
        assertTrue(configEnum.contains("OPPONENT_HERO_NON_ORIGINAL_SURRENDER"))
        assertTrue(gameUtil.contains("NeverSurrenderPolicy.blockSurrender"))
    }

    private fun repositoryRoot(): Path {
        val current = Path.of("").toAbsolutePath().normalize()
        return sequenceOf(current, current.parent)
            .first { Files.isRegularFile(it.resolve("release-channel.json")) }
    }
}
