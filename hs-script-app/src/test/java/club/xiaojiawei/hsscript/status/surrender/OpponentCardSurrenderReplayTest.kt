package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.bean.log.CommonEntity
import club.xiaojiawei.hsscript.utils.PowerLogUtil
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.ZoneEnum
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Regression coverage from the Beta Power.log window on 2026-09-07 19:10–19:12. */
class OpponentCardSurrenderReplayTest {

    @Test
    fun `historical beta replay matches demon seed only after opponent play zone`() {
        val war = activeWar()
        val fixtureLines = requireNotNull(javaClass.getResourceAsStream(
            "/replay/beta-2026-09-07-19-10-12-Power.log.txt",
        )) {
            "historical Beta Power.log fixture is missing"
        }.bufferedReader(Charsets.UTF_8).readLines()

        val handEntity = parse(fixtureLines[0])
        val card = Card(TestCardAction()).apply {
            entityId = handEntity.entityId
            cardId = handEntity.cardId
            entityName = handEntity.entityName
        }
        war.rival.handArea.add(card)

        assertNull(SurrenderPolicy.evaluateOpponentPlayedCard(war), "hand reveal must not surrender")

        val secretEntity = parse(fixtureLines[1])
        war.rival.handArea.removeByEntityId(card.entityId)
        card.cardId = secretEntity.cardId
        card.entityName = secretEntity.entityName
        war.rival.secretArea.add(card)

        val result = SurrenderPolicy.evaluateOpponentPlayedCard(war)
        assertTrue(result?.shouldSurrender == true)
        assertEquals("opponent-played-card-demon-seed", result?.ruleId)
        assertTrue(result?.reason.orEmpty().contains("opponent-card=恶魔之种"))
        assertTrue(result?.reason.orEmpty().contains("cardId=SW_091"))
        assertTrue(result?.reason.orEmpty().contains("zone=SECRET"))

        war.rival.secretArea.removeByEntityId(card.entityId)
        val setasideEntity = parse(fixtureLines[3])
        card.cardId = setasideEntity.cardId
        card.entityName = setasideEntity.entityName
        assertTrue(fixtureLines[3].contains("value=SETASIDE"))
        war.rival.setasideArea.add(card)
        assertTrue(
            SurrenderPolicy.evaluateOpponentPlayedCard(war)?.reason.orEmpty().contains("zone=SETASIDE"),
        )
    }

    @Test
    fun `negative coverage excludes own card unrelated card and graveyard history`() {
        val war = activeWar()
        val ownCard = Card(TestCardAction()).apply {
            entityId = "own-19"
            cardId = "SW_091"
            entityName = "恶魔之种"
        }
        war.me.secretArea.add(ownCard)
        assertNull(SurrenderPolicy.evaluateOpponentPlayedCard(war), "our own quest is not an opponent card")

        val unrelatedCard = Card(TestCardAction()).apply {
            entityId = "rival-20"
            cardId = "SW_090"
            entityName = "其他卡牌"
        }
        war.rival.secretArea.add(unrelatedCard)
        assertNull(SurrenderPolicy.evaluateOpponentPlayedCard(war), "unrelated card must not match")

        war.rival.secretArea.removeByEntityId(unrelatedCard.entityId)
        val graveyardCard = Card(TestCardAction()).apply {
            entityId = "rival-21"
            cardId = "SW_091"
            entityName = "恶魔之种"
        }
        war.rival.graveyardArea.add(graveyardCard)
        assertNull(SurrenderPolicy.evaluateOpponentPlayedCard(war), "graveyard history is not an active played-zone signal")
    }

    @Test
    fun `localized name is a data-driven fallback when Power log omits card ID`() {
        val war = activeWar()
        war.rival.secretArea.add(Card(TestCardAction()).apply {
            entityId = "rival-name-only"
            entityName = "恶魔之种"
        })

        val result = SurrenderPolicy.evaluateOpponentPlayedCard(war)
        assertEquals("opponent-played-card-demon-seed", result?.ruleId)
        assertTrue(result?.reason.orEmpty().contains("match=localized-name"))
    }

    @Test
    fun `registry exposes stable ID localized name and future played zones`() {
        val definition = SurrenderPolicy.directSurrenderCardRegistry.single { it.key == "demon-seed" }

        assertTrue(definition.cardIds.contains("SW_091"))
        assertTrue(definition.localizedNames.contains("恶魔之种"))
        assertEquals(setOf(ZoneEnum.PLAY, ZoneEnum.SECRET, ZoneEnum.SETASIDE), definition.playedZones)
    }

    @Test
    fun `start of game darkbishop reveal in opponent deck requests surrender`() {
        val war = activeWar()
        war.rival.deckArea.add(Card(TestCardAction()).apply {
            entityId = "rival-darkbishop"
            cardId = "SW_448"
            entityName = "黑暗主教本尼迪塔斯"
        })

        val result = SurrenderPolicy.evaluateOpponentPlayedCard(war)

        assertTrue(result?.shouldSurrender == true)
        assertEquals("opponent-played-card-darkbishop-benedictus", result?.ruleId)
        assertTrue(result?.reason.orEmpty().contains("cardId=SW_448"))
        assertTrue(result?.reason.orEmpty().contains("zone=DECK"))
    }

    @Test
    fun `darkbishop registry allows only explicit deck reveal signal`() {
        val definition = SurrenderPolicy.directSurrenderCardRegistry.single {
            it.key == "darkbishop-benedictus"
        }

        assertTrue(definition.cardIds.contains("SW_448"))
        assertTrue(definition.cardIds.contains("CORE_SW_448"))
        assertTrue(definition.localizedNames.contains("黑暗主教本尼迪塔斯"))
        assertEquals(setOf(ZoneEnum.DECK), definition.playedZones)
    }

    private fun parse(line: String): CommonEntity = CommonEntity().also {
        PowerLogUtil.parseCommonEntity(it, line)
    }

    private fun activeWar(): War {
        val war = War()
        war.me = Player(playerId = "2", gameId = "laz#12793", war = war)
        war.rival = Player(playerId = "1", gameId = "drdanks#11763", war = war)
        war.currentPhase = WarPhaseEnum.GAME_TURN
        return war
    }
}
