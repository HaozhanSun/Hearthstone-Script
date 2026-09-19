package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.InitAction
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MonteCarloTreeNode
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsDecisionModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PirateNuLingNagaAttackOrderTest {

    @Test
    fun `both pirate models defer Naga behind another legal friendly minion attack`() {
        assertModelDefersNaga(PirateDemonHunterMctsExperimentModel)
        assertModelDefersNaga(PirateWarriorMctsModel)
    }

    @Test
    fun `both pirate models keep Naga available when it is the only attacker`() {
        assertModelAllowsOnlyNaga(PirateDemonHunterMctsExperimentModel)
        assertModelAllowsOnlyNaga(PirateWarriorMctsModel)
    }

    @Test
    fun `both pirate models defer Adrenaline Fiend behind another friendly minion`() {
        assertModelDefersAdrenalineFiend(PirateDemonHunterMctsExperimentModel)
        assertModelDefersAdrenalineFiend(PirateWarriorMctsModel)
    }

    @Test
    fun `both pirate models allow Adrenaline Fiend when it is the only remaining minion attacker`() {
        assertModelAllowsOnlyAdrenalineFiend(PirateDemonHunterMctsExperimentModel)
        assertModelAllowsOnlyAdrenalineFiend(PirateWarriorMctsModel)
    }

    private fun assertModelDefersNaga(model: MctsDecisionModel) {
        val war = testWar()
        val naga = testMinion(PirateAttackOrderPolicy.NU_LING_NAGA, "naga")
        val other = testMinion("OTHER_ATTACKER", "other")
        war.addCard(naga, war.me.playArea)
        war.addCard(other, war.me.playArea)

        val nagaAttack = naga.action.generateAttackActions(war, war.me).single()
        assertTrue(model.isDeferredAction(nagaAttack, war))
        assertFalse(model.isDeferredAction(other.action.generateAttackActions(war, war.me).single(), war))

        val node = MonteCarloTreeNode(war, InitAction, mctsArg(model))
        assertTrue(node.actions.none { it.creator?.entityId == naga.entityId })
        assertTrue(node.actions.any { it.creator?.entityId == other.entityId })
    }

    private fun assertModelAllowsOnlyNaga(model: MctsDecisionModel) {
        val war = testWar()
        val naga = testMinion(PirateAttackOrderPolicy.NU_LING_NAGA, "only-naga")
        war.addCard(naga, war.me.playArea)

        val node = MonteCarloTreeNode(war, InitAction, mctsArg(model))
        assertTrue(node.actions.any { it.creator?.entityId == naga.entityId })
    }

    private fun assertModelDefersAdrenalineFiend(model: MctsDecisionModel) {
        val war = testWar()
        val fiend = testMinion(PirateAttackOrderPolicy.ADRENALINE_FIEND, "fiend")
        val other = testMinion("OTHER_PIRATE", "other-pirate")
        war.addCard(fiend, war.me.playArea)
        war.addCard(other, war.me.playArea)

        val fiendAttack = fiend.action.generateAttackActions(war, war.me).single()
        val otherAttack = other.action.generateAttackActions(war, war.me).single()
        assertTrue(model.isDeferredAction(fiendAttack, war))
        assertFalse(model.isDeferredAction(otherAttack, war))
        assertFalse(model.isMandatoryAction(fiendAttack, war))
    }

    private fun assertModelAllowsOnlyAdrenalineFiend(model: MctsDecisionModel) {
        val war = testWar()
        val fiend = testMinion(PirateAttackOrderPolicy.ADRENALINE_FIEND, "only-fiend")
        val other = testMinion("OTHER_PIRATE", "spent-pirate").apply { isExhausted = true }
        war.addCard(fiend, war.me.playArea)
        war.addCard(other, war.me.playArea)

        val fiendAttack = fiend.action.generateAttackActions(war, war.me).single()
        assertFalse(model.isDeferredAction(fiendAttack, war))
    }

    private fun mctsArg(model: MctsDecisionModel): MCTSArg = MCTSArg(
        endMillisTime = Long.MAX_VALUE,
        turnCount = 1,
        turnFactor = 0.5,
        countPerTurn = 1,
        scoreCalculator = { 0.0 },
        enableMultiThread = false,
        decisionModel = model,
        experimentalSearch = true,
    )

    private fun testWar(): War {
        val war = War()
        val me = Player(playerId = "me", war = war)
        val rival = Player(playerId = "rival", war = war)
        war.me = me
        war.rival = rival
        war.player1 = me
        war.player2 = rival
        war.currentPlayer = me
        war.isMyTurn = true
        me.resources = 0
        val rivalHero = testMinion("RIVAL_HERO", "rival-hero").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 30
        }
        war.addCard(rivalHero, war.rival.playArea)
        return war
    }

    private fun testMinion(cardId: String, entityId: String): Card = Card(TestCardAction()).apply {
        this.cardId = cardId
        this.entityId = entityId
        entityName = cardId
        cardType = CardTypeEnum.MINION
        cardRace = CardRaceEnum.PIRATE
        cost = 0
        atc = 2
        health = 3
        isExhausted = false
        action.belongCard = this
    }
}
