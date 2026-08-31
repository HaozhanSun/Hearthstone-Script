package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.InitAction
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
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
    fun `southsea and hozen only add reward to another attacking pirate`() {
        val war = testWar(turn = 2, mana = 4)
        val captain = testCard(PirateWarriorMctsModel.SOUTHSEA_CAPTAIN, 3, 3)
        val hozen = testCard(PirateWarriorMctsModel.HOZEN_ROUGHHOUSER, 3, 2)
        val pirate = testCard("ATTACKING_PIRATE", 1, 2)
        war.addCard(captain, war.me.playArea)
        war.addCard(hozen, war.me.playArea)
        war.addCard(pirate, war.me.playArea)

        assertEquals(4, PirateWarriorMctsModel.effectivePirateAttack(pirate, war))
        assertEquals(4, PirateWarriorMctsModel.effectivePirateAttack(captain, war))
        assertEquals(3, PirateWarriorMctsModel.effectivePirateAttack(hozen, war))
    }

    @Test
    fun `combat aura is temporary during simulation and is cleaned up`() {
        val war = testWar(turn = 2, mana = 4)
        val captain = testCard(PirateWarriorMctsModel.SOUTHSEA_CAPTAIN, 3, 3)
        val hozen = testCard(PirateWarriorMctsModel.HOZEN_ROUGHHOUSER, 3, 2)
        val attacker = testCard("ATTACKING_PIRATE", 1, 2)
        war.addCard(captain, war.me.playArea)
        war.addCard(hozen, war.me.playArea)
        war.addCard(attacker, war.me.playArea)
        val attack = AttackAction({}, {}, attacker)

        PirateWarriorMctsModel.beforeSimulatedAction(war, attack)
        assertEquals(4, attacker.atc)
        PirateWarriorMctsModel.afterSimulatedAction(war, war, attack)
        assertEquals(2, attacker.atc)
        assertEquals(0, attacker.mctsTemporaryAttackBonus)
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

    private fun testHero(cardId: String, health: Int, attack: Int = 0): Card =
        testCard(cardId, cost = 0, attack = attack).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            this.health = health
        }
}
