package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscript.status.DebugScreenshotRing
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.awt.Color
import java.awt.image.BufferedImage

class SurrenderPolicyTest {

    @Test
    fun surrenderStateRequiresGameplayOrActiveWar() {
        assertFalse(SurrenderPolicy.hasConfirmedGameState(null, false))
        assertFalse(SurrenderPolicy.hasConfirmedGameState(ModeEnum.HUB, false))
        assertTrue(SurrenderPolicy.hasConfirmedGameState(ModeEnum.GAMEPLAY, false))
        assertTrue(SurrenderPolicy.hasConfirmedGameState(null, true))
    }

    @Test
    fun originalHunterHeroIsEligible() {
        val result = SurrenderPolicy.evaluateOpponentHeroName("雷克萨")

        assertTrue(result.matched)
        assertFalse(result.shouldSurrender)
    }

    @Test
    fun originalHeroFullLocalizedNameIsEligible() {
        val result = SurrenderPolicy.evaluateOpponentHeroName("玛法里奥·怒风")

        assertTrue(result.matched)
        assertFalse(result.shouldSurrender)
    }

    @Test
    fun defaultDeathKnightLichKingIsEligibleByName() {
        val result = SurrenderPolicy.evaluateOpponentHeroName("巫妖王")

        assertTrue(result.matched)
        assertFalse(result.shouldSurrender)
    }

    @Test
    fun defaultDeathKnightLichKingIsEligibleByStableCardId() {
        val result = SurrenderPolicy.evaluateOpponentHero("localized-name-not-needed", "HERO_11")

        assertTrue(result.matched)
        assertFalse(result.shouldSurrender)
    }

    @Test
    fun nonOriginalHeroIsRejected() {
        val result = SurrenderPolicy.evaluateOpponentHeroName("死亡猎手雷克萨")

        assertFalse(result.matched)
        assertTrue(result.shouldSurrender)
    }

    @Test
    fun unresolvedHeroWaitsWithoutSurrender() {
        val result = SurrenderPolicy.evaluateOpponentHeroName("UNKNOWN ENTITY [cardType=INVALID]")

        assertFalse(result.matched)
        assertFalse(result.shouldSurrender)
        assertEquals("opponent-hero-name-not-resolved", result.reason)
    }

    @Test
    fun resolvedNonOriginalHeroRequestsSurrenderBeforeMulligan() {
        val war = warWithRivalHero("星界雪怒")

        SurrenderPolicy.resetForNewGame()
        val result = SurrenderPolicy.evaluateOpponentHeroBeforeMulligan(war)

        assertTrue(result != null)
        assertTrue(result!!.shouldSurrender)
        assertFalse(result.matched)
    }

    @Test
    fun resolvedOriginalHeroContinuesBeforeMulligan() {
        val war = warWithRivalHero("雷克萨")

        SurrenderPolicy.resetForNewGame()
        val result = SurrenderPolicy.evaluateOpponentHeroBeforeMulligan(war)

        assertTrue(result == null)
    }

    @Test
    fun originalHeroAtFortyHealthDoesNotTriggerLegacyHealthSurrender() {
        val war = warWithRivalHero("加尔鲁什").apply {
            rival.playArea.hero!!.health = 40
            rival.playArea.hero!!.damage = 0
        }

        assertNull(SurrenderPolicy.evaluateTurnStart(war))
    }

    @Test
    fun rankOcrParserAcceptsPlainAndLocalizedRankText() {
        assertEquals(10, CurrentRankDetector.parseRankText("白银10"))
        assertEquals(9, CurrentRankDetector.parseRankText("当前等级：9"))
        assertEquals(8, CurrentRankDetector.parseRankText("\uFF18"))
    }

    @Test
    fun rankOcrParserRejectsUnrelatedOrInvalidNumbers() {
        assertNull(CurrentRankDetector.parseRankText("Kenneth Sun"))
        assertNull(CurrentRankDetector.parseRankText("等级：11"))
        assertNull(CurrentRankDetector.parseRankText("等级：0"))
        assertNull(CurrentRankDetector.parseRankText("01404"))
    }

