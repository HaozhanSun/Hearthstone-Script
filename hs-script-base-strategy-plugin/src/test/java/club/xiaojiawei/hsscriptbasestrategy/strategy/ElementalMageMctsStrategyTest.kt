package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElementalMageMctsStrategyTest {
    @Test
    fun `elemental mage is visible only as a wild strategy`() {
        val strategy = HsElementalMageMctsDeckStrategy()

        assertEquals("元素法 V1.3", strategy.name().substringBefore(" ·"))
        assertEquals(listOf(RunModeEnum.WILD), strategy.runModes.toList())
        assertFalse(strategy.runModes.contains(RunModeEnum.STANDARD))
        assertTrue(strategy.id().contains("elemental-mage-mcts-v1-2"))
        val entries = javaClass.classLoader
            .getResourceAsStream("META-INF/services/club.xiaojiawei.hsscriptstrategysdk.DeckStrategy")
            ?.bufferedReader()?.readLines().orEmpty()
        assertTrue(entries.contains("club.xiaojiawei.hsscriptbasestrategy.strategy.HsElementalMageMctsDeckStrategy"))
    }

    @Test
    fun `from turn three a playable elemental is mandatory`() {
        val war = testWar(turn = 3, mana = 3)
        val elemental = testCard("ELEMENTAL_TEST", "火光元素", 2, CardRaceEnum.ELEMENTAL)
        val filler = testCard("FILLER_TEST", "普通随从", 1, CardRaceEnum.UNKNOWN)
        war.addCard(elemental, war.me.handArea)
        war.addCard(filler, war.me.handArea)

        val elementalAction = PlayAction({}, {}, elemental)
        val fillerAction = PlayAction({}, {}, filler)
        assertTrue(ElementalMageMctsModel.mustPlayElementalFirst(war))
        assertTrue(ElementalMageMctsModel.isMandatoryAction(elementalAction, war))
        assertFalse(ElementalMageMctsModel.isMandatoryAction(fillerAction, war))
    }

    @Test
    fun `sunfire is legal only at two or less mana and is late priority`() {
        val war = testWar(turn = 5, mana = 5)
        val sunfire = testCard("SUNFIRE_TEST", "阳炎耀斑", 5, CardRaceEnum.UNKNOWN, CardTypeEnum.SPELL)
        val elemental = testCard("ELEMENTAL_TEST", "火光元素", 2, CardRaceEnum.ELEMENTAL)
        war.addCard(sunfire, war.me.handArea)
        war.addCard(elemental, war.me.handArea)

        assertFalse(ElementalMageMctsModel.isActionLegal(PlayAction({}, {}, sunfire), war))
        sunfire.cost = 2
        assertTrue(ElementalMageMctsModel.isActionLegal(PlayAction({}, {}, sunfire), war))
        assertTrue(ElementalMageMctsModel.actionPrior(PlayAction({}, {}, sunfire), war) <
            ElementalMageMctsModel.actionPrior(PlayAction({}, {}, elemental), war))
    }

    @Test
    fun `chain counter resets on a missed elemental turn`() {
        val first = ElementalMageMctsModel.updateChain(
            ElementalMageMctsModel.ChainSnapshot(), 3, playedElemental = true,
        )
        val second = ElementalMageMctsModel.updateChain(first, 4, playedElemental = true)
        val reset = ElementalMageMctsModel.updateChain(second, 5, playedElemental = false)

        assertEquals(1, first.consecutiveTurns)
        assertEquals(2, second.consecutiveTurns)
        assertEquals(0, reset.consecutiveTurns)
        assertFalse(reset.lastTurnPlayedElemental)
    }

    @Test
    fun `elemental strategy exposes a current face lethal before trade actions`() {
        val war = testWar(turn = 6, mana = 0)
        val hero = testCard("MAGE_HERO", "英雄", 0, CardRaceEnum.UNKNOWN, CardTypeEnum.HERO).apply {
            atc = 3
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("RIVAL_HERO", "敌方英雄", 0, CardRaceEnum.UNKNOWN, CardTypeEnum.HERO).apply {
            health = 3
        }
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)

        val face = AttackAction({}, {}, hero, targetEntityId = rivalHero.entityId, targetIsHero = true)
        assertTrue(ElementalMageMctsModel.isLethalAction(face, war))
    }

    @Test
    fun `overflowing lava rejects plans that lose more than one expected copy`() {
        assertTrue(ElementalMageMctsModel.overflowingLavaCopyPlan(3, 3).allowed)
        assertEquals(1, ElementalMageMctsModel.overflowingLavaCopyPlan(3, 3).lostCopies)
        assertFalse(ElementalMageMctsModel.overflowingLavaCopyPlan(4, 3).allowed)
        assertEquals(2, ElementalMageMctsModel.overflowingLavaCopyPlan(4, 3).lostCopies)
    }

    private fun testWar(turn: Int, mana: Int): War {
        val war = War()
        val me = Player(playerId = "me", war = war)
        val rival = Player(playerId = "rival", war = war)
        war.me = me
        war.rival = rival
        war.player1 = me
        war.player2 = rival
        war.currentPlayer = me
        war.isMyTurn = true
        me.turn = turn
        me.resources = mana
        return war
    }

    private fun testCard(
        cardId: String,
        name: String,
        cost: Int,
        race: CardRaceEnum,
        type: CardTypeEnum = CardTypeEnum.MINION,
    ): Card = Card(TestCardAction()).apply {
        entityId = "$cardId-entity"
        this.cardId = cardId
        entityName = name
        this.cost = cost
        cardRace = race
        cardType = type
        health = 3
        atc = 2
        action.belongCard = this
    }
}
