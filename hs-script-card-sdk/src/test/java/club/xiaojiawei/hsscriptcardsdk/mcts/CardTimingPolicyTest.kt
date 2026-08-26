package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardTimingPolicyTest {

    @Test
    fun `recognizes patches and both dynamic cost card families`() {
        assertTrue(card("CFM_637", "海盗帕奇斯").let(CardTimingPolicy::isPatchesThePirate))
        assertTrue(card("YOD_032", "狂暴邪翼蝠").let(CardTimingPolicy::isOpponentDamageReductionCard))
        assertTrue(card("TOY_330t7", "奇利亚斯豪华版3000型").let(CardTimingPolicy::isZilliaxDeluxe3000))
        assertFalse(card("VAC_927", "狂飙邪魔").let(CardTimingPolicy::isEndOfTurnCostReductionCard))
    }

    @Test
    fun `defers dynamic cost card while a minion can attack`() {
        val war = War(false)
        val me = Player(playerId = "me", war = war)
        war.me = me
        val timingCard = card("YOD_032", "狂暴邪翼蝠").apply {
            cardType = CardTypeEnum.MINION
            cost = 4
        }
        val attacker = card("ATTACKER", "攻击者").apply {
            cardType = CardTypeEnum.MINION
            atc = 1
            health = 1
            cost = 1
        }
        war.addCard(timingCard, me.handArea)
        war.addCard(attacker, me.playArea)

        assertTrue(CardTimingPolicy.shouldDefer(timingCard, war))
        attacker.isExhausted = true
        assertFalse(CardTimingPolicy.shouldDefer(timingCard, war))
    }

    @Test
    fun `does not defer timing card for an unparsed competing hand card`() {
        val war = War(false)
        val me = Player(playerId = "me", war = war)
        war.me = me
        val timingCard = card("YOD_032", "狂暴邪翼蝠").apply {
            cardType = CardTypeEnum.MINION
            cost = 1
        }
        val opaqueHandCard = card("AV_661", "征战平原").apply {
            cardType = CardTypeEnum.LOCATION
            cost = 2
        }
        me.resources = 3
        war.addCard(timingCard, me.handArea)
        war.addCard(opaqueHandCard, me.handArea)

        assertFalse(CardTimingPolicy.shouldDefer(timingCard, war))
    }

    @Test
    fun `simulates opponent damage and new friendly minion reductions`() {
        val before = War(false)
        val beforeMe = Player(playerId = "me", war = before)
        val beforeRival = Player(playerId = "rival", war = before)
        before.me = beforeMe
        before.rival = beforeRival
        val beforeRivalHero = card("RIVAL_HERO", "对手英雄").apply {
            cardType = CardTypeEnum.HERO
            health = 30
        }
        val beforeRagewing = card("YOD_032", "狂暴邪翼蝠").apply { cost = 4 }
        val beforeZilliax = card("TOY_330t7", "奇利亚斯豪华版3000型").apply { cost = 7 }
        before.addCard(beforeRivalHero, beforeRival.playArea)
        before.addCard(beforeRagewing, beforeMe.handArea)
        before.addCard(beforeZilliax, beforeMe.handArea)

        val after = War(false)
        val afterMe = Player(playerId = "me", war = after)
        val afterRival = Player(playerId = "rival", war = after)
        after.me = afterMe
        after.rival = afterRival
        val afterRivalHero = card("RIVAL_HERO", "对手英雄").apply {
            cardType = CardTypeEnum.HERO
            health = 30
            damage = 2
        }
        val afterRagewing = card("YOD_032", "狂暴邪翼蝠").apply { cost = 4 }
        val afterZilliax = card("TOY_330t7", "奇利亚斯豪华版3000型").apply { cost = 7 }
        after.addCard(afterRivalHero, afterRival.playArea)
        after.addCard(afterRagewing, afterMe.handArea)
        after.addCard(afterZilliax, afterMe.handArea)
        repeat(2) {
            after.addCard(card("MINION_$it", "随从").apply { cardType = CardTypeEnum.MINION }, afterMe.playArea)
        }

        CardTimingPolicy.applySimulatedReductions(before, after)

        assertEquals(2, afterRagewing.cost)
        assertEquals(5, afterZilliax.cost)
    }

    private fun card(cardId: String, name: String): Card = Card(TestCardAction()).apply {
        this.cardId = cardId
        entityId = cardId
        entityName = name
    }
}
