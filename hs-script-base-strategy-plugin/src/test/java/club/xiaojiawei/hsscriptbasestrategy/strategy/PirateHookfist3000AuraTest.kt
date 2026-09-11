package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.PirateDamageAuraPolicy
import club.xiaojiawei.hsscriptcardsdk.util.CardUtil
import kotlin.test.Test
import kotlin.test.assertEquals

/** Offline E2E coverage for the temporary, per-turn Pirate damage aura. */
class PirateHookfist3000AuraTest {
    @Test
    fun `zero through three hookfists scale both pirate models`() {
        for (count in 0..3) {
            val war = testWar()
            repeat(count) { index ->
                war.addCard(card(PirateDamageAuraPolicy.HOOKFIST_CORE_ID, "hookfist-$index"), war.me.playArea)
            }
            val attacker = card("PIRATE_ATTACKER", "attacker").apply {
                atc = 2
                isExhausted = false
            }
            war.addCard(attacker, war.me.playArea)

            assertEquals(2 + count, PirateDamageAuraPolicy.outgoingDamage(attacker, 2, war))
            assertEquals(2 + count, PirateWarriorMctsModel.effectivePirateAttack(attacker, war))
            assertEquals(2 + count, PirateDemonHunterMctsExperimentModel.effectivePirateAttack(attacker, war))
        }
    }

    @Test
    fun `simulation and lethal calculation use one current turn bonus per pirate event`() {
        val war = testWar()
        repeat(3) { index ->
            war.addCard(card(PirateDamageAuraPolicy.HOOKFIST_CORE_ID, "hookfist-$index"), war.me.playArea)
        }
        val attacker = card("PIRATE_ATTACKER", "attacker").apply {
            atc = 2
            isExhausted = false
        }
        val rivalHero = card("RIVAL_HERO", "rival-hero").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 30
            isExhausted = false
        }
        war.addCard(attacker, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)

        assertEquals(5, PirateLethalAttackPolicy.legalFaceDamage(war))
        CardUtil.simulateAttack(war, attacker, rivalHero)
        assertEquals(5, rivalHero.damage)
    }

    @Test
    fun `non pirate and outside current turn do not receive the aura`() {
        val war = testWar()
        war.addCard(card(PirateDamageAuraPolicy.HOOKFIST_CORE_ID, "hookfist"), war.me.playArea)
        val nonPirate = card("NON_PIRATE", "non-pirate").apply { cardRace = CardRaceEnum.DEMON }
        assertEquals(2, PirateDamageAuraPolicy.outgoingDamage(nonPirate, 2, war))

        war.currentPlayer = war.rival
        war.isMyTurn = false
        val pirate = card("PIRATE", "pirate")
        assertEquals(2, PirateDamageAuraPolicy.outgoingDamage(pirate, 2, war))
    }

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
        me.resources = 10
        return war
    }

    private fun card(cardId: String, entityId: String): Card = Card(TestCardAction()).apply {
        this.cardId = cardId
        this.entityId = entityId
        entityName = cardId
        cardType = CardTypeEnum.MINION
        cardRace = CardRaceEnum.PIRATE
        health = 3
        action.belongCard = this
    }
}
