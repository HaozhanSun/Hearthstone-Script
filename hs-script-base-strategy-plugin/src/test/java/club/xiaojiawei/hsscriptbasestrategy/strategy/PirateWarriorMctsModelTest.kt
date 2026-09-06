package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.InitAction
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.MctsRootSelectionPolicy
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MonteCarloTreeNode
import club.xiaojiawei.hsscriptcardsdk.mcts.MonteCarloTreeSearch
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsActionOrderPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PirateWarriorMctsModelTest {
    @Test
    fun `cannon is mandatory before treasure distributor`() {
        val war = testWar(turn = 1, mana = 2)
        val cannon = testCard(PirateWarriorMctsModel.SHIPS_CANNON, cost = 2)
        val distributor = testCard(PirateWarriorMctsModel.TREASURE_DISTRIBUTOR, cost = 1)
        war.addCard(cannon, war.me.handArea)
        war.addCard(distributor, war.me.handArea)

        val cannonAction = PlayAction({}, {}, cannon)
        val distributorAction = PlayAction({}, {}, distributor)
        assertTrue(PirateWarriorMctsModel.isMandatoryAction(cannonAction, war))
        assertTrue(!PirateWarriorMctsModel.isMandatoryAction(distributorAction, war))
    }

    @Test
    fun `first turn quest is mandatory when cannon is not playable`() {
        val war = testWar(turn = 1, mana = 1)
        val quest = testCard(PirateWarriorMctsModel.QUESTLINE, cost = 1)
        val distributor = testCard(PirateWarriorMctsModel.TREASURE_DISTRIBUTOR, cost = 1)
        war.addCard(quest, war.me.handArea)
        war.addCard(distributor, war.me.handArea)

        assertTrue(
            PirateWarriorMctsModel.isMandatoryAction(PlayAction({}, {}, quest), war),
        )
        assertTrue(
            !PirateWarriorMctsModel.isMandatoryAction(PlayAction({}, {}, distributor), war),
        )
    }

    @Test
    fun `pirates receive southsea reward without an attack-event hozen bonus`() {
        val war = testWar(turn = 2, mana = 4)
        val captain = testCard(PirateWarriorMctsModel.SOUTHSEA_CAPTAIN, cost = 3, attack = 3)
        val hozen = testCard(PirateWarriorMctsModel.HOZEN_ROUGHHOUSER, cost = 3, attack = 2)
        val attacker = testCard("PIRATE_ATTACKER", cost = 1, attack = 2)
        war.addCard(captain, war.me.playArea)
        war.addCard(hozen, war.me.playArea)
        war.addCard(attacker, war.me.playArea)

        assertEquals(3, PirateWarriorMctsModel.effectivePirateAttack(attacker, war))
    }

    @Test
    fun `patches has bottom prior`() {
        val war = testWar(turn = 2, mana = 1)
        val patches = testCard(PirateWarriorMctsModel.PATCHES_THE_PIRATE, cost = 1)
        val ordinary = testCard("ORDINARY_PIRATE", cost = 1)
        war.addCard(patches, war.me.handArea)
        war.addCard(ordinary, war.me.handArea)

        assertTrue(
            PirateWarriorMctsModel.actionPrior(
                PlayAction({}, {}, patches),
                war,
            ) < PirateWarriorMctsModel.actionPrior(PlayAction({}, {}, ordinary), war),
        )
    }

    @Test
    fun `root action generation exposes only playable cannon at p0`() {
        val war = testWar(turn = 1, mana = 2)
        val cannon = testCard(PirateWarriorMctsModel.SHIPS_CANNON, cost = 2)
        val distributor = testCard(PirateWarriorMctsModel.TREASURE_DISTRIBUTOR, cost = 1)
        war.addCard(cannon, war.me.handArea)
        war.addCard(distributor, war.me.handArea)

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
                decisionModel = PirateWarriorMctsModel,
                experimentalSearch = true,
            ),
        )

        assertTrue(node.actions.isNotEmpty())
        assertTrue(node.actions.all { it.creator?.let { card -> PirateWarriorMctsModel.isCard(card, PirateWarriorMctsModel.SHIPS_CANNON) } == true })
    }

    @Test
    fun `mcts root and next replan enforce minion play then minion attack then hero power`() {
        val war = testWar(turn = 3, mana = 10)
        val handMinion = testCard("HAND_MINION", cost = 1)
        val readyMinion = testCard("READY_MINION", cost = 1).apply { isExhausted = false }
        val hero = testCard("WARRIOR_HERO", cost = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 1
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("RIVAL_HERO", cost = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 30
        }
        val heroPower = testCard("HERO_POWER", cost = 1).apply {
            cardType = CardTypeEnum.HERO_POWER
            cardRace = CardRaceEnum.UNKNOWN
            isLaunchpad = true
            isExhausted = false
        }
        war.addCard(handMinion, war.me.handArea)
        war.addCard(readyMinion, war.me.playArea)
        war.addCard(hero, war.me.playArea)
        war.addCard(heroPower, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)

        val arg = testMctsArg()
        val root = MonteCarloTreeNode(war, InitAction, arg)
        assertTrue(root.actions.isNotEmpty())
        assertTrue(root.actions.all { it is PlayAction && it.creator?.cardType === CardTypeEnum.MINION })

        val selected = MonteCarloTreeSearch().searchBestNode(
            war,
            arg.copy(endMillisTime = System.currentTimeMillis() + 1_000L),
        )
        assertTrue(selected.isNotEmpty())
        assertTrue(selected.first().applyAction is PlayAction)
        assertEquals(handMinion.cardId, selected.first().applyAction.creator?.cardId)

        val afterMinionPlay = root.buildNextNode(root.actions.single())
        assertTrue(afterMinionPlay.actions.isNotEmpty())
        assertTrue(afterMinionPlay.actions.all {
            it is AttackAction && it.creator?.cardType === CardTypeEnum.MINION
        })

        val afterMinionAttack = afterMinionPlay.buildNextNode(afterMinionPlay.actions.single())
        assertTrue(afterMinionAttack.actions.isNotEmpty())
        assertTrue(afterMinionAttack.actions.all {
            it is PowerAction && it.creator?.cardType === CardTypeEnum.HERO_POWER
        })
    }

    @Test
    fun `released pirate warrior uses the global turn plan`() {
        val arg = HsPirateWarriorMctsDeckStrategy().executeMCTSOutCard(testWar(turn = 3, mana = 4)).single()

        assertTrue(arg.experimentalSearch)
        assertEquals(MctsRootSelectionPolicy.GLOBAL_TURN_PLAN, arg.rootSelectionPolicy)
        assertTrue(arg.experimentalActionBudgetMillis > 0)
    }

    @Test
    fun `global plan penalizes leaving reachable mana unused`() {
        val root = testWar(turn = 3, mana = 4)
        val fourCost = testCard("FOUR_COST_PLAY", cost = 4)
        root.addCard(fourCost, root.me.handArea)

        val oneManaUsed = root.clone().apply { me.usedResources = 1 }
        val allManaUsed = root.clone().apply { me.usedResources = 4 }

        assertTrue(
            PirateWarriorMctsModel.turnPlanAdjustment(root, allManaUsed, emptyList()) >
                PirateWarriorMctsModel.turnPlanAdjustment(root, oneManaUsed, emptyList()),
        )
        assertEquals(4, PirateWarriorMctsModel.maxSpendableMana(root))
    }

    @Test
    fun `global plan does not penalize an empty state with no legal spend`() {
        val root = testWar(turn = 3, mana = 4)
        val terminal = root.clone()

        assertEquals(0, PirateWarriorMctsModel.maxSpendableMana(root))
        assertEquals(
            0.0,
            PirateWarriorMctsModel.turnPlanAdjustment(root, terminal, emptyList()),
        )
    }

    @Test
    fun `pirate warrior action fence keeps spells between board development and attacks`() {
        val war = testWar(turn = 3, mana = 4)
        val minion = testCard("PHASE_MINION", cost = 1)
        val spell = testCard("PHASE_SPELL", cost = 1).apply {
            cardType = CardTypeEnum.SPELL
            cardRace = CardRaceEnum.UNKNOWN
        }
        val readyMinion = testCard("PHASE_READY_MINION", cost = 1).apply {
            isExhausted = false
        }
        val hero = testCard("PHASE_HERO", cost = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 1
            health = 30
            isExhausted = false
        }
        val power = testCard("PHASE_POWER", cost = 1).apply {
            cardType = CardTypeEnum.HERO_POWER
            cardRace = CardRaceEnum.UNKNOWN
            isLaunchpad = true
            isExhausted = false
        }
        war.addCard(minion, war.me.handArea)
        war.addCard(spell, war.me.handArea)
        war.addCard(readyMinion, war.me.playArea)
        war.addCard(hero, war.me.playArea)
        war.addCard(power, war.me.playArea)

        assertEquals(
            MctsActionOrderPhase.MINION_PLAY,
            PirateWarriorMctsModel.actionOrderPhase(PlayAction({}, {}, minion), war),
        )
        assertEquals(
            MctsActionOrderPhase.SPELL_PLAY,
            PirateWarriorMctsModel.actionOrderPhase(PlayAction({}, {}, spell), war),
        )
        assertEquals(
            MctsActionOrderPhase.MINION_ATTACK,
            PirateWarriorMctsModel.actionOrderPhase(AttackAction({}, {}, readyMinion), war),
        )
        assertEquals(
            MctsActionOrderPhase.HERO_POWER,
            PirateWarriorMctsModel.actionOrderPhase(PowerAction({}, {}, power), war),
        )
        assertEquals(
            MctsActionOrderPhase.HERO_ATTACK,
            PirateWarriorMctsModel.actionOrderPhase(AttackAction({}, {}, hero), war),
        )
    }

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

    private fun testMctsArg(): MCTSArg = MCTSArg(
        endMillisTime = Long.MAX_VALUE,
        turnCount = 1,
        turnFactor = 0.5,
        countPerTurn = 1,
        scoreCalculator = { 0.0 },
        enableMultiThread = false,
        decisionModel = PirateWarriorMctsModel,
        experimentalSearch = true,
    )

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
}
