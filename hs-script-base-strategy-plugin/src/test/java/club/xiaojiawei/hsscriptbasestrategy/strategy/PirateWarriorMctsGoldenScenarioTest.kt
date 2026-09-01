package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.InitAction
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MonteCarloTreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline golden scenarios for Pirate Warrior state/action/evaluation rules. */
class PirateWarriorMctsGoldenScenarioTest {
    @Test
    fun `distributor is mandatory only after cannon and first turn quest constraints`() {
        val war = testWar(turn = 2, mana = 1)
        val distributor = testCard(PirateWarriorMctsModel.TREASURE_DISTRIBUTOR, 1)
        war.addCard(distributor, war.me.handArea)

        assertTrue(
            PirateWarriorMctsModel.isMandatoryAction(PlayAction({}, {}, distributor), war),
        )
    }

    @Test
    fun `mulligan removes every Patches copy`() {
        val strategy = HsPirateWarriorMctsDeckStrategy()
        val patches = testCard(PirateWarriorMctsModel.PATCHES_THE_PIRATE, 1)
        val ordinary = testCard("ORDINARY_PIRATE", 1)
        val cards = hashSetOf(patches, ordinary)

        strategy.executeChangeCard(cards)

        assertFalse(cards.contains(patches))
        assertTrue(cards.contains(ordinary))
    }

    @Test
    fun `southsea aura is counted but hozen does not add an attack-event bonus`() {
        val war = testWar(turn = 2, mana = 4)
        val captain = testCard(PirateWarriorMctsModel.SOUTHSEA_CAPTAIN, 3, 3)
        val hozen = testCard(PirateWarriorMctsModel.HOZEN_ROUGHHOUSER, 3, 2)
        val pirate = testCard("ATTACKING_PIRATE", 1, 2)
        war.addCard(captain, war.me.playArea)
        war.addCard(hozen, war.me.playArea)
        war.addCard(pirate, war.me.playArea)

        assertEquals(3, PirateWarriorMctsModel.effectivePirateAttack(pirate, war))
        assertEquals(3, PirateWarriorMctsModel.effectivePirateAttack(captain, war))
        assertEquals(3, PirateWarriorMctsModel.effectivePirateAttack(hozen, war))
    }

    @Test
    fun `southsea combat bonus is temporary during simulation and is cleaned up`() {
        val war = testWar(turn = 2, mana = 4)
        val captain = testCard(PirateWarriorMctsModel.SOUTHSEA_CAPTAIN, 3, 3)
        val hozen = testCard(PirateWarriorMctsModel.HOZEN_ROUGHHOUSER, 3, 2)
        val attacker = testCard("ATTACKING_PIRATE", 1, 2)
        war.addCard(captain, war.me.playArea)
        war.addCard(hozen, war.me.playArea)
        war.addCard(attacker, war.me.playArea)
        val attack = AttackAction({}, {}, attacker)

        PirateWarriorMctsModel.beforeSimulatedAction(war, attack)
        assertEquals(3, attacker.atc)
        PirateWarriorMctsModel.afterSimulatedAction(war, war, attack)
        assertEquals(2, attacker.atc)
        assertEquals(0, attacker.mctsTemporaryAttackBonus)
    }

