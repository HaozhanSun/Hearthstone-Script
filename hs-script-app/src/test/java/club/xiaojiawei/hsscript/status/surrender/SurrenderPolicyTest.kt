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
    fun rankResolverPrefersExplicitTenOverPartialOneReads() {
        assertEquals(
            10,
            CurrentRankDetector.resolveRankCandidates(listOf("1", "", "10", "1")),
        )
    }

    @Test
    fun rankResolverRequiresAgreementBeforeSurrenderRank() {
        assertNull(CurrentRankDetector.resolveRankCandidates(listOf("1", "", "")))
        assertEquals(1, CurrentRankDetector.resolveRankCandidates(listOf("1", "1", "")))
        assertEquals(9, CurrentRankDetector.resolveRankCandidates(listOf("9", "9", "19")))
    }

    @Test
    fun rankTenDoesNotRequestSurrender() {
        assertNull(SurrenderPolicy.evaluateCurrentRank(10))
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
