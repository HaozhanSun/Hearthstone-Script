package club.xiaojiawei.hsscriptstrategysdk.deck

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsDecisionModel
import kotlin.test.Test
import kotlin.test.assertEquals

class ImmediateLocationFenceStateTest {

    @Test
    fun `confirmed play arms and matching confirmed power clears the fence`() {
        val location = Card(TestCardAction()).apply {
            entityId = "location-1"
            cardId = "VAC_929"
            cardType = CardTypeEnum.LOCATION
        }
        val war = War(false)
        val model = object : MctsDecisionModel {
            override fun shouldImmediatelyPowerLocation(card: Card, war: War): Boolean =
                card.cardId == "VAC_929"
        }
        val fence = ImmediateLocationFenceState()

        assertEquals("location-1", fence.observeConfirmedPlay(PlayAction({}, {}, location), model, war))
        assertEquals("location-1", fence.pendingCreatorId)
        assertEquals("location-1", fence.observeConfirmedPower(PowerAction({}, {}, location)))
        assertEquals(null, fence.pendingCreatorId)
    }
}