    @Test
    fun `hozen battlecry buffs only other pirates already on board`() {
        val war = testWar(turn = 2, mana = 3)
        val hozen = testCard(PirateWarriorMctsModel.HOZEN_ROUGHHOUSER, 3, 2).apply { entityId = "HOZEN_IN_HAND" }
        val existingPirate = testCard("EXISTING_PIRATE", 1, 2).apply { health = 2 }
        val secondHozen = testCard(PirateWarriorMctsModel.HOZEN_ROUGHHOUSER, 3, 2).apply {
            entityId = "SECOND_HOZEN_ON_BOARD"
            health = 4
        }
        val nonPirate = testCard("NON_PIRATE", 1, 2).apply {
            cardRace = CardRaceEnum.UNKNOWN
            health = 2
        }
        val handPirate = testCard("HAND_PIRATE", 1, 2).apply { health = 2 }
        war.addCard(hozen, war.me.handArea)
        war.addCard(existingPirate, war.me.playArea)
        war.addCard(secondHozen, war.me.playArea)
        war.addCard(nonPirate, war.me.playArea)
        war.addCard(handPirate, war.me.handArea)

        val play = hozen.action.generatePlayActions(war, war.me).single()
        val after = MonteCarloTreeNode(war, InitAction, testArg()).buildNextNode(play).state.war

        assertEquals(3, after.me.playArea.findByEntityId(existingPirate.entityId)?.atc)
        assertEquals(3, after.me.playArea.findByEntityId(existingPirate.entityId)?.health)
        assertEquals(3, after.me.playArea.findByEntityId(secondHozen.entityId)?.atc)
        assertEquals(5, after.me.playArea.findByEntityId(secondHozen.entityId)?.health)
        assertEquals(2, after.me.playArea.findByEntityId(nonPirate.entityId)?.atc)
        assertEquals(2, after.me.playArea.findByEntityId(nonPirate.entityId)?.health)
        assertEquals(2, after.me.handArea.findByEntityId(handPirate.entityId)?.atc)
        assertEquals(2, after.me.handArea.findByEntityId(handPirate.entityId)?.health)
        assertEquals(2, after.me.playArea.findByEntityId(hozen.entityId)?.atc)
        assertEquals(3, after.me.playArea.findByEntityId(hozen.entityId)?.health)
    }

    @Test
    fun `hozen with no other pirate leaves itself unchanged and a second hozen is a valid target`() {
        val soloWar = testWar(turn = 2, mana = 3)
        val soloHozen = testCard(PirateWarriorMctsModel.HOZEN_ROUGHHOUSER, 3, 2)
        soloWar.addCard(soloHozen, soloWar.me.handArea)
        val soloPlay = soloHozen.action.generatePlayActions(soloWar, soloWar.me).single()
        val soloAfter = MonteCarloTreeNode(soloWar, InitAction, testArg()).buildNextNode(soloPlay).state.war
        assertEquals(2, soloAfter.me.playArea.findByEntityId(soloHozen.entityId)?.atc)
        assertEquals(3, soloAfter.me.playArea.findByEntityId(soloHozen.entityId)?.health)

        val chainWar = testWar(turn = 2, mana = 6)
        val firstHozen = testCard(PirateWarriorMctsModel.HOZEN_ROUGHHOUSER, 3, 2).apply { entityId = "FIRST_HOZEN_ON_BOARD" }
        val secondHozen = testCard(PirateWarriorMctsModel.HOZEN_ROUGHHOUSER, 3, 2).apply { entityId = "SECOND_HOZEN_IN_HAND" }
        val pirate = testCard("BOARD_PIRATE", 1, 2).apply { health = 2 }
        chainWar.addCard(firstHozen, chainWar.me.playArea)
        chainWar.addCard(secondHozen, chainWar.me.handArea)
        chainWar.addCard(pirate, chainWar.me.playArea)
        val secondPlay = secondHozen.action.generatePlayActions(chainWar, chainWar.me).single()
        val chainAfter = MonteCarloTreeNode(chainWar, InitAction, testArg()).buildNextNode(secondPlay).state.war

        assertEquals(3, chainAfter.me.playArea.findByEntityId(firstHozen.entityId)?.atc)
        assertEquals(4, chainAfter.me.playArea.findByEntityId(firstHozen.entityId)?.health)
        assertEquals(3, chainAfter.me.playArea.findByEntityId(pirate.entityId)?.atc)
        assertEquals(3, chainAfter.me.playArea.findByEntityId(pirate.entityId)?.health)
        assertEquals(2, chainAfter.me.playArea.findByEntityId(secondHozen.entityId)?.atc)
        assertEquals(3, chainAfter.me.playArea.findByEntityId(secondHozen.entityId)?.health)
    }

