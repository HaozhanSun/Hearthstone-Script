package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HsPirateDemonHunterDeckStrategyTest {

    @Test
    fun `defers a one-mana-short priority card when coin is available`() {
        assertTrue(
            HsPirateDemonHunterDeckStrategy.shouldDeferPriorityCard(
                cardCost = 2,
                currentMana = 1,
                coinAvailable = true,
            ),
        )
    }

    @Test
    fun `does not defer when card is already playable or coin is absent`() {
        assertFalse(
            HsPirateDemonHunterDeckStrategy.shouldDeferPriorityCard(
                cardCost = 2,
                currentMana = 2,
                coinAvailable = true,
            ),
        )
        assertFalse(
            HsPirateDemonHunterDeckStrategy.shouldDeferPriorityCard(
                cardCost = 2,
                currentMana = 1,
                coinAvailable = false,
            ),
        )
    }

    @Test
    fun `always mulligans patches even though it costs one`() {
        val patches = Card(TestCardAction()).apply {
            cardId = "CFM_637"
            entityName = "海盗帕奇斯"
            cost = 1
        }

        assertTrue(HsPirateDemonHunterDeckStrategy.shouldMulligan(patches))
    }
}
