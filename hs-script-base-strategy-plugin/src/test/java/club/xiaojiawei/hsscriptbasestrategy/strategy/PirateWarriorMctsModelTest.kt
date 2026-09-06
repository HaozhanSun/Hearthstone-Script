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
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsTurnPhaseFence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PirateWarriorMctsModelTest {
    @Test
    fun `hero attack allows lethal face but otherwise targets a nonlethal minion`() {
        val war = testWar(turn = 2, mana = 3)
        val hero = testCard("WARRIOR_HERO", cost = 0, attack = 3).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("WARRIOR_RIVAL_HERO", cost = 0, attack = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 5
            armor = 0
        }
        val largeMinion = testCard("LARGE_MINION", cost = 0, attack = 4).apply { health = 8 }
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(largeMinion, war.rival.playArea)

        val face = AttackAction({}, {}, hero, targetEntityId = rivalHero.entityId, targetIsHero = true)
        assertTrue(PirateWarriorMctsModel.isActionLegal(face, war))
        val minionAttack = AttackAction({}, {}, hero, targetEntityId = largeMinion.entityId)
        assertTrue(!PirateWarriorMctsModel.isActionLegal(minionAttack, war))

        rivalHero.health = 3
        assertTrue(PirateWarriorMctsModel.isActionLegal(face, war))
    }

    @Test
    fun `nu ling naga makes other minions prefer enemy minions over nonlethal face`() {
        val war = testWar(turn = 2, mana = 3)
        val rivalHero = testCard("NAGA_RIVAL_HERO", cost = 0, attack = 0).apply {
            cardType = CardTypeEnum.HERO
            health = 20
        }
        val naga = testCard(PirateHeroAttackTargetPolicy.NU_LING_NAGA, cost = 3).apply {
            cardType = CardTypeEnum.MINION
            health = 3
            isExhausted = true
        }
        val attacker = testCard("NAGA_ATTACKER", cost = 0, attack = 3).apply {
            cardType = CardTypeEnum.MINION
            cardRace = CardRaceEnum.PIRATE
            health = 3
            isExhausted = false
        }
        val rivalMinion = testCard("NAGA_RIVAL_MINION", cost = 0, attack = 2).apply { health = 4 }
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(naga, war.me.playArea)
        war.addCard(attacker, war.me.playArea)
        war.addCard(rivalMinion, war.rival.playArea)

        val minionAttack = AttackAction({}, {}, attacker, targetEntityId = rivalMinion.entityId)
        val faceAttack = AttackAction({}, {}, attacker, targetEntityId = rivalHero.entityId, targetIsHero = true)

        assertTrue(
            PirateWarriorMctsModel.actionPrior(minionAttack, war) >
                PirateWarriorMctsModel.actionPrior(faceAttack, war),
        )
    }

    @Test
    fun `hero attack chooses the highest threat among killable minions`() {
        val war = testWar(turn = 2, mana = 3)
        val hero = testCard("THREAT_HERO", cost = 0, attack = 3).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("THREAT_RIVAL_HERO", cost = 0, attack = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 20
        }
        val lowThreat = testCard("LOW_THREAT", cost = 0, attack = 1).apply { health = 3 }
        val highThreat = testCard("HIGH_THREAT", cost = 0, attack = 6).apply { health = 3 }
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(lowThreat, war.rival.playArea)
        war.addCard(highThreat, war.rival.playArea)

        val face = AttackAction({}, {}, hero, targetEntityId = rivalHero.entityId, targetIsHero = true)
        val lowThreatAttack = AttackAction({}, {}, hero, targetEntityId = lowThreat.entityId)
        val highThreatAttack = AttackAction({}, {}, hero, targetEntityId = highThreat.entityId)

        assertTrue(!PirateWarriorMctsModel.isActionLegal(face, war))
        assertTrue(!PirateWarriorMctsModel.isActionLegal(lowThreatAttack, war))
        assertTrue(PirateWarriorMctsModel.isActionLegal(highThreatAttack, war))
    }

    @Test
    fun `hero may attack face when no enemy minion is killable`() {
        val war = testWar(turn = 2, mana = 3)
        val hero = testCard("FACE_HERO", cost = 0, attack = 3).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("FACE_RIVAL_HERO", cost = 0, attack = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 20
        }
        val largeMinion = testCard("FACE_LARGE_MINION", cost = 0, attack = 4).apply { health = 6 }
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(largeMinion, war.rival.playArea)

        val face = AttackAction({}, {}, hero, targetEntityId = rivalHero.entityId, targetIsHero = true)
        val minionAttack = AttackAction({}, {}, hero, targetEntityId = largeMinion.entityId)

        assertTrue(PirateWarriorMctsModel.isActionLegal(face, war))
        assertTrue(!PirateWarriorMctsModel.isActionLegal(minionAttack, war))
    }

    @Test
    fun `friendly setup attack is required for a combined hero kill`() {
        val war = testWar(turn = 2, mana = 3)
        val hero = testCard("COMBO_HERO", cost = 0, attack = 3).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("COMBO_RIVAL_HERO", cost = 0, attack = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 20
        }
        val rivalMinion = testCard("COMBO_RIVAL_MINION", cost = 0, attack = 5).apply { health = 5 }
        val setupMinion = testCard("COMBO_SETUP_MINION", cost = 0, attack = 2).apply {
            health = 2
            isExhausted = false
        }
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(rivalMinion, war.rival.playArea)
        war.addCard(setupMinion, war.me.playArea)

        val face = AttackAction({}, {}, hero, targetEntityId = rivalHero.entityId, targetIsHero = true)
        val heroAttack = AttackAction({}, {}, hero, targetEntityId = rivalMinion.entityId)
        val setupAttack = setupMinion.action.generateAttackActions(war, war.me)
            .first { it.targetEntityId == rivalMinion.entityId }

        assertTrue(!PirateWarriorMctsModel.isActionLegal(face, war))
        assertTrue(PirateWarriorMctsModel.isActionLegal(heroAttack, war))
        assertTrue(PirateHeroAttackTargetPolicy.requiresFriendlySetupAttack(war))
        assertTrue(PirateHeroAttackTargetPolicy.isRequiredFriendlySetupAttack(setupAttack, war))
        assertTrue(PirateWarriorMctsModel.isMandatoryAction(setupAttack, war))
    }

    @Test
    fun `hero attack respects a taunt even when it cannot be killed`() {
        val war = testWar(turn = 2, mana = 3)
        val hero = testCard("TAUNT_HERO", cost = 0, attack = 3).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("TAUNT_RIVAL_HERO", cost = 0, attack = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 20
        }
        val taunt = testCard("TAUNT", cost = 0, attack = 4).apply {
            health = 8
            isTaunt = true
        }
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(taunt, war.rival.playArea)

        val face = AttackAction({}, {}, hero, targetEntityId = rivalHero.entityId, targetIsHero = true)
        val tauntAttack = AttackAction({}, {}, hero, targetEntityId = taunt.entityId)
        assertTrue(!PirateWarriorMctsModel.isActionLegal(face, war))
        assertTrue(PirateWarriorMctsModel.isActionLegal(tauntAttack, war))
    }

    @Test
    fun `released pirate warrior strategy exposes a versioned display name`() {
        assertEquals("海盗战 V1.0", HsPirateWarriorMctsDeckStrategy().name())
    }
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
        war.addCard(testCard(PirateAttackOrderPolicy.ADRENALINE_FIEND, cost = 2), war.me.playArea)
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

        var afterMinionAttacks = afterMinionPlay
        repeat(afterMinionPlay.actions.size) {
            assertTrue(afterMinionAttacks.actions.isNotEmpty())
            assertTrue(afterMinionAttacks.actions.all {
                it is AttackAction && it.creator?.cardType === CardTypeEnum.MINION
            })
            afterMinionAttacks = afterMinionAttacks.buildNextNode(afterMinionAttacks.actions.first())
        }
        assertTrue(afterMinionAttacks.actions.isNotEmpty())
        assertTrue(afterMinionAttacks.actions.all {
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
        war.addCard(testCard(PirateAttackOrderPolicy.ADRENALINE_FIEND, cost = 2), war.me.playArea)
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

        val weapon = testCard("REV_509", cost = 0).apply {
            cardType = CardTypeEnum.WEAPON
        }
        assertEquals(
            MctsActionOrderPhase.MINION_PLAY,
            PirateWarriorMctsModel.actionOrderPhase(PlayAction({}, {}, weapon), war),
        )
        val fence = MctsTurnPhaseFence().apply {
            observe(MctsActionOrderPhase.HERO_ATTACK)
        }
        assertTrue(
            !fence.allows(
                PirateWarriorMctsModel.actionOrderPhase(PlayAction({}, {}, weapon), war),
                isEndTurn = false,
                endTurnLegal = true,
            ),
        )
    }

    @Test
    fun `without adrenaline fiend hero actions may precede minion attacks`() {
        val war = testWar(turn = 3, mana = 4)
        val hero = testCard("EARLY_HERO", cost = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
        }
        val power = testCard("EARLY_POWER", cost = 1).apply {
            cardType = CardTypeEnum.HERO_POWER
            cardRace = CardRaceEnum.UNKNOWN
        }
        val minion = testCard("EARLY_MINION", cost = 1)

        assertEquals(
            MctsActionOrderPhase.EARLY_HERO_ACTION,
            PirateWarriorMctsModel.actionOrderPhase(AttackAction({}, {}, hero), war),
        )
        assertEquals(
            MctsActionOrderPhase.EARLY_HERO_ACTION,
            PirateWarriorMctsModel.actionOrderPhase(PowerAction({}, {}, power), war),
        )
        assertEquals(
            MctsActionOrderPhase.MINION_ATTACK,
            PirateWarriorMctsModel.actionOrderPhase(AttackAction({}, {}, minion), war),
        )
        assertTrue(
            !PirateWarriorMctsModel.isDeferredAction(PowerAction({}, {}, power), war),
        )
    }

    @Test
    fun `parachute brigand is deferred behind another playable card even when free`() {
        val war = testWar(turn = 2, mana = 1)
        val brigand = testCard(PirateWarriorMctsModel.PARACHUTE_BRIGAND, cost = 0)
        val ordinary = testCard("ORDINARY_AFTER_BRIGAND", cost = 1)
        war.addCard(brigand, war.me.handArea)
        war.addCard(ordinary, war.me.handArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg())

        assertTrue(node.actions.any { it.creator?.cardId == ordinary.cardId })
        assertTrue(node.actions.none { it.creator?.cardId == brigand.cardId })
    }

    @Test
    fun `parachute brigand remains available as the only free playable action`() {
        val war = testWar(turn = 2, mana = 0)
        val brigand = testCard(PirateWarriorMctsModel.PARACHUTE_BRIGAND, cost = 0)
        war.addCard(brigand, war.me.handArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg())

        assertTrue(node.actions.any { it.creator?.cardId == brigand.cardId })
    }

    @Test
    fun `root lethal gate exposes face attacks before nonlethal minion trades`() {
        val war = testWar(turn = 2, mana = 0)
        val rivalHero = testCard("LETHAL_RIVAL_HERO", cost = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 4
            atc = 0
        }
        val rivalMinion = testCard("LETHAL_RIVAL_MINION", cost = 0).apply { health = 6; atc = 0 }
        val first = testCard("LETHAL_ATTACKER_ONE", cost = 0, attack = 2).apply { isExhausted = false }
        val second = testCard("LETHAL_ATTACKER_TWO", cost = 0, attack = 2).apply { isExhausted = false }
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(rivalMinion, war.rival.playArea)
        war.addCard(first, war.me.playArea)
        war.addCard(second, war.me.playArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg())

        assertTrue(node.actions.any { it is AttackAction && it.targetIsHero })
        assertTrue(node.actions.none { it is AttackAction && !it.targetIsHero })
    }

    @Test
    fun `taunt prevents the lethal gate from claiming face damage`() {
        val war = testWar(turn = 2, mana = 0)
        val rivalHero = testCard("TAUNT_RIVAL_HERO", cost = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 4
            atc = 0
        }
        val taunt = testCard("LETHAL_TAUNT", cost = 0).apply { health = 8; atc = 0; isTaunt = true }
        val attacker = testCard("TAUNT_ATTACKER", cost = 0, attack = 4).apply { isExhausted = false }
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(taunt, war.rival.playArea)
        war.addCard(attacker, war.me.playArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg())

        assertTrue(node.actions.any { it is AttackAction && it.targetEntityId == taunt.entityId })
        assertTrue(node.actions.none { it is AttackAction && it.targetIsHero })
    }

    @Test
    fun `weapon attack contributes to team lethal face route`() {
        val war = testWar(turn = 2, mana = 0)
        val rivalHero = testCard("WEAPON_RIVAL_HERO", cost = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 4
            atc = 0
        }
        val rivalMinion = testCard("WEAPON_RIVAL_MINION", cost = 0).apply { health = 6; atc = 0 }
        val attacker = testCard("WEAPON_ATTACKER", cost = 0, attack = 2).apply { isExhausted = false }
        val hero = testCard("WEAPON_HERO", cost = 0, attack = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
            isExhausted = false
        }
        val weapon = testCard("WEAPON_FOR_LETHAL", cost = 0, attack = 2).apply {
            cardType = CardTypeEnum.WEAPON
            health = 2
        }
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(rivalMinion, war.rival.playArea)
        war.addCard(attacker, war.me.playArea)
        war.addCard(hero, war.me.playArea)
        war.addCard(weapon, war.me.playArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg())

        assertTrue(node.actions.any { it is AttackAction && it.creator?.entityId == hero.entityId && it.targetIsHero })
        assertTrue(node.actions.none { it is AttackAction && !it.targetIsHero })
    }

    @Test
    fun `nonlethal total damage keeps normal minion target fallback`() {
        val war = testWar(turn = 2, mana = 0)
        val rivalHero = testCard("NONLETHAL_RIVAL_HERO", cost = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 8
            atc = 0
        }
        val rivalMinion = testCard("NONLETHAL_RIVAL_MINION", cost = 0).apply { health = 6; atc = 0 }
        val attacker = testCard("NONLETHAL_ATTACKER", cost = 0, attack = 2).apply { isExhausted = false }
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(rivalMinion, war.rival.playArea)
        war.addCard(attacker, war.me.playArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg())

        assertTrue(node.actions.any { it is AttackAction && it.targetEntityId == rivalMinion.entityId })
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