    @Test
    fun `hookfist is downranked with a playable minion but rises when weapon fits this turn`() {
        val noWeapon = testWar(turn = 2, mana = 3)
        val hookfist = testCard(PirateWarriorMctsModel.HOOKFIST, 3)
        val minion = testCard("OTHER_MINION", 1)
        noWeapon.addCard(hookfist, noWeapon.me.handArea)
        noWeapon.addCard(minion, noWeapon.me.handArea)
        val noWeaponPrior = PirateWarriorMctsModel.actionPrior(PlayAction({}, {}, hookfist), noWeapon)

        val withWeapon = testWar(turn = 2, mana = 4)
        val hookfist2 = testCard(PirateWarriorMctsModel.HOOKFIST, 3)
        val weapon = testCard("WEAPON_IN_HAND", 1).apply { cardType = CardTypeEnum.WEAPON }
        withWeapon.addCard(hookfist2, withWeapon.me.handArea)
        withWeapon.addCard(weapon, withWeapon.me.handArea)
        val withWeaponPrior = PirateWarriorMctsModel.actionPrior(PlayAction({}, {}, hookfist2), withWeapon)

        assertTrue(noWeaponPrior < 0.0)
        assertTrue(withWeaponPrior > noWeaponPrior)
    }

    @Test
    fun `equipped weapon with durability keeps hookfist high and downranks replacement`() {
        val war = testWar(turn = 2, mana = 4)
        val hookfist = testCard(PirateWarriorMctsModel.HOOKFIST, 3)
        val equipped = testCard("EQUIPPED_WEAPON", 2).apply {
            cardType = CardTypeEnum.WEAPON
            durability = 2
        }
        val newWeapon = testCard("NEW_WEAPON", 1).apply {
            cardType = CardTypeEnum.WEAPON
        }
        war.me.playArea.weapon = equipped
        war.addCard(hookfist, war.me.handArea)
        war.addCard(newWeapon, war.me.handArea)

        assertTrue(PirateWarriorMctsModel.actionPrior(PlayAction({}, {}, hookfist), war) > 0.0)
        assertTrue(PirateWarriorMctsModel.actionPrior(PlayAction({}, {}, newWeapon), war) < 16.0)
    }

    @Test
    fun `frontline axe blocks face at ten health but permits it below ten`() {
        val atTen = frontlineAxeWar(heroHealth = 10, rivalHeroHealth = 30, minionHealth = 3)
        val atTenActions = heroAttackActions(atTen)
        val atTenLegal = atTenActions.filter { PirateWarriorMctsModel.isActionLegal(it, atTen) }
        assertEquals(1, atTenLegal.size)
        assertTrue(atTenLegal.all { hitsRivalMinion(it, atTen) })

        val atNine = frontlineAxeWar(heroHealth = 9, rivalHeroHealth = 30, minionHealth = 3)
        val atNineActions = heroAttackActions(atNine)
        val atNineLegal = atNineActions.filter { PirateWarriorMctsModel.isActionLegal(it, atNine) }
        assertEquals(2, atNineLegal.size)
        assertTrue(atNineLegal.any { !hitsRivalMinion(it, atNine) })

        val lethal = frontlineAxeWar(heroHealth = 9, rivalHeroHealth = 3, minionHealth = 3)
        val lethalFace = heroAttackActions(lethal).first { !hitsRivalMinion(it, lethal) }
        assertTrue(PirateWarriorMctsModel.isActionLegal(lethalFace, lethal))
        assertTrue(PirateWarriorMctsModel.actionPrior(lethalFace, lethal) >= 80.0)
    }