    @Test
    fun rankTierParserRecognizesLocalizedLeagueNames() {
        assertEquals(CurrentRankDetector.RankTier.SILVER, CurrentRankDetector.parseTierText("白银10"))
        assertEquals(CurrentRankDetector.RankTier.GOLD, CurrentRankDetector.parseTierText("黄金10"))
        assertEquals(CurrentRankDetector.RankTier.BRONZE, CurrentRankDetector.parseTierText("青铜9"))
        assertEquals(CurrentRankDetector.RankTier.UNKNOWN, CurrentRankDetector.parseTierText("Kenneth Sun"))
    }

    @Test
    fun rankResolverPrefersExplicitTenOverPartialOneReads() {
        assertEquals(
            10,
            CurrentRankDetector.resolveRankCandidates(listOf("1", "", "10", "1")),
        )
    }

    @Test
    fun rankResolverUsesVisualTenHintWhenOcrReadsOnlyOneDigit() {
        assertEquals(
            10,
            CurrentRankDetector.resolveRankCandidates(listOf("", "2", "", ""), visualTenHint = true),
        )
        assertEquals(
            10,
            CurrentRankDetector.resolveRankCandidates(listOf("9", "1", "", "1"), visualTenHint = true),
        )
    }

    @Test
    fun rankVisualHintDistinguishesTwoDigitBadgeFromSingleDigitBadge() {
        val ten = BufferedImage(144, 140, BufferedImage.TYPE_INT_RGB)
        val tenGraphics = ten.createGraphics()
        tenGraphics.color = Color.WHITE
        tenGraphics.fillRect(40, 45, 10, 35)
        tenGraphics.fillRect(56, 45, 18, 35)
        tenGraphics.dispose()
        assertTrue(CurrentRankDetector.looksLikeTwoDigitRank(ten))

        val one = BufferedImage(144, 140, BufferedImage.TYPE_INT_RGB)
        val oneGraphics = one.createGraphics()
        oneGraphics.color = Color.WHITE
        oneGraphics.fillRect(53, 45, 12, 35)
        oneGraphics.dispose()
        assertFalse(CurrentRankDetector.looksLikeTwoDigitRank(one))
    }

    @Test
    fun rankTierVisualClassifierDistinguishesWarmGoldFromNeutralSilver() {
        val gold = BufferedImage(144, 140, BufferedImage.TYPE_INT_RGB)
        val goldGraphics = gold.createGraphics()
        goldGraphics.color = Color(220, 165, 65)
        goldGraphics.drawRect(6, 6, 90, 100)
        goldGraphics.drawRect(10, 10, 82, 92)
        goldGraphics.dispose()
        assertEquals(CurrentRankDetector.RankTier.GOLD, CurrentRankDetector.detectTierVisual(gold))

        val silver = BufferedImage(144, 140, BufferedImage.TYPE_INT_RGB)
        val silverGraphics = silver.createGraphics()
        silverGraphics.color = Color(185, 185, 185)
        silverGraphics.drawRect(6, 6, 90, 100)
        silverGraphics.drawRect(10, 10, 82, 92)
        silverGraphics.dispose()
        assertEquals(CurrentRankDetector.RankTier.SILVER, CurrentRankDetector.detectTierVisual(silver))
    }

    @Test
    fun rankResolverRequiresAgreementBeforeSurrenderRank() {
        assertNull(CurrentRankDetector.resolveRankCandidates(listOf("1", "", "")))
        assertEquals(1, CurrentRankDetector.resolveRankCandidates(listOf("1", "1", "")))
        assertEquals(9, CurrentRankDetector.resolveRankCandidates(listOf("9", "9", "19")))
    }

    @Test
    fun rankResolverRejectsConflictingDigitsFromTransitionFrame() {
        assertNull(
            CurrentRankDetector.resolveRankCandidates(
                listOf("", "2", "4", "4", "", "3"),
            ),
        )
    }

    @Test
    fun rankTenDoesNotRequestSurrender() {
        assertNull(SurrenderPolicy.evaluateCurrentRank(10))
    }

