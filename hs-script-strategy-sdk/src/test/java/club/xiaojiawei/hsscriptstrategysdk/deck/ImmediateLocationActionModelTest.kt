package club.xiaojiawei.hsscriptstrategysdk.deck

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsDecisionModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImmediateLocationActionModelTest {

    @Test
    fun `confirmed location play fences the next live action to its power click`() {
        val target = card("location-1", CardTypeEnum.LOCATION)
        val other = card("other-1", CardTypeEnum.MINION)
        val war = War(false)
        val delegate = object : MctsDecisionModel {
            override fun isActionLegal(action: club.xiaojiawei.hsscriptcardsdk.bean.Action, war: War): Boolean = true
        }
        val model = MCTSDeckStrategy.ImmediateLocationActionModel(delegate, target.entityId)
        val power = PowerAction({}, {}, target)
        val play = PlayAction({}, {}, other)

        assertTrue(model.isMandatoryAction(power, war))
        assertTrue(model.isActionLegal(power, war))
        assertFalse(model.isDeferredAction(power, war))
        assertTrue(model.actionPrior(power, war) > model.actionPrior(play, war))
        assertFalse(model.isMandatoryAction(play, war))
        assertFalse(model.isActionLegal(play, war))
        assertTrue(model.isDeferredAction(play, war))
    }

    private fun card(entityId: String, type: CardTypeEnum): Card = Card(TestCardAction()).apply {
        this.entityId = entityId
        cardId = entityId
        cardType = type
    }
}