    @Test
    fun `frontline axe only rewards a minion kill and consumes one durability`() {
        val nonKill = frontlineAxeWar(heroHealth = 9, rivalHeroHealth = 30, minionHealth = 7)
        val nonKillAction = heroAttackActions(nonKill).first { hitsRivalMinion(it, nonKill) }
        val nonKillAfter = simulate(nonKillAction, nonKill)
        assertTrue(PirateWarriorMctsModel.actionPrior(nonKillAction, nonKill) < 0.0)
        assertEquals(0.0, PirateWarriorMctsModel.afterSimulatedAction(nonKill, nonKillAfter, nonKillAction).expectedReward)
        assertEquals(1, nonKillAfter.me.playArea.weapon?.damage)
        assertEquals(2, nonKillAfter.me.playArea.weapon?.blood())
        assertEquals(4, nonKillAfter.rival.playArea.cards.single().blood())

        val exactKill = frontlineAxeWar(heroHealth = 9, rivalHeroHealth = 30, minionHealth = 3)
        val killAction = heroAttackActions(exactKill).first { hitsRivalMinion(it, exactKill) }
        val killAfter = simulate(killAction, exactKill)
        assertTrue(PirateWarriorMctsModel.actionPrior(killAction, exactKill) > 0.0)
        assertEquals(18.0, PirateWarriorMctsModel.afterSimulatedAction(exactKill, killAfter, killAction).expectedReward)
        assertEquals(1, killAfter.me.playArea.weapon?.damage)
        assertEquals(2, killAfter.me.playArea.weapon?.blood())

        val lastDurability = frontlineAxeWar(heroHealth = 9, rivalHeroHealth = 30, minionHealth = 3).also {
            it.me.playArea.weapon?.durability = 1
        }
        val lastDurabilityAction = heroAttackActions(lastDurability).first { hitsRivalMinion(it, lastDurability) }
        val lastDurabilityAfter = simulate(lastDurabilityAction, lastDurability)
        val spentWeapon = lastDurabilityAfter.me.playArea.weapon
        assertTrue(spentWeapon == null || (!spentWeapon.isAlive() && spentWeapon.damage >= 1))
    }

    @Test
    fun `frontline axe attacks are deferred behind a ready pirate and multiple minions stay targetable`() {
        val war = frontlineAxeWar(heroHealth = 10, rivalHeroHealth = 30, minionHealth = 3, minionCount = 2)
        war.addCard(testCard("READY_PIRATE", 1, 2), war.me.playArea)
        val actions = heroAttackActions(war)
        val minionActions = actions.filter { hitsRivalMinion(it, war) }

        assertEquals(2, minionActions.size)
        assertTrue(minionActions.all { PirateWarriorMctsModel.isActionLegal(it, war) })
        assertTrue(minionActions.all { PirateWarriorMctsModel.isDeferredAction(it, war) })
    }

    @Test
    fun `frontline axe has no legal attack action when no rival target exists`() {
        val war = testWar(turn = 2, mana = 4)
        val hero = testHero("MY_HERO", health = 9, attack = 3)
        val weapon = testWeapon(PirateWarriorMctsModel.FRONTLINE_AXE, attack = 3, durability = 3)
        war.addCard(hero, war.me.playArea)
        war.addCard(weapon, war.me.playArea)

        assertTrue(heroAttackActions(war).isEmpty())
    }

    @Test
    fun `captain crowley is hard-gated at three empty slots`() {
        listOf(0, 1, 2, 3, 7).forEach { freeSlots ->
            val war = testWar(turn = 2, mana = 5)
            repeat(7 - freeSlots) { index ->
                war.addCard(testCard("OCCUPIED_$freeSlots-$index", 1), war.me.playArea)
            }
            val crowley = testCard(PirateWarriorMctsModel.CAPTAIN_CROWLEY, 5)
            val action = PlayAction({}, {}, crowley)

            assertEquals(freeSlots >= 3, PirateWarriorMctsModel.isActionLegal(action, war))
            assertEquals(freeSlots >= 3, PirateWarriorMctsModel.actionPrior(action, war) > 0.0)
        }
    }

