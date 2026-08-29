package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.InitAction
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import kotlin.test.Test
import kotlin.test.assertTrue

class MonteCarloTreeNodeDeferredActionTest {

    @Test
    fun `deferred timing action remains available when every other action is deferred`() {
        val war = War()
        val me = Player(playerId = "me", war = war)
        val rival = Player(playerId = "rival", war = war)
        war.me = me
        war.rival = rival
        war.player1 = me
        war.player2 = rival
        war.currentPlayer = me
        war.isMyTurn = true
        me.resources = 1

        val timingCard = card("YOD_032").apply { entityName = "狂暴邪翼蝠" }
        val otherCard = card("OTHER_CARD")
        war.addCard(timingCard, me.handArea)
        war.addCard(otherCard, me.handArea)

        val model = object : MctsDecisionModel {
            override fun shouldDefer(card: Card, war: War): Boolean =
                card.cardId == "YOD_032"

            override fun isDeferredAction(action: club.xiaojiawei.hsscriptcardsdk.bean.Action, war: War): Boolean =
                action is PlayAction && action.creator?.cardId == "OTHER_CARD"
        }
        val arg = MCTSArg(
            endMillisTime = Long.MAX_VALUE,
            turnCount = 1,
            turnFactor = 0.5,
            countPerTurn = 1,
            scoreCalculator = { 0.0 },
            enableMultiThread = false,
            decisionModel = model,
            experimentalSearch = true,
        )

        val node = MonteCarloTreeNode(war, InitAction, arg)

        assertTrue(node.actions.any { it.creator?.cardId == "YOD_032" })
        assertTrue(node.actions.any { it.creator?.cardId == "OTHER_CARD" })
        assertTrue(node.actions.none { it.javaClass.simpleName == "TurnOverAction" })
    }

    private fun card(cardId: String): Card = Card(TestCardAction()).apply {
        entityId = "$cardId-entity"
        this.cardId = cardId
        cardType = CardTypeEnum.MINION
        cardRace = CardRaceEnum.PIRATE
        cost = 1
        atc = 1
        health = 1
        action.belongCard = this
    }
}
