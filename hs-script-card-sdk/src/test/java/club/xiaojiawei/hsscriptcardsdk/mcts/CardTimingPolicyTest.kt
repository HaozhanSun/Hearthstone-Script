package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.InitAction
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsDecisionModel
import club.xiaojiawei.hsscriptcardsdk.mcts.MonteCarloTreeNode
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

    @Test
    fun `aredar brute is legal at the exact last-card cost and health boundaries`() {
        val war = warWithHero(health = 30, damage = 15)
        val brute = aredarBrute(cost = 4)
        war.addCard(brute, war.me.handArea)

        assertTrue(
            CardTimingPolicy.isActionLegal(PlayAction({}, {}, brute), war),
        )
    }

    @Test
    fun `aredar brute is rejected when another card remains in hand`() {
        val war = warWithHero(health = 30, damage = 15)
        val brute = aredarBrute(cost = 4)
        war.addCard(brute, war.me.handArea)
        war.addCard(card("OTHER_CARD", "其他牌"), war.me.handArea)

        val action = PlayAction({}, {}, brute)
        assertFalse(CardTimingPolicy.isActionLegal(action, war))
        assertEquals("aredar-brute-not-last-card-in-hand", CardTimingPolicy.actionFilterReason(action, war))
    }

    @Test
    fun `zero-cost aredar brute is legal even when another card remains in hand`() {
        val war = warWithHero(health = 30, damage = 15)
        val brute = aredarBrute(cost = 0)
        war.addCard(brute, war.me.handArea)
        war.addCard(card("OTHER_CARD", "其他牌"), war.me.handArea)

        val action = PlayAction({}, {}, brute)
        assertTrue(CardTimingPolicy.isActionLegal(action, war))
        assertEquals(null, CardTimingPolicy.actionFilterReason(action, war))
    }

    @Test
    fun `aredar brute is rejected above effective cost four`() {
        val war = warWithHero(health = 30, damage = 15)
        val brute = aredarBrute(cost = 5)
        war.addCard(brute, war.me.handArea)

        val action = PlayAction({}, {}, brute)
        assertFalse(CardTimingPolicy.isActionLegal(action, war))
        assertEquals("aredar-brute-effective-cost-over-4", CardTimingPolicy.actionFilterReason(action, war))
    }

    @Test
    fun `aredar brute is rejected above current health fifteen`() {
        val war = warWithHero(health = 30, damage = 14)
        val brute = aredarBrute(cost = 4)
        war.addCard(brute, war.me.handArea)

        val action = PlayAction({}, {}, brute)
        assertFalse(CardTimingPolicy.isActionLegal(action, war))
        assertEquals("aredar-brute-hero-health-over-15", CardTimingPolicy.actionFilterReason(action, war))
    }

    @Test
    fun `aredar brute fails closed when hero or hand identity is unavailable`() {
        val noHeroWar = War(false)
        val noHeroMe = Player(playerId = "me", war = noHeroWar)
        noHeroWar.me = noHeroMe
        val noHeroBrute = aredarBrute(cost = 4)
        noHeroWar.addCard(noHeroBrute, noHeroMe.handArea)
        assertFalse(CardTimingPolicy.isActionLegal(PlayAction({}, {}, noHeroBrute), noHeroWar))

        val war = warWithHero(health = 30, damage = 15)
        val handBrute = aredarBrute(cost = 4)
        war.addCard(handBrute, war.me.handArea)
        val actionCard = aredarBrute(cost = 4).apply { entityId = "" }
        assertFalse(CardTimingPolicy.isActionLegal(PlayAction({}, {}, actionCard), war))
        assertEquals("aredar-brute-card-identity-unavailable", CardTimingPolicy.actionFilterReason(PlayAction({}, {}, actionCard), war))
    }

    @Test
    fun `common tree filter cannot be bypassed by a permissive deck model`() {
        val war = warWithHero(health = 30, damage = 15)
        war.me.resources = 4
        val brute = aredarBrute(cost = 4)
        war.addCard(brute, war.me.handArea)
        val permissiveModel = object : MctsDecisionModel {
            override fun isActionLegal(action: club.xiaojiawei.hsscriptcardsdk.bean.Action, war: War): Boolean = true
        }
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
                decisionModel = permissiveModel,
            ),
        )

        assertTrue(node.actions.none { it is PlayAction && it.creator?.cardId == CardTimingPolicy.AREDAR_BRUTE_ID })
    }

    private fun card(cardId: String, name: String): Card = Card(TestCardAction()).apply {
        this.cardId = cardId
        entityId = cardId
        entityName = name
    }

    private fun aredarBrute(cost: Int): Card = card(CardTimingPolicy.AREDAR_BRUTE_ID, "艾瑞达蛮兵").apply {
        cardType = CardTypeEnum.MINION
        this.cost = cost
    }

    private fun warWithHero(health: Int, damage: Int): War {
        val war = War(false)
        val me = Player(playerId = "me", war = war)
        val rival = Player(playerId = "rival", war = war)
        war.me = me
        war.rival = rival
        war.player1 = me
        war.player2 = rival
        war.currentPlayer = me
        war.isMyTurn = true
        val hero = card("HERO", "英雄").apply {
            cardType = CardTypeEnum.HERO
            this.health = health
            this.damage = damage
        }
        war.addCard(hero, me.playArea)
        return war
    }
}