    @Test
    fun `warrior hero power is last resort while minions or attacks remain`() {
        val withMinion = testWar(turn = 2, mana = 2)
        val power = testHeroPower()
        val minion = testCard("PLAYABLE_PIRATE", 1)
        withMinion.addCard(power, withMinion.me.playArea)
        withMinion.addCard(minion, withMinion.me.handArea)
        val powerAction = PowerAction({}, {}, power)
        val minionAction = PlayAction({}, {}, minion)

        assertTrue(PirateWarriorMctsModel.isDeferredAction(powerAction, withMinion))
        assertTrue(PirateWarriorMctsModel.actionPrior(powerAction, withMinion) <
            PirateWarriorMctsModel.actionPrior(minionAction, withMinion))

        val withAttack = testWar(turn = 2, mana = 2)
        val attackPower = testHeroPower()
        val attacker = testCard("READY_PIRATE", 1)
        withAttack.addCard(testHero("MY_HERO", health = 4), withAttack.me.playArea)
        val rivalHero = testHero("RIVAL_HERO", health = 30)
        withAttack.addCard(attackPower, withAttack.me.playArea)
        withAttack.addCard(attacker, withAttack.me.playArea)
        withAttack.addCard(rivalHero, withAttack.rival.playArea)
        assertTrue(PirateWarriorMctsModel.isDeferredAction(PowerAction({}, {}, attackPower), withAttack))

        val onlyPower = testWar(turn = 2, mana = 2)
        val onlyPowerCard = testHeroPower()
        onlyPower.addCard(onlyPowerCard, onlyPower.me.playArea)
        assertFalse(PirateWarriorMctsModel.isDeferredAction(PowerAction({}, {}, onlyPowerCard), onlyPower))

        val insufficient = testWar(turn = 2, mana = 1)
        val insufficientPower = testHeroPower().apply { cost = 2 }
        insufficient.addCard(insufficientPower, insufficient.me.playArea)
        assertFalse(PirateWarriorMctsModel.isDeferredAction(PowerAction({}, {}, insufficientPower), insufficient))
    }

    @Test
    fun `unknown card is fail safe and does not receive opaque action`() {
        val war = testWar(turn = 2, mana = 2)
        val unknown = testCard("CAP_UNKNOWN", 2).apply {
            isUncertain = true
            cardType = CardTypeEnum.UNKNOWN
            cardRace = CardRaceEnum.UNKNOWN
        }
        war.addCard(unknown, war.me.handArea)

        val node = MonteCarloTreeNode(war, InitAction, testArg())

        assertTrue(node.actions.isNotEmpty())
        assertTrue(node.actions.all { it.creator == null })
        assertFalse(PirateWarriorMctsModel.canCreateOpaqueAction(unknown, war))
    }

    @Test
    fun `unverified ship and generated-effect cards do not get opaque fallback`() {
        val war = testWar(turn = 2, mana = 5)
        listOf(
            PirateWarriorMctsModel.BLASTPOWDER_ENGINEER,
            PirateWarriorMctsModel.CANNONMASTER,
            PirateWarriorMctsModel.HOOK_N_HEAVE,
            PirateWarriorMctsModel.CAPTAIN_CROWLEY,
        ).forEach { cardId ->
            assertFalse(PirateWarriorMctsModel.canCreateOpaqueAction(testCard(cardId, 2), war))
        }
    }

    @Test
    fun `full board filters minion action and leaves end turn`() {
        val war = testWar(turn = 2, mana = 3)
        repeat(7) { index ->
            war.addCard(testCard("BOARD_$index", 1), war.me.playArea)
        }
        val handMinion = testCard("HAND_MINION", 1)
        war.addCard(handMinion, war.me.handArea)

        val node = MonteCarloTreeNode(war, InitAction, testArg())

        assertTrue(node.actions.any { it.creator == null })
        assertFalse(node.actions.any { it.creator?.entityId == handMinion.entityId })
    }

    @Test
    fun `evaluation penalizes available opponent lethal pressure`() {
        val war = testWar(turn = 2, mana = 3)
        war.me.playArea.hero = testHero("MY_HERO", health = 10)
        war.rival.playArea.hero = testHero("RIVAL_HERO", health = 30)
        val safeScore = PirateWarriorMctsModel.scoreAdjustment(war)

        war.addCard(testCard("RIVAL_ATTACKER", 1, attack = 12), war.rival.playArea)

        assertTrue(PirateWarriorMctsModel.scoreAdjustment(war) < safeScore - 80.0)
    }

    @Test
    fun `evaluation rewards potential face lethal only without a taunt`() {
        val war = testWar(turn = 2, mana = 3)
        war.me.playArea.hero = testHero("MY_HERO", health = 30)
        war.rival.playArea.hero = testHero("RIVAL_HERO", health = 3)
        val baseline = PirateWarriorMctsModel.scoreAdjustment(war)
        war.addCard(testCard("ATTACKING_PIRATE", 1, attack = 3), war.me.playArea)

        assertTrue(PirateWarriorMctsModel.scoreAdjustment(war) > baseline + 30.0)
    }

