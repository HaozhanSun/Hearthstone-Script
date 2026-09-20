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
import club.xiaojiawei.hsscriptcardsdk.cardparser.ParsedCardActionFactory
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MonteCarloTreeNode
import club.xiaojiawei.hsscriptcardsdk.mcts.MonteCarloTreeSearch
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsActionOrderPhase
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsTurnPhaseFence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PirateWarriorMctsModelTest {
    @Test
    fun `battlefield is downranked until warrior has two friendly minions`() {
        val emptyBoard = testWar(turn = 2, mana = 3)
        val battlefield = testCard(PirateWarriorMctsModel.BATTLEFIELD, cost = 3)
        assertFalse(
            PirateWarriorMctsModel.isActionLegal(
                PlayAction({}, {}, battlefield),
                emptyBoard,
            ),
        )
        val emptyPrior = PirateWarriorMctsModel.actionPrior(
            PlayAction({}, {}, battlefield),
            emptyBoard,
        )
        assertTrue(emptyPrior < 0.0)

        val oneMinionBoard = testWar(turn = 2, mana = 3).apply {
            addCard(testCard("FRIENDLY_MINION", cost = 0), me.playArea)
        }
        val oneMinionPrior = PirateWarriorMctsModel.actionPrior(
            PlayAction({}, {}, testCard(PirateWarriorMctsModel.BATTLEFIELD, cost = 3)),
            oneMinionBoard,
        )
        assertTrue(oneMinionPrior < 0.0)

        val establishedBoard = testWar(turn = 2, mana = 3).apply {
            addCard(testCard("FRIENDLY_MINION_1", cost = 0), me.playArea)
            addCard(testCard("FRIENDLY_MINION_2", cost = 0), me.playArea)
        }
        val establishedPrior = PirateWarriorMctsModel.actionPrior(
            PlayAction({}, {}, testCard(PirateWarriorMctsModel.BATTLEFIELD, cost = 3)),
            establishedBoard,
        )
        assertTrue(establishedPrior > 0.0)
    }

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
    fun `warrior hero cannot attack a seven-health minion with only four attack`() {
        val war = testWar(turn = 2, mana = 3)
        val hero = testCard("WARRIOR_FOUR_ATTACK_HERO", cost = 0, attack = 4).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("WARRIOR_FOUR_ATTACK_RIVAL_HERO", cost = 0, attack = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 20
        }
        val killable = testCard("WARRIOR_KILLABLE_THREAT", cost = 0, attack = 2).apply { health = 4 }
        val tooHealthy = testCard("WARRIOR_SEVEN_HEALTH_THREAT", cost = 0, attack = 7).apply { health = 7 }
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(killable, war.rival.playArea)
        war.addCard(tooHealthy, war.rival.playArea)

        val killableAttack = AttackAction({}, {}, hero, targetEntityId = killable.entityId)
        val tooHealthyAttack = AttackAction({}, {}, hero, targetEntityId = tooHealthy.entityId)
        val face = AttackAction({}, {}, hero, targetEntityId = rivalHero.entityId, targetIsHero = true)

        assertTrue(PirateWarriorMctsModel.isActionLegal(killableAttack, war))
        assertTrue(!PirateWarriorMctsModel.isActionLegal(tooHealthyAttack, war))
        assertTrue(!PirateWarriorMctsModel.isActionLegal(face, war))
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
    fun `nu ling naga is downranked until a friendly minion can attack`() {
        val war = testWar(turn = 2, mana = 3)
        val rivalHero = testCard("NAGA_PLAY_RIVAL_HERO", cost = 0, attack = 0).apply {
            cardType = CardTypeEnum.HERO
            health = 20
        }
        val naga = testCard(PirateHeroAttackTargetPolicy.NU_LING_NAGA, cost = 3).apply {
            cardType = CardTypeEnum.MINION
        }
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(naga, war.me.handArea)

        val nagaAction = PlayAction({}, {}, naga)
        val noAttackerPrior = PirateWarriorMctsModel.actionPrior(nagaAction, war)

        val attacker = testCard("NAGA_PLAY_ATTACKER", cost = 0, attack = 2).apply {
            cardType = CardTypeEnum.MINION
            cardRace = CardRaceEnum.PIRATE
            health = 2
            isExhausted = false
        }
        war.addCard(attacker, war.me.playArea)
        val attackerAvailablePrior = PirateWarriorMctsModel.actionPrior(nagaAction, war)

        assertTrue(noAttackerPrior < attackerAvailablePrior)
        assertTrue(noAttackerPrior < 0.0)
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
    fun `hero attack prefers the healthier equal-attack killable minion over a ready one`() {
        val war = testWar(turn = 6, mana = 1)
        val hero = testCard("WEAPON_TRADE_HERO", cost = 0, attack = 3).apply {
            cardType = CardTypeEnum.HERO
            health = 30
            isExhausted = false
        }
        val weapon = testCard("TLC_833", cost = 0, attack = 2).apply {
            cardType = CardTypeEnum.WEAPON
            health = 2
        }
        val rivalHero = testCard("WEAPON_TRADE_RIVAL_HERO", cost = 0, attack = 0).apply {
            cardType = CardTypeEnum.HERO
            health = 20
        }
        val healthier = testCard("REV_246", cost = 0, attack = 2).apply {
            health = 3
            isExhausted = true
        }
        val ready = testCard("TLC_903t", cost = 0, attack = 2).apply {
            health = 1
            isExhausted = false
        }
        war.addCard(hero, war.me.playArea)
        war.me.playArea.weapon = weapon
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(healthier, war.rival.playArea)
        war.addCard(ready, war.rival.playArea)

        val healthierAttack = AttackAction({}, {}, hero, targetEntityId = healthier.entityId)
        val readyAttack = AttackAction({}, {}, hero, targetEntityId = ready.entityId)

        assertTrue(PirateWarriorMctsModel.isActionLegal(healthierAttack, war))
        assertFalse(PirateWarriorMctsModel.isActionLegal(readyAttack, war))
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
    fun `fresh combat replan forces a direct minion kill before face`() {
        val war = testWar(turn = 4, mana = 0)
        val rivalHero = testCard("DIRECT_KILL_RIVAL_HERO", cost = 0, attack = 0).apply {
            cardType = CardTypeEnum.HERO
            health = 30
        }
        val damagedThreat = testCard("DAMAGED_FIVE_ATTACK_THREAT", cost = 0, attack = 5).apply {
            health = 4
            damage = 2
            isExhausted = true
        }
        val readyPirate = testCard("READY_PIRATE", cost = 0, attack = 2).apply {
            cardType = CardTypeEnum.MINION
            cardRace = CardRaceEnum.PIRATE
            health = 2
            isExhausted = false
        }
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(damagedThreat, war.rival.playArea)
        war.addCard(readyPirate, war.me.playArea)

        val kill = AttackAction({}, {}, readyPirate, targetEntityId = damagedThreat.entityId)
        val face = AttackAction({}, {}, readyPirate, targetEntityId = rivalHero.entityId, targetIsHero = true)

        assertTrue(PirateWarriorMctsModel.isActionLegal(kill, war))
        assertTrue(PirateWarriorMctsModel.isMandatoryAction(kill, war))
        assertFalse(PirateWarriorMctsModel.isMandatoryAction(face, war))
        assertTrue(
            PirateAttackOrderPolicy.isDirectFriendlyMinionKillAction(
                kill,
                war,
                PirateWarriorMctsModel::effectivePirateAttack,
            ),
        )
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
        assertTrue(!PirateWarriorMctsModel.isActionLegal(tauntAttack, war))
    }

    @Test
    fun `released pirate warrior strategy exposes a versioned display name`() {
        assertTrue(
            HsPirateWarriorMctsDeckStrategy().name()
                .startsWith("海盗战 V${PirateMctsStrategyVersion.REVISION} · build "),
        )
    }

    @Test
    fun `applause is a midgame draw breakpoint and not an opening hard rule`() {
        val war = testWar(turn = 3, mana = 2)
        war.addCard(typedMinion("BEAST_ON_BOARD", CardRaceEnum.PET), war.me.playArea)
        war.addCard(typedMinion("PIRATE_ON_BOARD", CardRaceEnum.PIRATE), war.me.playArea)
        val applause = spellCard(PirateWarriorMctsModel.APPLAUSE, cost = 2)
        val action = PlayAction({}, {}, applause)

        val valuation = PirateWarriorMctsModel.applauseDrawValuation(action, war)
        assertEquals(2, valuation.currentTypes.size)
        assertEquals(3, valuation.drawCount)
        assertTrue(PirateWarriorMctsModel.actionPrior(action, war) >= 34.0)
        assertEquals(
            18.0,
            PirateWarriorMctsModel.afterSimulatedAction(war, war.clone(), action).expectedReward,
        )
        assertFalse(PirateWarriorMctsModel.isMandatoryAction(action, war))

        war.addCard(typedMinion("DRAGON_ON_BOARD", CardRaceEnum.DRAGON), war.me.playArea)
        assertEquals(4, PirateWarriorMctsModel.applauseDrawValuation(action, war).drawCount)
        assertTrue(PirateWarriorMctsModel.actionPrior(action, war) >= 42.0)
    }

    @Test
    fun `mulligan removes applause and patches while retaining ordinary low-cost cards`() {
        val strategy = HsPirateWarriorMctsDeckStrategy()
        val applause = spellCard(PirateWarriorMctsModel.APPLAUSE, cost = 2)
        val patches = testCard(PirateWarriorMctsModel.PATCHES_THE_PIRATE, cost = 1)
        val keep = testCard("KEEP_ONE_COST_PIRATE", cost = 1)
        val cards = hashSetOf(applause, patches, keep)

        strategy.executeChangeCard(cards)

        assertFalse(cards.contains(applause))
        assertFalse(cards.contains(patches))
        assertTrue(cards.contains(keep))
    }

    @Test
    fun `going second original hand forces coin then ships cannon`() {
        val war = testWar(turn = 1, mana = 1)
        val cannon = testCard(PirateWarriorMctsModel.SHIPS_CANNON, cost = 2)
        val pirate = testCard("OPENING_ONE_COST_PIRATE", cost = 1)
        val coin = testCard("COIN", cost = 0).apply { isCoinCard = true }
        war.addCard(cannon, war.me.handArea)
        war.addCard(pirate, war.me.handArea)
        war.addCard(coin, war.me.handArea)
        PirateWarriorMctsModel.registerOpeningHandSnapshot(war, listOf(cannon, pirate))

        val coinAction = PlayAction({}, {}, coin)
        val cannonAction = PlayAction({}, {}, cannon)
        assertEquals(
            PirateWarriorMctsModel.OpeningCannonCoinStep.PLAY_COIN,
            PirateWarriorMctsModel.openingCannonCoinStep(war),
        )
        assertTrue(PirateWarriorMctsModel.isMandatoryAction(coinAction, war))
        assertFalse(PirateWarriorMctsModel.isMandatoryAction(cannonAction, war))

        war.me.handArea.removeByEntityId(coin.entityId)
        war.me.tempResources = 1
        assertEquals(
            PirateWarriorMctsModel.OpeningCannonCoinStep.PLAY_CANNON,
            PirateWarriorMctsModel.openingCannonCoinStep(war),
        )
        assertTrue(PirateWarriorMctsModel.isMandatoryAction(cannonAction, war))
        PirateWarriorMctsModel.clearOpeningHandSnapshot(war)
    }

    @Test
    fun `opening coin exception fails closed without original one-cost pirate`() {
        val war = testWar(turn = 1, mana = 1)
        val cannon = testCard(PirateWarriorMctsModel.SHIPS_CANNON, cost = 2)
        val coin = testCard("COIN", cost = 0).apply { isCoinCard = true }
        war.addCard(cannon, war.me.handArea)
        war.addCard(coin, war.me.handArea)
        PirateWarriorMctsModel.registerOpeningHandSnapshot(war, listOf(cannon))

        assertEquals(
            PirateWarriorMctsModel.OpeningCannonCoinStep.NONE,
            PirateWarriorMctsModel.openingCannonCoinStep(war),
        )
        PirateWarriorMctsModel.clearOpeningHandSnapshot(war)
    }

    @Test
    fun `quest reward is recognized and must be played before hero power`() {
        val war = testWar(turn = 9, mana = 6)
        val reward = testCard(PirateWarriorMctsModel.QUEST_REWARD, cost = 5).apply {
            entityId = "157"
            entityName = "船长洛卡拉"
            cardRace = CardRaceEnum.UNKNOWN
        }
        val power = testHeroPower()
        war.addCard(reward, war.me.handArea)
        war.addCard(power, war.me.playArea)

        val rewardAction = PlayAction({}, {}, reward)
        val powerAction = PowerAction({}, {}, power)

        assertTrue(PirateWarriorMctsModel.isQuestReward(reward))
        assertTrue(PirateWarriorMctsModel.canCreateOpaqueAction(reward, war))
        assertTrue(PirateWarriorMctsModel.isMandatoryAction(rewardAction, war))
        assertTrue(!PirateWarriorMctsModel.isMandatoryAction(powerAction, war))
        assertEquals(5_000L, PirateWarriorMctsModel.preDispatchWaitMillis(rewardAction, war))
        assertTrue(PirateWarriorMctsModel.shouldRetryAfterUnconfirmedDispatch(rewardAction, war, 0))
        assertTrue(!PirateWarriorMctsModel.shouldRetryAfterUnconfirmedDispatch(rewardAction, war, 1))
    }

    @Test
    fun `zero-cost aredar brute is mandatory before quest reward or other cards`() {
        val war = testWar(turn = 9, mana = 5)
        val rivalHero = testCard("GDB_FULL_BOARD_RIVAL_HERO", cost = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
        }
        war.addCard(rivalHero, war.rival.playArea)
        repeat(7) { index ->
            war.addCard(
                testCard("GDB_FULL_BOARD_MINION_$index", cost = 0).apply {
                    entityId = "GDB_FULL_BOARD_MINION_$index-entity"
                    health = 1
                    isExhausted = true
                },
                war.rival.playArea,
            )
        }
        val brute = testCard("GDB_320", cost = 0).apply {
            entityName = "艾瑞达蛮兵"
            cardType = CardTypeEnum.MINION
        }
        val reward = testCard(PirateWarriorMctsModel.QUEST_REWARD, cost = 5).apply {
            entityName = "船长洛卡拉"
        }
        war.addCard(brute, war.me.handArea)
        war.addCard(reward, war.me.handArea)

        val bruteAction = PlayAction({}, {}, brute)
        val rewardAction = PlayAction({}, {}, reward)
        assertTrue(PirateWarriorMctsModel.isMandatoryAction(bruteAction, war))
        assertTrue(!PirateWarriorMctsModel.isMandatoryAction(rewardAction, war))
        assertEquals(1_000.0, PirateWarriorMctsModel.actionPrior(bruteAction, war))
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
    fun `first turn quest is mandatory before cannon or distributor`() {
        val war = testWar(turn = 1, mana = 2)
        val quest = testCard(PirateWarriorMctsModel.QUESTLINE, cost = 1).apply {
            cardType = CardTypeEnum.SPELL
        }
        val cannon = testCard(PirateWarriorMctsModel.SHIPS_CANNON, cost = 2)
        val distributor = testCard(PirateWarriorMctsModel.TREASURE_DISTRIBUTOR, cost = 1)
        war.addCard(quest, war.me.handArea)
        war.addCard(cannon, war.me.handArea)
        war.addCard(distributor, war.me.handArea)

        assertTrue(PirateWarriorMctsModel.isMandatoryAction(PlayAction({}, {}, quest), war))
        assertTrue(!PirateWarriorMctsModel.isMandatoryAction(PlayAction({}, {}, cannon), war))
        assertTrue(!PirateWarriorMctsModel.isMandatoryAction(PlayAction({}, {}, distributor), war))
    }

    @Test
    fun `first turn opaque quest survives phase fence`() {
        val war = testWar(turn = 1, mana = 2)
        val quest = testCard(PirateWarriorMctsModel.QUESTLINE, cost = 1).apply {
            cardType = CardTypeEnum.SPELL
        }
        val cannon = testCard(PirateWarriorMctsModel.SHIPS_CANNON, cost = 2)
        val weapon = testCard(PirateWarriorMctsModel.FRONTLINE_AXE, cost = 2).apply {
            cardType = CardTypeEnum.WEAPON
        }
        war.addCard(quest, war.me.handArea)
        war.addCard(cannon, war.me.handArea)
        war.addCard(weapon, war.me.handArea)

        assertEquals(
            MctsActionOrderPhase.MINION_PLAY,
            PirateWarriorMctsModel.actionOrderPhase(PlayAction({}, {}, quest), war),
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
        var minionAttackSteps = 0
        while (afterMinionAttacks.actions.all {
                it is AttackAction && it.creator?.cardType === CardTypeEnum.MINION
            }
        ) {
            assertTrue(afterMinionAttacks.actions.isNotEmpty())
            afterMinionAttacks = afterMinionAttacks.buildNextNode(afterMinionAttacks.actions.first())
            minionAttackSteps++
            assertTrue(minionAttackSteps <= 7)
        }
        assertTrue(afterMinionAttacks.actions.isNotEmpty())
        if (!afterMinionAttacks.actions.all {
                it is PowerAction && it.creator?.cardType === CardTypeEnum.HERO_POWER
            }
        ) {
            throw AssertionError(
                afterMinionAttacks.actions.joinToString { "${it::class.simpleName}:${it.creator?.cardType}:${it.creator?.cardId}" },
            )
        }
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
    fun `ragewing is deferred behind another playable action but remains a fallback`() {
        val war = testWar(turn = 2, mana = 4)
        val ragewing = testCard(PirateWarriorMctsModel.RAGEWING, cost = 4)
        val cannon = testCard(PirateWarriorMctsModel.SHIPS_CANNON, cost = 2)
        war.addCard(ragewing, war.me.handArea)
        war.addCard(cannon, war.me.handArea)

        val ragewingAction = PlayAction({}, {}, ragewing)
        assertTrue(PirateWarriorMctsModel.isDeferredAction(ragewingAction, war))
        assertEquals(
            "ragewing-deferred-behind-other-action",
            PirateWarriorMctsModel.actionFilterReason(ragewingAction, war),
        )

        val onlyRagewing = testWar(turn = 2, mana = 4)
        val onlyCard = testCard(PirateWarriorMctsModel.RAGEWING, cost = 4)
        onlyRagewing.addCard(onlyCard, onlyRagewing.me.handArea)
        assertTrue(
            !PirateWarriorMctsModel.isDeferredAction(
                PlayAction({}, {}, onlyCard),
                onlyRagewing,
            ),
        )
    }

    @Test
    fun `ragewing is deferred during hand scan so later attacks are not hidden by phase fence`() {
        val war = testWar(turn = 2, mana = 4)
        val ragewing = testCard(PirateWarriorMctsModel.RAGEWING, cost = 4)
        val cannon = testCard(PirateWarriorMctsModel.SHIPS_CANNON, cost = 2)
        war.addCard(ragewing, war.me.handArea)
        war.addCard(cannon, war.me.handArea)

        assertTrue(PirateWarriorMctsModel.shouldDefer(ragewing, war))
    }

    @Test
    fun `ragewing stays last when hook n heave is recognized by built-in action`() {
        ParsedCardActionFactory.clear()
        val war = testWar(turn = 2, mana = 4)
        val ragewing = testCard(PirateWarriorMctsModel.RAGEWING, cost = 2)
        val hookNHeave = testCard(PirateWarriorMctsModel.HOOK_N_HEAVE, cost = 2).apply {
            cardType = CardTypeEnum.SPELL
            val parsedAction = requireNotNull(
                ParsedCardActionFactory.getOrCreate(cardId, "钩手拖拽")
            )()
            parsedAction.belongCard = this
            action = parsedAction
        }
        war.addCard(ragewing, war.me.handArea)
        war.addCard(hookNHeave, war.me.handArea)

        val ragewingAction = PlayAction({}, {}, ragewing)
        assertTrue(PirateWarriorMctsModel.isDeferredAction(ragewingAction, war))
        assertEquals(
            "ragewing-deferred-behind-other-action",
            PirateWarriorMctsModel.actionFilterReason(ragewingAction, war),
        )
        assertTrue(hookNHeave.action.generatePlayActions(war, war.me).isNotEmpty())
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
    fun `equipped weapon forces usable hero power before hero attack`() {
        val war = testWar(turn = 2, mana = 2)
        val hero = testCard("WEAPON_ORDER_HERO", cost = 0, attack = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("WEAPON_ORDER_RIVAL_HERO", cost = 0, attack = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
        }
        val weapon = testCard("WEAPON_ORDER_EQUIPPED", cost = 0, attack = 3).apply {
            cardType = CardTypeEnum.WEAPON
            cardRace = CardRaceEnum.UNKNOWN
            durability = 2
        }
        val power = testCard("WEAPON_ORDER_POWER", cost = 2, attack = 0).apply {
            cardType = CardTypeEnum.HERO_POWER
            cardRace = CardRaceEnum.UNKNOWN
            entityId = "WEAPON_ORDER_POWER-test"
            isLaunchpad = true
            isExhausted = false
            child += testCard("GDB_905", cost = 2, attack = 0)
        }
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.me.playArea.weapon = weapon
        war.addCard(power, war.me.playArea)

        assertTrue(PirateAttackOrderPolicy.shouldUseHeroPowerBeforeWeaponAttack(war))
        assertEquals(
            MctsActionOrderPhase.HERO_POWER,
            PirateWarriorMctsModel.actionOrderPhase(PowerAction({}, {}, power), war),
        )
        assertEquals(
            MctsActionOrderPhase.HERO_ATTACK,
            PirateWarriorMctsModel.actionOrderPhase(AttackAction({}, {}, hero), war),
        )
    }

    @Test
    fun `taunt exception spends hero power before hero attack then exposes minion attacks`() {
        // TestCardAction models a hero power as a launchpad and therefore
        // falls back to the SDK's five-resource launch cost when no child
        // launch card is attached.
        val war = testWar(turn = 3, mana = 5)
        val hero = testCard("TAUNT_EXCEPTION_HERO", cost = 0, attack = 1).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("TAUNT_EXCEPTION_RIVAL_HERO", cost = 0).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
            atc = 0
        }
        val taunt = testCard("TAUNT_EXCEPTION_TAUNT", cost = 0).apply {
            isTaunt = true
            atc = 0
            health = 5
        }
        val minion = testCard("TAUNT_EXCEPTION_MINION", cost = 0).apply { isExhausted = false }
        val heroPower = testCard("TAUNT_EXCEPTION_POWER", cost = 1).apply {
            cardType = CardTypeEnum.HERO_POWER
            cardRace = CardRaceEnum.UNKNOWN
            isLaunchpad = true
            isExhausted = false
        }
        war.addCard(hero, war.me.playArea)
        war.addCard(minion, war.me.playArea)
        war.addCard(heroPower, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(taunt, war.rival.playArea)

        val root = MonteCarloTreeNode(war, InitAction, testMctsArg())
        assertTrue(root.actions.isNotEmpty())
        assertTrue(root.actions.all { it is PowerAction && it.creator?.entityId == heroPower.entityId })

        val afterPower = root.buildNextNode(root.actions.single())
        assertTrue(afterPower.actions.isNotEmpty())
        assertTrue(afterPower.actions.none {
            it is AttackAction && it.creator?.entityId == hero.entityId
        })
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

        assertTrue(node.actions.none { it is AttackAction && it.targetEntityId == taunt.entityId })
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

    private fun testHeroPower(): Card = testCard("HERO_01bp", 2).apply {
        cardType = CardTypeEnum.HERO_POWER
        cardRace = CardRaceEnum.UNKNOWN
        isExhausted = false
    }

    private fun typedMinion(cardId: String, race: CardRaceEnum, cost: Int = 0): Card =
        testCard(cardId, cost).apply { cardRace = race }

    private fun spellCard(cardId: String, cost: Int): Card =
        testCard(cardId, cost).apply {
            cardType = CardTypeEnum.SPELL
            cardRace = CardRaceEnum.UNKNOWN
        }
}
