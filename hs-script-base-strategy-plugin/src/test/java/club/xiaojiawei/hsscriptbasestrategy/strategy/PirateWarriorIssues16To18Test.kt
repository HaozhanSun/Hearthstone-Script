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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline regressions for Pirate Warrior issues #16, #17 and #18. */
class PirateWarriorIssues16To18Test {
    @Test
    fun `frontline axe minion kill is deferred behind a ready friendly minion`() {
        val war = testWar(mana = 0)
        val hero = card("WARRIOR_HERO_AXE_ORDER", "hero-axe-order", CardTypeEnum.HERO, attack = 3).apply {
            cardRace = CardRaceEnum.UNKNOWN
            isExhausted = false
        }
        val rivalHero = card("RIVAL_HERO_AXE_ORDER", "rival-hero-axe-order", CardTypeEnum.HERO, attack = 0).apply {
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
        }
        val axe = card("BAR_844", "axe-order-weapon", CardTypeEnum.WEAPON, attack = 3).apply {
            durability = 2
        }
        val readyMinion = card("READY_MINION_AXE_ORDER", "ready-minion-axe-order", CardTypeEnum.MINION, attack = 2).apply {
            isExhausted = false
        }
        val target = card("AXE_ORDER_TARGET", "axe-order-target", CardTypeEnum.MINION, attack = 1).apply {
            health = 3
            cardRace = CardRaceEnum.UNKNOWN
        }
        war.addCard(hero, war.me.playArea)
        war.me.playArea.weapon = axe
        war.addCard(readyMinion, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(target, war.rival.playArea)

        val axeAttack = hero.action.generateAttackActions(war, war.me)
            .single { it.targetEntityId == target.entityId }

        assertTrue(PirateWarriorMctsModel.isActionLegal(axeAttack, war))
        assertTrue(PirateWarriorMctsModel.isDeferredAction(axeAttack, war))
        assertEquals(
            "frontline-axe-attack-deferred-behind-other-action",
            PirateWarriorMctsModel.actionFilterReason(axeAttack, war),
        )
    }

    @Test
    fun `hozen roughhouser attack is deferred while another minion can attack`() {
        val war = testWar(mana = 0)
        val hozen = card(
            PirateWarriorMctsModel.HOZEN_ROUGHHOUSER,
            "hozen-order",
            CardTypeEnum.MINION,
            attack = 2,
        ).apply { isExhausted = false }
        val otherPirate = card("OTHER_READY_PIRATE_HOZEN_ORDER", "other-hozen-order", CardTypeEnum.MINION, attack = 2).apply {
            isExhausted = false
        }
        val rivalHero = card("RIVAL_HERO_HOZEN_ORDER", "rival-hero-hozen-order", CardTypeEnum.HERO, attack = 0).apply {
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
        }
        war.addCard(hozen, war.me.playArea)
        war.addCard(otherPirate, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)

        val hozenAttack = hozen.action.generateAttackActions(war, war.me).first()
        assertTrue(PirateAttackOrderPolicy.shouldDeferHozenRoughhouserAttack(hozenAttack, war))
        assertTrue(PirateWarriorMctsModel.isDeferredAction(hozenAttack, war))
        assertEquals(
            "hozen-roughhouser-attack-deferred-behind-other-pirates",
            PirateWarriorMctsModel.actionFilterReason(hozenAttack, war),
        )
    }

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

    @Test
    fun `enzos first mate is blocked when a weapon is already equipped`() {
        val war = testWar(mana = 1)
        val mate = card(
            PirateWarriorMctsModel.NZOTHS_FIRST_MATE,
            "first-mate-equipped",
            CardTypeEnum.MINION,
            attack = 1,
        ).apply { cost = 1 }
        val weapon = card("EQUIPPED_WEAPON", "equipped-weapon", CardTypeEnum.WEAPON, attack = 3)
        war.addCard(mate, war.me.handArea)
        war.addCard(weapon, war.me.playArea)

        val action = club.xiaojiawei.hsscriptcardsdk.bean.PlayAction({}, {}, mate)
        assertFalse(PirateWarriorMctsModel.isActionLegal(action, war))
        assertTrue(PirateWarriorMctsModel.isDeferredAction(action, war))
        assertTrue(PirateWarriorMctsModel.shouldDefer(mate, war))
        assertTrue(PirateWarriorMctsModel.actionPrior(action, war) <= -1_000.0)

        val root = MonteCarloTreeNode(war, InitAction, testArg())
        assertTrue(root.actions.none { it.creator?.entityId == mate.entityId })

        val replacement = card("GENERATED_RUSTY_HOOK", "generated-rusty-hook", CardTypeEnum.WEAPON, attack = 1)
        val staleSimulation = club.xiaojiawei.hsscriptcardsdk.bean.PlayAction(
            {},
            { simulated -> simulated.me.playArea.weapon = replacement },
            mate,
        )
        val afterStaleSimulation = root.buildNextNode(staleSimulation).state.war
        assertEquals(weapon.entityId, afterStaleSimulation.me.playArea.weapon?.entityId)
    }

    @Test
    fun `enzos first mate remains playable when no weapon is equipped`() {
        val war = testWar(mana = 1)
        val mate = card(
            PirateWarriorMctsModel.NZOTHS_FIRST_MATE,
            "first-mate-free",
            CardTypeEnum.MINION,
            attack = 1,
        ).apply { cost = 1 }
        war.addCard(mate, war.me.handArea)

        val action = club.xiaojiawei.hsscriptcardsdk.bean.PlayAction({}, {}, mate)
        assertTrue(PirateWarriorMctsModel.isActionLegal(action, war))
        assertFalse(PirateWarriorMctsModel.isDeferredAction(action, war))
        assertFalse(PirateWarriorMctsModel.shouldDefer(mate, war))
        assertTrue(PirateWarriorMctsModel.actionPrior(action, war) > 0.0)
    }

    @Test
    fun `first mate avoids an unprofitable two-two trade but keeps kill and combo lines`() {
        val war = testWar(mana = 0)
        val rivalHero = card("RIVAL_HERO_TRADE", "rival-hero", CardTypeEnum.HERO, attack = 0).apply {
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
        }
        val mate = card(
            PirateWarriorMctsModel.NZOTHS_FIRST_MATE,
            "first-mate-trade",
            CardTypeEnum.MINION,
            attack = 1,
        ).apply { isExhausted = false }
        val target = card("ENEMY_TWO_TWO", "enemy-two-two", CardTypeEnum.MINION, attack = 2).apply {
            health = 2
        }
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(mate, war.me.playArea)
        war.addCard(target, war.rival.playArea)

        val unsafeAttack = mate.action.generateAttackActions(war, war.me)
            .single { it.targetEntityId == target.entityId }
        assertFalse(PirateWarriorMctsModel.isActionLegal(unsafeAttack, war))
        assertTrue(
            PirateWarriorMctsModel.actionFilterReason(unsafeAttack, war) ==
                "minion-attack-no-lethal-or-tactical-benefit",
        )

        target.health = 1
        val killAttack = mate.action.generateAttackActions(war, war.me)
            .single { it.targetEntityId == target.entityId }
        assertTrue(PirateWarriorMctsModel.isActionLegal(killAttack, war))

        target.health = 2
        val combo = card("COMBO_PIRATE", "combo-pirate", CardTypeEnum.MINION, attack = 1).apply {
            isExhausted = false
        }
        war.addCard(combo, war.me.playArea)
        val comboAttack = mate.action.generateAttackActions(war, war.me)
            .single { it.targetEntityId == target.entityId }
        assertTrue(PirateWarriorMctsModel.isActionLegal(comboAttack, war))
    }

    @Test
    fun `unprofitable taunt trade is blocked`() {
        val war = testWar(mana = 0)
        val rivalHero = card("RIVAL_HERO_TAUNT", "rival-hero", CardTypeEnum.HERO, attack = 0).apply {
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
        }
        val mate = card(
            PirateWarriorMctsModel.NZOTHS_FIRST_MATE,
            "first-mate-taunt",
            CardTypeEnum.MINION,
            attack = 1,
        ).apply { isExhausted = false }
        val taunt = card("ENEMY_TAUNT_TWO_TWO", "enemy-taunt", CardTypeEnum.MINION, attack = 2).apply {
            health = 2
            isTaunt = true
        }
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(mate, war.me.playArea)
        war.addCard(taunt, war.rival.playArea)

        val attack = mate.action.generateAttackActions(war, war.me)
            .single { it.targetEntityId == taunt.entityId }
        assertFalse(PirateWarriorMctsModel.isActionLegal(attack, war))
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