    private fun testArg(): MCTSArg = MCTSArg(
        endMillisTime = Long.MAX_VALUE,
        turnCount = 1,
        turnFactor = 0.5,
        countPerTurn = 1,
        scoreCalculator = { 0.0 },
        enableMultiThread = false,
        debugName = "Pirate Warrior golden test",
        decisionModel = PirateWarriorMctsModel,
        experimentalSearch = true,
    )

    private fun testWar(turn: Int, mana: Int): War {
        val war = War()
        val me = Player(playerId = "me", war = war)
        val rival = Player(playerId = "rival", war = war)
        war.me = me
        war.rival = rival
        war.player1 = me
        war.player2 = rival
        war.currentPlayer = me
        war.isMyTurn = true
        me.turn = turn
        me.resources = mana
        return war
    }

    private fun testCard(cardId: String, cost: Int, attack: Int = 2): Card = Card(TestCardAction()).apply {
        entityId = "$cardId-test"
        this.cardId = cardId
        entityName = cardId
        cardType = CardTypeEnum.MINION
        cardRace = CardRaceEnum.PIRATE
        this.cost = cost
        atc = attack
        health = 3
        action.belongCard = this
    }

    private fun testWeapon(cardId: String, attack: Int, durability: Int): Card =
        testCard(cardId, cost = 4, attack = attack).apply {
            cardType = CardTypeEnum.WEAPON
            cardRace = CardRaceEnum.UNKNOWN
            this.durability = durability
        }

    private fun testHeroPower(): Card = testCard("HERO_01bp", cost = 2).apply {
        cardType = CardTypeEnum.HERO_POWER
        cardRace = CardRaceEnum.UNKNOWN
        isExhausted = false
    }

    private fun frontlineAxeWar(
        heroHealth: Int,
        rivalHeroHealth: Int,
        minionHealth: Int,
        minionCount: Int = 1,
    ): War {
        val war = testWar(turn = 2, mana = 4)
        war.addCard(testHero("MY_HERO", heroHealth, attack = 3), war.me.playArea)
        war.addCard(testWeapon(PirateWarriorMctsModel.FRONTLINE_AXE, attack = 3, durability = 3), war.me.playArea)
        war.addCard(testHero("RIVAL_HERO", rivalHeroHealth), war.rival.playArea)
        repeat(minionCount) { index ->
            war.addCard(testCard("RIVAL_MINION_$index", 1, attack = 1).apply {
                cardRace = CardRaceEnum.UNKNOWN
                health = minionHealth
            }, war.rival.playArea)
        }
        return war
    }

    private fun heroAttackActions(war: War): List<club.xiaojiawei.hsscriptcardsdk.bean.AttackAction> =
        war.me.playArea.hero?.action?.generateAttackActions(war, war.me).orEmpty()

    private fun hitsRivalMinion(action: club.xiaojiawei.hsscriptcardsdk.bean.AttackAction, war: War): Boolean {
        val after = simulate(action, war)
        val beforeCards = war.rival.playArea.cards.associateBy { it.entityId }
        return beforeCards.any { (entityId, beforeCard) ->
            val afterCard = after.rival.playArea.cards.associateBy { it.entityId }[entityId]
            beforeCard.isAlive() && (
                afterCard == null ||
                    !afterCard.isAlive() ||
                    afterCard.damage != beforeCard.damage ||
                    afterCard.isDivineShield != beforeCard.isDivineShield
                )
        }
    }

    private fun simulate(action: club.xiaojiawei.hsscriptcardsdk.bean.AttackAction, war: War): War =
        war.clone().also {
            PirateWarriorMctsModel.beforeSimulatedAction(it, action)
            action.simulate.accept(it)
        }

    private fun testHero(cardId: String, health: Int, attack: Int = 0): Card =
        testCard(cardId, cost = 0, attack = attack).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            this.health = health
        }
}
