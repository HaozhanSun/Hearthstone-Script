package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.InitAction
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MonteCarloTreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PirateWarriorMctsModelTest {
    @Test
    fun `cannon is mandatory before treasure distributor`() {
        val war = testWar(turn = 1, mana = 2)
        val cannon = testCard(PirateWarriorMctsModel.SHIPS_CANNON, cost = 2)
        val distributor = testCard(PirateWarriorMctsModel.TREASURE_DISTRIBUTOR, cost = 1)
        war.addCard(cannon, war.me.handArea)
        war.addCard(distributor, war.me.handArea)

        val cannonAction = PlayAction({}, {}, cannon)
        val distributorAction = PlayAction({}, {}, distributor)
        assertTrue(PirateWarriorMctsModel.isMandatoryAction(cannonAction, war))
        assertTrue(!PirateWarriorMctsModel.isMandatoryAction(distributorAction, war))
    }

    @Test
    fun `first turn quest is mandatory when cannon is not playable`() {
        val war = testWar(turn = 1, mana = 1)
        val quest = testCard(PirateWarriorMctsModel.QUESTLINE, cost = 1)
        val distributor = testCard(PirateWarriorMctsModel.TREASURE_DISTRIBUTOR, cost = 1)
        war.addCard(quest, war.me.handArea)
        war.addCard(distributor, war.me.handArea)

        assertTrue(
            PirateWarriorMctsModel.isMandatoryAction(PlayAction({}, {}, quest), war),
        )
        assertTrue(
            !PirateWarriorMctsModel.isMandatoryAction(PlayAction({}, {}, distributor), war),
        )
    }

    @Test
    fun `pirates receive southsea reward without an attack-event hozen bonus`() {
        val war = testWar(turn = 2, mana = 4)
        val captain = testCard(PirateWarriorMctsModel.SOUTHSEA_CAPTAIN, cost = 3, attack = 3)
        val hozen = testCard(PirateWarriorMctsModel.HOZEN_ROUGHHOUSER, cost = 3, attack = 2)
        val attacker = testCard("PIRATE_ATTACKER", cost = 1, attack = 2)
        war.addCard(captain, war.me.playArea)
        war.addCard(hozen, war.me.playArea)
        war.addCard(attacker, war.me.playArea)

        assertEquals(3, PirateWarriorMctsModel.effectivePirateAttack(attacker, war))
    }

    @Test
    fun `patches has bottom prior`() {
        val war = testWar(turn = 2, mana = 1)
        val patches = testCard(PirateWarriorMctsModel.PATCHES_THE_PIRATE, cost = 1)
        val ordinary = testCard("ORDINARY_PIRATE", cost = 1)
        war.addCard(patches, war.me.handArea)
        war.addCard(ordinary, war.me.handArea)

        assertTrue(
            PirateWarriorMctsModel.actionPrior(
                PlayAction({}, {}, patches),
                war,
            ) < PirateWarriorMctsModel.actionPrior(PlayAction({}, {}, ordinary), war),
        )
    }

    @Test
    fun `root action generation exposes only playable cannon at p0`() {
        val war = testWar(turn = 1, mana = 2)
        val cannon = testCard(PirateWarriorMctsModel.SHIPS_CANNON, cost = 2)
        val distributor = testCard(PirateWarriorMctsModel.TREASURE_DISTRIBUTOR, cost = 1)
        war.addCard(cannon, war.me.handArea)
        war.addCard(distributor, war.me.handArea)

        val node = MonteCarloTreeNode(
            war,
            InitAction,
            MCTSArg(
                endMillisTime = Long.MAX_VALUE,
                turnCount = 1,
                turnFactor = 0.5,
                countPerTurn = 1,
                scoreCalculator = { 0.0 },
                enableMultiThread = false,
                decisionModel = PirateWarriorMctsModel,
                experimentalSearch = true,
            ),
        )

        assertTrue(node.actions.isNotEmpty())
        assertTrue(node.actions.all { it.creator?.let { card -> PirateWarriorMctsModel.isCard(card, PirateWarriorMctsModel.SHIPS_CANNON) } == true })
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

    private fun testCard(cardId: String, cost: Int, attack: Int = 2): Card = Card(TestCardAction()).apply {
        entityId = "$cardId-test"
        this.cardId = cardId
        entityName = cardId
        cardType = CardTypeEnum.MINION
        cardRace = CardRaceEnum.PIRATE
        this.cost = cost
        atc = attack
        health = 3
        action.belongCard = this
    }
}
