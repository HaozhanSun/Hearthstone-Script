package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.InitAction
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MonteCarloTreeNode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline regressions for Pirate Warrior issues #16, #17 and #18. */
class PirateWarriorIssues16To18Test {
    @Test
    fun `hookfist attack is deferred behind a legal hero attack`() {
        val war = testWar(mana = 0)
        val hero = card("WARRIOR_HERO", "hero", CardTypeEnum.HERO, attack = 3).apply {
            cardRace = CardRaceEnum.UNKNOWN
            isExhausted = false
        }
        val rivalHero = card("RIVAL_HERO", "rival-hero", CardTypeEnum.HERO, attack = 0).apply {
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
        }
        val hookfist = card("CORE_NX2_028", "hookfist", CardTypeEnum.MINION, attack = 4).apply {
            isExhausted = false
        }
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(hookfist, war.me.playArea)

        val hookfistAttack = hookfist.action.generateAttackActions(war, war.me).first()
        assertTrue(PirateWarriorMctsModel.isDeferredAction(hookfistAttack, war))
        assertTrue(
            PirateWarriorMctsModel.actionFilterReason(hookfistAttack, war)
                == "hookfist-attack-deferred-behind-hero-attack",
        )
        assertTrue(
            PirateWarriorMctsModel.actionOrderPhase(hookfistAttack, war)?.name == "HERO_ATTACK",
        )

        val root = MonteCarloTreeNode(war, InitAction, testArg())
        assertTrue(root.actions.any { it is AttackAction && it.creator?.entityId == hero.entityId })
        assertTrue(root.actions.none { it is AttackAction && it.creator?.entityId == hookfist.entityId })
    }

    @Test
    fun `hookfist remains available when hero cannot attack`() {
        val war = testWar(mana = 0)
        val hero = card("WARRIOR_EXHAUSTED_HERO", "hero", CardTypeEnum.HERO, attack = 0).apply {
            cardRace = CardRaceEnum.UNKNOWN
            isExhausted = true
        }
        val rivalHero = card("RIVAL_HERO_NO_ATTACK", "rival-hero", CardTypeEnum.HERO, attack = 0).apply {
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
        }
        val hookfist = card("NX2_028", "hookfist", CardTypeEnum.MINION, attack = 4).apply {
            isExhausted = false
        }
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(hookfist, war.me.playArea)

        val hookfistAttack = hookfist.action.generateAttackActions(war, war.me).first()
        assertFalse(PirateWarriorMctsModel.isDeferredAction(hookfistAttack, war))
    }

    @Test
    fun `juggernaut trigger is skipped only when a weapon is already equipped`() {
        val withoutWeapon = testWar(mana = 0)
        val juggernaut = card("SW_028t6", "juggernaut", CardTypeEnum.MINION, attack = 0)
        withoutWeapon.addCard(juggernaut, withoutWeapon.me.playArea)
        assertTrue(PirateWarriorMctsModel.shouldSimulateTurnStart(juggernaut, withoutWeapon))

        val withWeapon = testWar(mana = 0)
        val equipped = card("BAR_844", "equipped-weapon", CardTypeEnum.WEAPON, attack = 3)
        withWeapon.me.playArea.weapon = equipped
        val sameJuggernaut = card("SW_028t6", "juggernaut-equipped", CardTypeEnum.MINION, attack = 0)
        withWeapon.addCard(sameJuggernaut, withWeapon.me.playArea)
        assertFalse(PirateWarriorMctsModel.shouldSimulateTurnStart(sameJuggernaut, withWeapon))
    }

    @Test
    fun `summoned southsea deckhand is evaluated and exposed without a hand play event`() {
        val war = testWar(mana = 0)
        val rivalHero = card("RIVAL_HERO_DECKHAND", "rival-hero", CardTypeEnum.HERO, attack = 0).apply {
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
        }
        val summoned = card(
            PirateWarriorMctsModel.SOUTHSEA_DECKHAND,
            "summoned-deckhand",
            CardTypeEnum.MINION,
            attack = 2,
        ).apply {
            // A summoned state can arrive before the parser has populated its
            // tribe. The stable ID must still make it a Pirate for planning.
            cardRace = CardRaceEnum.UNKNOWN
            isExhausted = false
        }
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(summoned, war.me.playArea)

        assertTrue(PirateWarriorMctsModel.isPirate(summoned))
        assertTrue(PirateWarriorMctsModel.effectivePirateAttack(summoned, war) >= 2)

        val root = MonteCarloTreeNode(war, InitAction, testArg())
        assertTrue(root.actions.any { it is AttackAction && it.creator?.entityId == summoned.entityId })
    }

    private fun testWar(mana: Int): War {
        val war = War()
        val me = Player(playerId = "me", war = war)
        val rival = Player(playerId = "rival", war = war)
        war.me = me
        war.rival = rival
        war.player1 = me
        war.player2 = rival
        war.currentPlayer = me
        war.isMyTurn = true
        me.turn = 3
        me.resources = mana
        return war
    }

    private fun testArg(): MCTSArg = MCTSArg(
        endMillisTime = Long.MAX_VALUE,
        turnCount = 1,
        turnFactor = 0.5,
        countPerTurn = 1,
        scoreCalculator = { 0.0 },
        enableMultiThread = false,
        decisionModel = PirateWarriorMctsModel,
        experimentalSearch = true,
    )

    private fun card(id: String, entityId: String, type: CardTypeEnum, attack: Int): Card =
        Card(TestCardAction()).apply {
            cardId = id
            this.entityId = entityId
            entityName = id
            cardType = type
            cardRace = CardRaceEnum.PIRATE
            atc = attack
            health = 3
            action.belongCard = this
        }
}
