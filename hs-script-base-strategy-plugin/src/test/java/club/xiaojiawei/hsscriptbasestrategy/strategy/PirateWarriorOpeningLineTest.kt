package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import kotlin.test.Test
import kotlin.test.assertEquals

class PirateWarriorOpeningLineTest {
    @Test
    fun `going second with original cannon and one cost pirate uses coin then cannon`() {
        val war = testWar(turn = 1, mana = 1)
        val cannon = card(PirateWarriorMctsModel.SHIPS_CANNON, 2)
        val pirate = card("OPENING_PIRATE", 1)
        val coin = card("COIN", 0).apply { isCoinCard = true }
        war.addCard(cannon, war.me.handArea)
        war.addCard(pirate, war.me.handArea)
        war.addCard(coin, war.me.handArea)

        PirateWarriorMctsModel.registerOpeningHandSnapshot(war, listOf(cannon, pirate))
        assertEquals(
            PirateWarriorMctsModel.OpeningCannonCoinStep.PLAY_COIN,
            PirateWarriorMctsModel.openingCannonCoinStep(war),
        )

        war.me.handArea.removeByEntityId(coin.entityId)
        war.me.tempResources = 1
        assertEquals(
            PirateWarriorMctsModel.OpeningCannonCoinStep.PLAY_CANNON,
            PirateWarriorMctsModel.openingCannonCoinStep(war),
        )
        PirateWarriorMctsModel.clearOpeningHandSnapshot(war)
    }

    @Test
    fun `opening exception is disabled without coin and for a replacement entity`() {
        val noCoin = testWar(turn = 1, mana = 1)
        val cannon = card(PirateWarriorMctsModel.SHIPS_CANNON, 2)
        val pirate = card("NO_COIN_PIRATE", 1)
        noCoin.addCard(cannon, noCoin.me.handArea)
        noCoin.addCard(pirate, noCoin.me.handArea)
        PirateWarriorMctsModel.registerOpeningHandSnapshot(noCoin, listOf(cannon, pirate))
        assertEquals(
            PirateWarriorMctsModel.OpeningCannonCoinStep.NONE,
            PirateWarriorMctsModel.openingCannonCoinStep(noCoin),
        )
        PirateWarriorMctsModel.clearOpeningHandSnapshot(noCoin)

        val replacement = testWar(turn = 1, mana = 1)
        val originalCannon = card(PirateWarriorMctsModel.SHIPS_CANNON, 2)
        val replacementCannon = card(PirateWarriorMctsModel.SHIPS_CANNON, 2).apply {
            entityId = "replacement-cannon"
        }
        val originalPirate = card("ORIGINAL_PIRATE", 1)
        val replacementCoin = card("COIN", 0).apply { isCoinCard = true }
        replacement.addCard(replacementCannon, replacement.me.handArea)
        replacement.addCard(originalPirate, replacement.me.handArea)
        replacement.addCard(replacementCoin, replacement.me.handArea)
        PirateWarriorMctsModel.registerOpeningHandSnapshot(replacement, listOf(originalCannon, originalPirate))
        assertEquals(
            PirateWarriorMctsModel.OpeningCannonCoinStep.NONE,
            PirateWarriorMctsModel.openingCannonCoinStep(replacement),
        )
        PirateWarriorMctsModel.clearOpeningHandSnapshot(replacement)
    }

    @Test
    fun `turn two forces quest before the original one cost pirate`() {
        val war = testWar(turn = 2, mana = 2)
        val cannon = card(PirateWarriorMctsModel.SHIPS_CANNON, 2)
        val pirate = card("TURN_TWO_PIRATE", 1)
        val quest = card(PirateWarriorMctsModel.QUESTLINE, 1).apply {
            cardType = CardTypeEnum.SPELL
            cardRace = CardRaceEnum.UNKNOWN
        }
        val coin = card("COIN", 0).apply { isCoinCard = true }
        war.addCard(cannon, war.me.playArea)
        war.addCard(pirate, war.me.handArea)
        war.addCard(quest, war.me.handArea)
        war.addCard(coin, war.me.handArea)
        PirateWarriorMctsModel.registerOpeningHandSnapshot(war, listOf(cannon, pirate))
        war.me.handArea.removeByEntityId(coin.entityId)

        assertEquals(
            PirateWarriorMctsModel.OpeningCannonCoinStep.PLAY_QUEST,
            PirateWarriorMctsModel.openingCannonCoinStep(war),
        )
        war.me.handArea.removeByEntityId(quest.entityId)
        war.addCard(quest, war.me.graveyardArea)
        war.me.usedResources = 1
        assertEquals(
            PirateWarriorMctsModel.OpeningCannonCoinStep.PLAY_PIRATE,
            PirateWarriorMctsModel.openingCannonCoinStep(war),
        )
        PirateWarriorMctsModel.clearOpeningHandSnapshot(war)
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

    private fun card(id: String, cost: Int): Card = Card(TestCardAction()).apply {
        entityId = "$id-entity"
        cardId = id
        entityName = id
        cardType = CardTypeEnum.MINION
        cardRace = CardRaceEnum.PIRATE
        this.cost = cost
        atc = 2
        health = 3
        action.belongCard = this
    }
}