    @Test
    fun silverTenIsTheSafeFloorButGoldTenRequestsSurrender() {
        assertNull(
            SurrenderPolicy.evaluateCurrentRank(
                rank = 10,
                tier = CurrentRankDetector.RankTier.SILVER,
            ),
        )
        val result = SurrenderPolicy.evaluateCurrentRank(
            rank = 10,
            tier = CurrentRankDetector.RankTier.GOLD,
        )
        assertTrue(result != null)
        assertTrue(result!!.shouldSurrender)
        assertEquals("current-tier-above-silver-10", result.ruleId)
    }

    @Test
    fun silverNineRequestsSurrender() {
        val result = SurrenderPolicy.evaluateCurrentRank(
            rank = 9,
            tier = CurrentRankDetector.RankTier.SILVER,
        )
        assertTrue(result != null)
        assertTrue(result!!.shouldSurrender)
    }

    @Test
    fun winRateGuardNeedsFivePlayedGamesAndSurrendersAtOrAboveFortyFivePercent() {
        assertNull(SurrenderPolicy.evaluateWinRate(SurrenderPolicy.WinRateSnapshot(games = 4, wins = 0)))
        assertNull(SurrenderPolicy.evaluateWinRate(SurrenderPolicy.WinRateSnapshot(games = 5, wins = 2)))
        val boundary = SurrenderPolicy.evaluateWinRate(SurrenderPolicy.WinRateSnapshot(games = 20, wins = 9))
        assertTrue(boundary != null)
        assertTrue(boundary!!.shouldSurrender)
        assertEquals("win-rate-at-least-45-percent", boundary.ruleId)
        val result = SurrenderPolicy.evaluateWinRate(SurrenderPolicy.WinRateSnapshot(games = 5, wins = 3))
        assertTrue(result != null)
        assertTrue(result!!.shouldSurrender)
        assertEquals("win-rate-at-least-45-percent", result.ruleId)
        assertTrue(result!!.reason.orEmpty().contains("reached-threshold=45.0%"))
    }

    @Test
    fun rankInspectionRequiresAnActiveWarNotJustThePreMulliganPhase() {
        assertFalse(
            SurrenderPolicy.isRankInspectionEligible(
                inWar = false,
                phase = WarPhaseEnum.FILL_DECK,
            ),
        )
        assertFalse(
            SurrenderPolicy.isRankInspectionEligible(
                inWar = true,
                phase = WarPhaseEnum.FILL_DECK,
            ),
        )
        assertTrue(
            SurrenderPolicy.isRankInspectionEligible(
                inWar = true,
                phase = WarPhaseEnum.DRAWN_INIT_CARD,
            ),
        )
        assertTrue(
            SurrenderPolicy.isRankInspectionEligible(
                inWar = true,
                phase = WarPhaseEnum.REPLACE_CARD,
            ),
        )
        assertFalse(
            SurrenderPolicy.isRankInspectionEligible(
                inWar = true,
                phase = WarPhaseEnum.GAME_OVER,
            ),
        )
    }

    @Test
    fun rankBelowTenRequestsSurrender() {
        val result = SurrenderPolicy.evaluateCurrentRank(9)

        assertTrue(result != null)
        assertTrue(result!!.shouldSurrender)
        assertEquals("current-rank=9", result.reason)
    }

    @Test
    fun debugScreenshotRingRetainsNewestSixtyPngs() {
        val directory = Files.createTempDirectory("hs-debug-ring-test").toFile()
        try {
            repeat(65) { index ->
                val file = directory.resolve("debug-$index.png")
                file.writeBytes(byteArrayOf(1))
                file.setLastModified(index.toLong())
            }

            val retained = DebugScreenshotRing.prune(directory, 60)

            assertEquals(60, retained.size)
            assertEquals(60, directory.listFiles()!!.size)
            assertTrue(directory.resolve("debug-64.png").exists())
            assertFalse(directory.resolve("debug-4.png").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun warWithRivalHero(heroName: String): War {
        val war = War()
        war.me = Player(playerId = "1", war = war)
        war.rival = Player(playerId = "2", war = war)
        war.currentPhase = WarPhaseEnum.REPLACE_CARD
        war.rival.playArea.hero = Card(TestCardAction()).apply {
            entityName = heroName
            cardType = CardTypeEnum.HERO
            health = 30
        }
        return war
    }
}
