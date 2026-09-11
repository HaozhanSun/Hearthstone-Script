package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsActionOrderPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PirateConditionalDamageSpellPolicyTest {
    @Test
    fun `pirate changes simulated SW027 damage from two to five and kills five health target`() {
        val war = testWar()
        war.addCard(testCard("PIRATE", CardTypeEnum.MINION, CardRaceEnum.PIRATE), war.me.playArea)
        val target = testCard("TARGET", CardTypeEnum.MINION, CardRaceEnum.UNKNOWN).apply { health = 5 }
        val spell = testCard(PirateConditionalDamageSpellPolicy.CARD_ID, CardTypeEnum.SPELL, CardRaceEnum.UNKNOWN)
        war.addCard(target, war.rival.playArea)
        war.addCard(spell, war.me.handArea)

        val action = damageAction(spell, target)
        val before = war.clone()
        val after = war.clone().also { action.simulate.accept(it) }

        assertEquals(5, PirateConditionalDamageSpellPolicy.damageAtCast(war))
        assertTrue(PirateConditionalDamageSpellPolicy.canKill(action, war))
        assertEquals(3, PirateConditionalDamageSpellPolicy.applyConditionalDamage(before, after, action))
        assertTrue(after.rival.playArea.cards.isEmpty(), "the five-health target is killed once the conditional damage is applied")
        assertEquals(MctsActionOrderPhase.TACTICAL_SPELL, PirateWarriorMctsModel.actionOrderPhase(action, war))
        assertEquals(MctsActionOrderPhase.TACTICAL_SPELL, PirateDemonHunterMctsExperimentModel.actionOrderPhase(action, war))
    }

    @Test
    fun `without a pirate SW027 remains a two damage kill candidate`() {
        val war = testWar()
        val target = testCard("TARGET_2", CardTypeEnum.MINION, CardRaceEnum.UNKNOWN).apply { health = 2 }
        val spell = testCard(PirateConditionalDamageSpellPolicy.CARD_ID, CardTypeEnum.SPELL, CardRaceEnum.UNKNOWN)
        war.addCard(target, war.rival.playArea)
        war.addCard(spell, war.me.handArea)
        val action = damageAction(spell, target)
        val before = war.clone()
        val after = war.clone().also { action.simulate.accept(it) }

        assertEquals(2, PirateConditionalDamageSpellPolicy.damageAtCast(war))
        assertTrue(PirateConditionalDamageSpellPolicy.canKill(action, war))
        assertEquals(0, PirateConditionalDamageSpellPolicy.applyConditionalDamage(before, after, action))
        assertTrue(after.rival.playArea.cards.isEmpty(), "the two-health target is killed by the base damage")
    }

    @Test
    fun `divine shield absorbs the single conditional hit`() {
        val war = testWar()
        val target = testCard("SHIELDED_TARGET", CardTypeEnum.MINION, CardRaceEnum.UNKNOWN).apply {
            health = 4
            isDivineShield = true
        }
        val spell = testCard(PirateConditionalDamageSpellPolicy.CARD_ID, CardTypeEnum.SPELL, CardRaceEnum.UNKNOWN)
        war.addCard(target, war.rival.playArea)
        war.addCard(spell, war.me.handArea)
        val action = damageAction(spell, target)

        val before = war.clone()
        val after = war.clone().also { action.simulate.accept(it) }

        assertFalse(PirateConditionalDamageSpellPolicy.canKill(action, war))
        assertEquals(0, PirateConditionalDamageSpellPolicy.applyConditionalDamage(before, after, action))
        assertEquals(4, after.rival.playArea.findByEntityId(target.entityId)!!.blood())
    }

    @Test
    fun `nonlethal target remains a soft normal spell action`() {
        val war = testWar()
        val target = testCard("TARGET_6", CardTypeEnum.MINION, CardRaceEnum.UNKNOWN).apply { health = 6 }
        val spell = testCard(PirateConditionalDamageSpellPolicy.CARD_ID, CardTypeEnum.SPELL, CardRaceEnum.UNKNOWN)
        war.addCard(target, war.rival.playArea)
        war.addCard(spell, war.me.handArea)
        val action = damageAction(spell, target)

        assertFalse(PirateConditionalDamageSpellPolicy.canKill(action, war))
        assertTrue(PirateConditionalDamageSpellPolicy.softPrior(action, war) > 0.0)
        assertEquals(MctsActionOrderPhase.SPELL_PLAY, PirateWarriorMctsModel.actionOrderPhase(action, war))
    }

    @Test
    fun `no enemy target produces no tactical SW027 action`() {
        val war = testWar()
        val spell = testCard(PirateConditionalDamageSpellPolicy.CARD_ID, CardTypeEnum.SPELL, CardRaceEnum.UNKNOWN)
        war.addCard(spell, war.me.handArea)
        val action = PlayAction({}, {}, spell)

        assertFalse(PirateConditionalDamageSpellPolicy.canKill(action, war))
        assertEquals(-24.0, PirateConditionalDamageSpellPolicy.softPrior(action, war))
    }

    @Test
    fun `conditional damage is applied once across a replan clone`() {
        val war = testWar()
        war.addCard(testCard("PIRATE_2", CardTypeEnum.MINION, CardRaceEnum.PIRATE), war.me.playArea)
        val target = testCard("TARGET_REPLAN", CardTypeEnum.MINION, CardRaceEnum.UNKNOWN).apply { health = 7 }
        val spell = testCard(PirateConditionalDamageSpellPolicy.CARD_ID, CardTypeEnum.SPELL, CardRaceEnum.UNKNOWN)
        war.addCard(target, war.rival.playArea)
        war.addCard(spell, war.me.handArea)
        val action = damageAction(spell, target)

        val before = war.clone()
        val after = war.clone().also { action.simulate.accept(it) }
        assertEquals(3, PirateConditionalDamageSpellPolicy.applyConditionalDamage(before, after, action))
        assertEquals(5, after.rival.playArea.findByEntityId(target.entityId)!!.damage)

        val replanned = after.clone()
        // A fresh hypothetical SW_027 would still be a kill candidate at
        // this health; however, a re-plan that did not actually simulate a
        // second spell must not apply the conditional damage a second time.
        assertTrue(PirateConditionalDamageSpellPolicy.canKill(action, replanned))
        assertEquals(0, PirateConditionalDamageSpellPolicy.applyConditionalDamage(after, replanned, action))
        assertEquals(5, replanned.rival.playArea.findByEntityId(target.entityId)!!.damage)
    }

    private fun damageAction(spell: Card, target: Card): PlayAction = PlayAction(
        {},
        { newWar -> newWar.rival.playArea.findByEntityId(target.entityId)?.injured(2) },
        spell,
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
        me.resources = 10
        return war
    }

    private fun testCard(id: String, type: CardTypeEnum, race: CardRaceEnum): Card =
        Card(TestCardAction()).apply {
            cardId = id
            entityId = "$id-entity"
            entityName = id
            cardType = type
            cardRace = race
            cost = 1
            atc = 2
            health = 3
            action.belongCard = this
        }
}
