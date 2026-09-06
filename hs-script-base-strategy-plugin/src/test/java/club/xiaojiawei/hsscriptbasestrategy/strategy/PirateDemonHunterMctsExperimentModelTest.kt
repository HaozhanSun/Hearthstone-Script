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
import club.xiaojiawei.hsscriptcardsdk.bean.TurnOverAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.CardTimingPolicy
import club.xiaojiawei.hsscriptcardsdk.mcts.MonteCarloTreeNode
import club.xiaojiawei.hsscriptcardsdk.mcts.MonteCarloTreeSearch
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsActionOrderPhase
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsTurnPhaseFence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PirateDemonHunterMctsExperimentModelTest {

    @Test
    fun `hero attack suppresses nonlethal face and keeps the first deterministic kill target`() {
        val war = testWar()
        val hero = testCard("DH_HERO").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            cost = 0
            atc = 3
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("DH_RIVAL_HERO").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            cost = 0
            atc = 0
            health = 20
        }
        val firstKill = testCard("FIRST_KILL").apply { cost = 0; atc = 1; health = 2 }
        val secondKill = testCard("SECOND_KILL").apply { cost = 0; atc = 1; health = 1 }
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(firstKill, war.rival.playArea)
        war.addCard(secondKill, war.rival.playArea)

        val generated = hero.action.generateAttackActions(war, war.me)
        assertTrue(generated.isNotEmpty())
        assertTrue(generated.all { !it.targetEntityId.isNullOrBlank() })
        assertTrue(generated.any { it.targetIsHero })
        assertFalse(
            PirateDemonHunterMctsExperimentModel.isActionLegal(
                AttackAction({}, {}, hero),
                war,
            ),
        )

        assertFalse(
            PirateDemonHunterMctsExperimentModel.isActionLegal(
                AttackAction({}, {}, hero, targetEntityId = rivalHero.entityId, targetIsHero = true),
                war,
            ),
        )
        assertTrue(
            PirateDemonHunterMctsExperimentModel.isActionLegal(
                AttackAction({}, {}, hero, targetEntityId = firstKill.entityId),
                war,
            ),
        )
        assertFalse(
            PirateDemonHunterMctsExperimentModel.isActionLegal(
                AttackAction({}, {}, hero, targetEntityId = secondKill.entityId),
                war,
            ),
        )
    }

    @Test
    fun `captain and hozen are downranked without another pirate on board`() {
        val war = testWar()
        val captain = testCard(PirateDemonHunterMctsExperimentModel.SOUTHSEA_CAPTAIN)
        val hozen = testCard(PirateDemonHunterMctsExperimentModel.HOZEN_ROUGHHOUSER)
        war.addCard(captain, war.me.handArea)
        war.addCard(hozen, war.me.handArea)

        val captainPrior = PirateDemonHunterMctsExperimentModel.actionPrior(
            PlayAction({}, {}, captain),
            war,
        )
        val hozenPrior = PirateDemonHunterMctsExperimentModel.actionPrior(
            PlayAction({}, {}, hozen),
            war,
        )

        assertTrue(captainPrior < 0.0)
        assertTrue(hozenPrior < 0.0)
    }

    @Test
    fun `battlefield is downranked on an empty minion board but cannon is still enabled`() {
        val war = testWar()
        val battlefield = testCard(PirateDemonHunterMctsExperimentModel.BATTLEFIELD)
        val cannon = testCard(PirateDemonHunterMctsExperimentModel.SHIPS_CANNON)
        war.addCard(battlefield, war.me.handArea)
        war.addCard(cannon, war.me.handArea)

        val battlefieldPrior = PirateDemonHunterMctsExperimentModel.actionPrior(
            PlayAction({}, {}, battlefield),
            war,
        )
        val cannonPrior = PirateDemonHunterMctsExperimentModel.actionPrior(
            PlayAction({}, {}, cannon),
            war,
        )

        assertTrue(battlefieldPrior < 0.0)
        assertTrue(cannonPrior > 0.0)
    }

    @Test
    fun `affordable zilliax is the mandatory first play on a low risk enemy board`() {
        val war = testWar().apply { me.resources = 4 }
        val zilliax = testCard("TOY_330t7").apply {
            cardType = CardTypeEnum.MINION
            cost = 4
            atc = 5
            health = 7
        }
        val other = testCard("OTHER_PLAY").apply { cost = 1 }
        war.addCard(zilliax, war.me.handArea)
        war.addCard(other, war.me.handArea)

        assertTrue(PirateDemonHunterMctsExperimentModel.shouldPrioritizeEarlyZilliax(war))
        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))

        assertTrue(node.actions.isNotEmpty())
        assertTrue(node.actions.all { it.creator?.entityId == zilliax.entityId })
    }

    @Test
    fun `zilliax low risk signal uses enemy count or visible attack total`() {
        val war = testWar()
        val first = testCard("RIVAL_ONE").apply { atc = 8 }
        val second = testCard("RIVAL_TWO").apply { atc = 1 }
        war.addCard(first, war.rival.playArea)
        assertTrue(PirateDemonHunterMctsExperimentModel.shouldPrioritizeEarlyZilliax(war))

        war.addCard(second, war.rival.playArea)
        assertFalse(PirateDemonHunterMctsExperimentModel.shouldPrioritizeEarlyZilliax(war))

        first.atc = 1
        assertTrue(PirateDemonHunterMctsExperimentModel.shouldPrioritizeEarlyZilliax(war))
    }

    @Test
    fun `sigil stays visible and receives a strong setup prior beside another playable card`() {
        val war = testWar()
        val sigil = testCard(PirateDemonHunterMctsExperimentModel.SIGIL_OF_SKYDIVING).apply {
            cardType = CardTypeEnum.SPELL
            cost = 2
        }
        val ordinary = testCard("ORDINARY_PIRATE").apply { cost = 1 }
        war.addCard(sigil, war.me.handArea)
        war.addCard(ordinary, war.me.handArea)

        assertFalse(PirateDemonHunterMctsExperimentModel.shouldDefer(sigil, war))
        assertTrue(
            PirateDemonHunterMctsExperimentModel.actionPrior(
                PlayAction({}, {}, sigil),
                war,
            ) > PirateDemonHunterMctsExperimentModel.actionPrior(PlayAction({}, {}, ordinary), war),
        )
    }

    @Test
    fun `weapon attendant is favored when a pirate is present and no weapon is equipped`() {
        val war = testWar()
        val pirate = testCard("PIRATE_ON_BOARD")
        val attendant = testCard(PirateDemonHunterMctsExperimentModel.WEAPONS_ATTENDANT).apply {
            cardType = CardTypeEnum.MINION
            cost = 2
        }
        war.addCard(pirate, war.me.playArea)
        war.addCard(attendant, war.me.handArea)

        val prior = PirateDemonHunterMctsExperimentModel.actionPrior(PlayAction({}, {}, attendant), war)
        assertTrue(prior >= 14.0)
    }

    @Test
    fun `replacement weapon is hard-blocked while equipped weapon still has a valuable attack`() {
        val war = testWar().apply { me.resources = 3 }
        val hero = testCard("HERO_WITH_WEAPON").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("RIVAL_HERO").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 30
        }
        val equipped = testCard("EQUIPPED_WEAPON").apply {
            cardType = CardTypeEnum.WEAPON
            cardRace = CardRaceEnum.UNKNOWN
            atc = 3
            durability = 2
            health = 0
        }
        val replacement = testCard("REPLACEMENT_WEAPON").apply {
            cardType = CardTypeEnum.WEAPON
            cardRace = CardRaceEnum.UNKNOWN
            atc = 4
            durability = 2
            health = 0
            cost = 3
        }
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(equipped, war.me.playArea)
        war.addCard(replacement, war.me.handArea)

        val replacementAction = PlayAction({}, {}, replacement)
        assertTrue(PirateDemonHunterMctsExperimentModel.shouldDefer(replacement, war))
        assertTrue(PirateDemonHunterMctsExperimentModel.isDeferredAction(replacementAction, war))

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))
        assertTrue(node.actions.none { it.creator?.entityId == replacement.entityId })
        assertTrue(node.actions.any { it is AttackAction && it.creator?.entityId == hero.entityId })
    }

    @Test
    fun `replacement weapon stays blocked while any equipped weapon is still parsed`() {
        val war = testWar().apply { me.resources = 3 }
        val hero = testCard("EXHAUSTED_HERO").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 30
            isExhausted = true
        }
        val equipped = testCard("SPENT_WEAPON").apply {
            cardType = CardTypeEnum.WEAPON
            cardRace = CardRaceEnum.UNKNOWN
            atc = 3
            durability = 1
            health = 0
        }
        val replacement = testCard("NEXT_WEAPON").apply {
            cardType = CardTypeEnum.WEAPON
            cardRace = CardRaceEnum.UNKNOWN
            atc = 4
            durability = 2
            health = 0
            cost = 3
        }
        war.addCard(hero, war.me.playArea)
        war.addCard(equipped, war.me.playArea)
        war.addCard(replacement, war.me.handArea)

        assertTrue(PirateDemonHunterMctsExperimentModel.shouldDefer(replacement, war))
        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))
        assertTrue(node.actions.none { it.creator?.entityId == replacement.entityId })
    }

    @Test
    fun `piggy receives a high but conditional prior against enemies in three damage range`() {
        val war = testWar()
        val piggy = testCard(PirateDemonHunterMctsExperimentModel.PIGGY).apply { cost = 2 }
        val normal = testCard("ORDINARY_PIRATE").apply { cost = 2 }
        war.addCard(piggy, war.me.handArea)
        war.addCard(normal, war.me.handArea)
        war.addCard(testCard("THREE_HEALTH_ENEMY").apply {
            cardRace = CardRaceEnum.UNKNOWN
            health = 3
            atc = 4
        }, war.rival.playArea)

        val piggyPrior = PirateDemonHunterMctsExperimentModel.actionPrior(PlayAction({}, {}, piggy), war)
        val normalPrior = PirateDemonHunterMctsExperimentModel.actionPrior(PlayAction({}, {}, normal), war)
        assertTrue(piggyPrior > normalPrior)
        assertTrue(piggyPrior >= 20.0)
    }

    @Test
    fun `hozen is included in effective pirate attack calculation`() {
        val war = testWar()
        val hozen = testCard(PirateDemonHunterMctsExperimentModel.HOZEN_ROUGHHOUSER)
        val pirate = testCard("PIRATE_ATTACKER").apply { atc = 2 }
        war.addCard(hozen, war.me.playArea)
        war.addCard(pirate, war.me.playArea)

        assertEquals(3, PirateDemonHunterMctsExperimentModel.effectivePirateAttack(pirate, war))
    }

    @Test
    fun `adrenaline fiend values every pirate attack opportunity as hero attack`() {
        val war = testWar()
        val fiend = testCard(PirateDemonHunterMctsExperimentModel.ADRENALINE_FIEND)
        val pirate = testCard("READY_PIRATE")
        val windfuryPirate = testCard("WINDFURY_PIRATE").apply { isWindFury = true }
        war.addCard(fiend, war.me.playArea)
        war.addCard(pirate, war.me.playArea)
        war.addCard(windfuryPirate, war.me.playArea)

        // One Fiend attack, one ordinary attack, and two Windfury attacks,
        // multiplied by the one Fiend trigger.  This is the future
        // hero-attack resource the evaluator must see before EndTurn.
        assertEquals(
            4,
            PirateDemonHunterMctsExperimentModel.expectedAdrenalineHeroAttack(war),
        )
    }

    @Test
    fun `adrenaline fiend makes a pirate attack and resulting board score more valuable`() {
        val withoutFiend = testWar()
        val pirate = testCard("READY_PIRATE")
        withoutFiend.addCard(pirate, withoutFiend.me.playArea)

        val withFiend = testWar()
        withFiend.addCard(testCard(PirateDemonHunterMctsExperimentModel.ADRENALINE_FIEND), withFiend.me.playArea)
        val samePirate = testCard("READY_PIRATE")
        withFiend.addCard(samePirate, withFiend.me.playArea)

        val attackWithout = AttackAction({}, {}, pirate)
        val attackWith = AttackAction({}, {}, samePirate)
        assertTrue(
            PirateDemonHunterMctsExperimentModel.actionPrior(attackWith, withFiend) >
                PirateDemonHunterMctsExperimentModel.actionPrior(attackWithout, withoutFiend),
        )
        assertTrue(
            PirateDemonHunterMctsExperimentModel.scoreAdjustment(withFiend) >
                PirateDemonHunterMctsExperimentModel.scoreAdjustment(withoutFiend),
        )
    }

    @Test
    fun `playing hozen immediately gives existing pirates one health`() {
        val war = testWar()
        val hozen = testCard(PirateDemonHunterMctsExperimentModel.HOZEN_ROUGHHOUSER).apply {
            cost = 3
        }
        val pirate = testCard("EXISTING_PIRATE").apply {
            health = 2
        }
        war.addCard(hozen, war.me.handArea)
        war.addCard(pirate, war.me.playArea)

        val play = hozen.action.generatePlayActions(war, war.me).single()
        val after = MonteCarloTreeNode(war, InitAction, testMctsArg()).buildNextNode(play).state.war

        assertEquals(3, after.me.playArea.findByEntityId(pirate.entityId)?.health)
    }

    @Test
    fun `adrenaline fiend remains legal without an immediate attack`() {
        val card = testCard(PirateDemonHunterMctsExperimentModel.ADRENALINE_FIEND)
        val war = testWar()
        assertFalse(PirateDemonHunterMctsExperimentModel.shouldDefer(card, war))
        assertEquals("海盗瞎 V1.0", HsPirateDemonHunterMctsGlobalPlanDeckStrategy().name())
    }

    @Test
    fun `blindeye judge waits while pufferfist can clear one-health enemy board`() {
        val war = testWar()
        war.me.resources = 4
        val judge = testCard(PirateDemonHunterMctsExperimentModel.BLINDEYE_JUDGE).apply {
            cost = 4
        }
        val pufferfist = testCard(PirateDemonHunterMctsExperimentModel.PUFFERFIST).apply {
            cost = 3
        }
        war.addCard(judge, war.me.handArea)
        war.addCard(pufferfist, war.me.handArea)
        repeat(3) {
            war.addCard(testCard("ONE_HEALTH_ENEMY_$it").apply {
                cardRace = CardRaceEnum.UNKNOWN
                cardType = CardTypeEnum.MINION
                health = 1
                atc = 1
            }, war.rival.playArea)
        }

        val judgeAction = PlayAction({}, {}, judge)
        val pufferfistAction = PlayAction({}, {}, pufferfist)
        val judgePrior = PirateDemonHunterMctsExperimentModel.actionPrior(judgeAction, war)
        val pufferfistPrior = PirateDemonHunterMctsExperimentModel.actionPrior(pufferfistAction, war)

        assertTrue(PirateDemonHunterMctsExperimentModel.isDeferredAction(judgeAction, war))
        assertFalse(PirateDemonHunterMctsExperimentModel.isDeferredAction(pufferfistAction, war))
        assertTrue(pufferfistPrior > judgePrior)
        assertTrue(pufferfistPrior >= 20.0)

        val arg = MCTSArg(
            endMillisTime = Long.MAX_VALUE,
            turnCount = 1,
            turnFactor = 0.5,
            countPerTurn = 1,
            scoreCalculator = { 0.0 },
            enableMultiThread = false,
            decisionModel = PirateDemonHunterMctsExperimentModel,
        )
        val node = MonteCarloTreeNode(war, InitAction, arg)
        assertFalse(node.actions.any { it.creator?.cardId == PirateDemonHunterMctsExperimentModel.BLINDEYE_JUDGE })
        assertTrue(node.actions.any { it.creator?.cardId == PirateDemonHunterMctsExperimentModel.PUFFERFIST })
    }

    @Test
    fun `hero power is deferred until non-hero-power actions are exhausted`() {
        val war = testWar().apply { me.resources = 2 }
        val heroPower = testCard("HERO_POWER_TEST").apply {
            cardType = CardTypeEnum.HERO_POWER
            cardRace = CardRaceEnum.UNKNOWN
            cost = 1
            isExhausted = false
        }
        val ordinary = testCard("ORDINARY_PIRATE").apply { cost = 1 }
        war.addCard(heroPower, war.me.playArea)
        war.addCard(ordinary, war.me.handArea)

        val heroPowerAction = PowerAction({}, {}, heroPower)
        val ordinaryAction = PlayAction({}, {}, ordinary)
        assertTrue(PirateDemonHunterMctsExperimentModel.isDeferredAction(heroPowerAction, war))
        assertTrue(
            PirateDemonHunterMctsExperimentModel.actionPrior(heroPowerAction, war) <
                PirateDemonHunterMctsExperimentModel.actionPrior(ordinaryAction, war),
        )

        val onlyHeroPower = testWar().apply { me.resources = 1 }
        val onlyPower = testCard("HERO_POWER_ONLY").apply {
            cardType = CardTypeEnum.HERO_POWER
            cardRace = CardRaceEnum.UNKNOWN
            cost = 1
            isExhausted = false
        }
        onlyHeroPower.addCard(onlyPower, onlyHeroPower.me.playArea)
        assertFalse(
            PirateDemonHunterMctsExperimentModel.isDeferredAction(
                PowerAction({}, {}, onlyPower),
                onlyHeroPower,
            ),
        )
    }

    @Test
    fun `hero power stays deferred when coin bridges to a non-power card`() {
        val war = testWar().apply { me.resources = 1 }
        val heroPower = testCard("HERO_POWER_BRIDGE").apply {
            cardType = CardTypeEnum.HERO_POWER
            cardRace = CardRaceEnum.UNKNOWN
            cost = 1
            isExhausted = false
        }
        val coin = testCard("COIN_BRIDGE").apply {
            cardType = CardTypeEnum.SPELL
            cardRace = CardRaceEnum.UNKNOWN
            cost = 0
            isCoinCard = true
        }
        val twoCostCard = testCard("TWO_COST_NON_POWER").apply { cost = 2 }
        war.addCard(heroPower, war.me.playArea)
        war.addCard(coin, war.me.handArea)
        war.addCard(twoCostCard, war.me.handArea)

        assertTrue(
            PirateDemonHunterMctsExperimentModel.isDeferredAction(
                PowerAction({}, {}, heroPower),
                war,
            ),
        )
    }

    @Test
    fun `mcts root and replans enforce minion play then minion attack then hero power`() {
        val war = testWar().apply { me.resources = 10 }
        val handMinion = testCard("HAND_MINION_FOR_ORDER")
        val handSpell = testCard(PirateDemonHunterMctsExperimentModel.SIGIL_OF_SKYDIVING).apply {
            cardType = CardTypeEnum.SPELL
            cardRace = CardRaceEnum.UNKNOWN
            cost = 1
        }
        val readyMinion = testCard("READY_MINION_FOR_ORDER").apply { isExhausted = false }
        val hero = testCard("HERO_FOR_ORDER_WITH_PLAY").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 1
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("RIVAL_HERO_FOR_ORDER_WITH_PLAY").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 30
        }
        val heroPower = testCard("HERO_POWER_FOR_ORDER_WITH_PLAY").apply {
            cardType = CardTypeEnum.HERO_POWER
            cardRace = CardRaceEnum.UNKNOWN
            cost = 1
            isLaunchpad = true
            isExhausted = false
        }
        war.addCard(handMinion, war.me.handArea)
        war.addCard(handSpell, war.me.handArea)
        war.addCard(readyMinion, war.me.playArea)
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(heroPower, war.me.playArea)

        val arg = testMctsArg(experimentalSearch = true)
        val root = MonteCarloTreeNode(war, InitAction, arg)
        assertTrue(root.actions.isNotEmpty())
        assertTrue(root.actions.all {
            it is PlayAction && it.creator?.cardType === CardTypeEnum.MINION
        })

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
            it is PlayAction && it.creator?.entityId == handSpell.entityId
        })

        val afterSpellPlay = afterMinionPlay.buildNextNode(afterMinionPlay.actions.single())
        assertTrue(afterSpellPlay.actions.isNotEmpty())
        assertTrue(afterSpellPlay.actions.all {
            it is AttackAction && it.creator?.cardType === CardTypeEnum.MINION
        })

        val afterMinionAttack = afterSpellPlay.buildNextNode(afterSpellPlay.actions.single())
        assertTrue(afterMinionAttack.actions.isNotEmpty())
        assertTrue(afterMinionAttack.actions.all {
            it is PowerAction && it.creator?.cardType === CardTypeEnum.HERO_POWER
        })

        val afterHeroPower = afterMinionAttack.buildNextNode(afterMinionAttack.actions.single())
        assertTrue(afterHeroPower.actions.isNotEmpty())
        assertTrue(afterHeroPower.actions.all {
            it is AttackAction && it.creator?.cardType === CardTypeEnum.HERO
        })
    }

    @Test
    fun `hero power remains available when it is the only bridge to an attack`() {
        val war = testWar().apply { me.resources = 0 }
        val heroPower = testCard("HERO_POWER_ONLY_BRIDGE").apply {
            cardType = CardTypeEnum.HERO_POWER
            cardRace = CardRaceEnum.UNKNOWN
            cost = 1
            isExhausted = false
        }
        val coin = testCard("COIN_ONLY_BRIDGE").apply {
            cardType = CardTypeEnum.SPELL
            cardRace = CardRaceEnum.UNKNOWN
            cost = 0
            isCoinCard = true
        }
        war.addCard(heroPower, war.me.playArea)
        war.addCard(coin, war.me.handArea)

        assertFalse(
            PirateDemonHunterMctsExperimentModel.isDeferredAction(
                PowerAction({}, {}, heroPower),
                war,
            ),
        )
    }

    @Test
    fun `mcts orders fiend minion attacks then hero power then hero attack`() {
        val war = testWar().apply { me.resources = 5 }
        val fiend = testCard(PirateDemonHunterMctsExperimentModel.ADRENALINE_FIEND).apply {
            // The Fiend supplies the aura but is not itself a ready attack in
            // this fixture, so the first root is unambiguous.
            isExhausted = true
        }
        val attacker = testCard("READY_PIRATE_FOR_ORDER")
        val hero = testCard("HERO_FOR_ORDER").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 1
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("RIVAL_HERO_FOR_ORDER").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 30
        }
        val heroPower = testCard("HERO_POWER_FOR_ORDER").apply {
            cardType = CardTypeEnum.HERO_POWER
            cardRace = CardRaceEnum.UNKNOWN
            cost = 1
            isLaunchpad = true
            isExhausted = false
        }
        war.addCard(fiend, war.me.playArea)
        war.addCard(attacker, war.me.playArea)
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(heroPower, war.me.playArea)

        val root = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))
        assertTrue(root.actions.isNotEmpty())
        assertTrue(root.actions.all {
            it is AttackAction && it.creator?.cardType === CardTypeEnum.MINION
        })

        val afterMinionAttack = root.buildNextNode(root.actions.single())
        assertEquals(1, afterMinionAttack.actions.size)
        assertTrue(afterMinionAttack.actions.single() is PowerAction)
        assertEquals(heroPower.entityId, afterMinionAttack.actions.single().creator?.entityId)

        val afterHeroPower = afterMinionAttack.buildNextNode(afterMinionAttack.actions.single())
        assertEquals(1, afterHeroPower.actions.size)
        assertTrue(afterHeroPower.actions.single() is AttackAction)
        assertEquals(hero.entityId, afterHeroPower.actions.single().creator?.entityId)
    }

    @Test
    fun `without fiend hero attack is not artificially deferred`() {
        val war = testWar().apply { me.resources = 0 }
        val hero = testCard("HERO_WITHOUT_FIEND").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 1
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("RIVAL_WITHOUT_FIEND").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 30
        }
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)

        val heroAttack = hero.action.generateAttackActions(war, war.me).single()
        assertFalse(PirateDemonHunterMctsExperimentModel.isDeferredAction(heroAttack, war))
    }

    @Test
    fun `mcts does not spend coin when only hero power would be unlocked`() {
        val war = testWar().apply { me.resources = 0 }
        val coin = testCard("COIN_ONLY_HERO_POWER").apply {
            cardType = CardTypeEnum.SPELL
            cardRace = CardRaceEnum.UNKNOWN
            cost = 0
            isCoinCard = true
        }
        val heroPower = testCard("HERO_POWER_REQUIRES_COIN").apply {
            cardType = CardTypeEnum.HERO_POWER
            cardRace = CardRaceEnum.UNKNOWN
            cost = 1
            isExhausted = false
        }
        war.addCard(coin, war.me.handArea)
        war.addCard(heroPower, war.me.playArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))
        assertTrue(node.actions.none { it.creator?.entityId == coin.entityId })
    }

    @Test
    fun `post-hero-attack cliffside is the only action when two slots remain`() {
        val war = testWar().apply { me.resources = 0 }
        val cliffside = testCard(PirateDemonHunterMctsExperimentModel.DANGEROUS_CLIFFSIDE).apply {
            cardType = CardTypeEnum.LOCATION
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 3
            isExhausted = false
            isLocationActionCooldown = false
        }
        val hero = testCard("EXHAUSTED_HERO_FOR_LOCATION").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 30
            isExhausted = true
        }
        war.addCard(cliffside, war.me.playArea)
        war.addCard(hero, war.me.playArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))
        assertEquals(1, node.actions.size)
        assertTrue(node.actions.single() is PowerAction)
        assertEquals(cliffside.entityId, node.actions.single().creator?.entityId)
    }

    @Test
    fun `post-hero-attack cliffside is not exposed when two slots do not remain`() {
        val war = testWar().apply { me.resources = 0 }
        val cliffside = testCard(PirateDemonHunterMctsExperimentModel.DANGEROUS_CLIFFSIDE).apply {
            cardType = CardTypeEnum.LOCATION
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 3
            isExhausted = false
            isLocationActionCooldown = false
        }
        val hero = testCard("EXHAUSTED_HERO_NO_LOCATION_SLOT").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 30
            isExhausted = true
        }
        war.addCard(cliffside, war.me.playArea)
        repeat(6) { war.addCard(testCard("FULL_BOARD_$it"), war.me.playArea) }
        war.addCard(hero, war.me.playArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))
        assertTrue(node.actions.none { it.creator?.entityId == cliffside.entityId })
    }

    @Test
    fun `initial cliffside activation requires three free slots`() {
        val tooFewSlots = testWar().apply { me.resources = 0 }
        val cliffside = testCard(PirateDemonHunterMctsExperimentModel.DANGEROUS_CLIFFSIDE).apply {
            cardType = CardTypeEnum.LOCATION
            cardRace = CardRaceEnum.UNKNOWN
            health = 3
            isExhausted = false
            isLocationActionCooldown = false
        }
        tooFewSlots.addCard(cliffside, tooFewSlots.me.playArea)
        repeat(4) { tooFewSlots.addCard(testCard("INITIAL_CLIFFSIDE_FILL_$it"), tooFewSlots.me.playArea) }

        assertEquals(2, tooFewSlots.me.playArea.maxSize - tooFewSlots.me.playArea.cards.size)
        assertFalse(PirateDemonHunterMctsExperimentModel.canCreateOpaquePowerAction(cliffside, tooFewSlots))
        assertTrue(
            MonteCarloTreeNode(tooFewSlots, InitAction, testMctsArg(experimentalSearch = true))
                .actions.none { it.creator?.entityId == cliffside.entityId },
        )

        val enoughSlots = testWar().apply { me.resources = 0 }
        val playable = testCard(PirateDemonHunterMctsExperimentModel.DANGEROUS_CLIFFSIDE).apply {
            cardType = CardTypeEnum.LOCATION
            cardRace = CardRaceEnum.UNKNOWN
            health = 3
            isExhausted = false
            isLocationActionCooldown = false
        }
        enoughSlots.addCard(playable, enoughSlots.me.playArea)
        repeat(3) { enoughSlots.addCard(testCard("INITIAL_CLIFFSIDE_FILL_OK_$it"), enoughSlots.me.playArea) }

        assertEquals(3, enoughSlots.me.playArea.maxSize - enoughSlots.me.playArea.cards.size)
        assertTrue(PirateDemonHunterMctsExperimentModel.canCreateOpaquePowerAction(playable, enoughSlots))
        assertTrue(
            MonteCarloTreeNode(enoughSlots, InitAction, testMctsArg(experimentalSearch = true))
                .actions.any { it.creator?.entityId == playable.entityId },
        )
    }

    @Test
    fun `weapon-backed hero attack prevents end turn on a stale hero attack stat`() {
        val war = testWar().apply { me.resources = 0 }
        val hero = testCard("STALE_HERO_ATTACK_STAT").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 30
            isExhausted = false
        }
        val weapon = testCard("EQUIPPED_WEAPON_FOR_STALE_ATTACK").apply {
            cardType = CardTypeEnum.WEAPON
            cardRace = CardRaceEnum.UNKNOWN
            atc = 3
            durability = 1
            health = 0
        }
        val rivalHero = testCard("RIVAL_HERO_FOR_STALE_ATTACK").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 30
        }
        war.addCard(hero, war.me.playArea)
        war.addCard(weapon, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))
        assertTrue(node.actions.any { it is AttackAction && it.creator?.entityId == hero.entityId })
        assertTrue(node.actions.none { it === TurnOverAction })
    }

    @Test
    fun `simulated weapon play blocks a second weapon in the same turn`() {
        val war = testWar().apply { me.resources = 6 }
        val firstWeapon = testCard("FIRST_SIMULATED_WEAPON").apply {
            cardType = CardTypeEnum.WEAPON
            cardRace = CardRaceEnum.UNKNOWN
            cost = 2
            atc = 3
            durability = 2
            health = 0
        }
        val secondWeapon = testCard("SECOND_SIMULATED_WEAPON").apply {
            cardType = CardTypeEnum.WEAPON
            cardRace = CardRaceEnum.UNKNOWN
            cost = 2
            atc = 4
            durability = 2
            health = 0
        }
        war.addCard(firstWeapon, war.me.handArea)
        war.addCard(secondWeapon, war.me.handArea)

        val root = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))
        val firstPlay = root.actions.first { it.creator?.entityId == firstWeapon.entityId }
        val afterFirstPlay = root.buildNextNode(firstPlay)

        assertTrue(afterFirstPlay.state.war.me.playArea.weapon != null)
        assertTrue(afterFirstPlay.actions.none { it.creator?.cardType === CardTypeEnum.WEAPON })
    }

    @Test
    fun `deferred timing card cannot form a defer cycle with blindeye judge`() {
        val war = testWar().apply { me.resources = 5 }
        val heroPower = testCard("HERO_POWER_TIMING_CYCLE").apply {
            cardType = CardTypeEnum.HERO_POWER
            cardRace = CardRaceEnum.UNKNOWN
            cost = 1
            isExhausted = false
        }
        val blindeyeJudge = testCard(PirateDemonHunterMctsExperimentModel.BLINDEYE_JUDGE).apply {
            cost = 4
        }
        val ragewing = testCard(PirateDemonHunterMctsExperimentModel.RAGEWING).apply {
            cost = 2
        }
        war.addCard(heroPower, war.me.playArea)
        war.addCard(blindeyeJudge, war.me.handArea)
        war.addCard(ragewing, war.me.handArea)

        assertTrue(CardTimingPolicy.shouldDefer(ragewing, war))
        assertTrue(
            PirateDemonHunterMctsExperimentModel.isDeferredAction(
                PlayAction({}, {}, blindeyeJudge),
                war,
            ),
        )
        assertFalse(
            PirateDemonHunterMctsExperimentModel.isDeferredAction(
                PowerAction({}, {}, heroPower),
                war,
            ),
        )
    }

    @Test
    fun `cliffside activation is mandatory before other actions`() {
        val war = testWar()
        val cliffside = testCard(PirateDemonHunterMctsExperimentModel.DANGEROUS_CLIFFSIDE).apply {
            cardType = CardTypeEnum.LOCATION
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 3
            isExhausted = false
            isLocationActionCooldown = false
        }
        war.addCard(cliffside, war.me.playArea)

        val activation = PowerAction({}, {}, cliffside)

        assertTrue(PirateDemonHunterMctsExperimentModel.canCreateOpaquePowerAction(cliffside, war))
        assertTrue(PirateDemonHunterMctsExperimentModel.isMandatoryAction(activation, war))
        assertTrue(
            PirateDemonHunterMctsExperimentModel.actionPrior(activation, war) >
                PirateDemonHunterMctsExperimentModel.actionPrior(PlayAction({}, {}, testCard("OTHER")), war),
        )
    }

    @Test
    fun `mcts exposes opaque cliffside power action and filters competing actions`() {
        val war = testWar()
        val cliffside = testCard(PirateDemonHunterMctsExperimentModel.DANGEROUS_CLIFFSIDE).apply {
            cardType = CardTypeEnum.LOCATION
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 3
            isExhausted = false
            isLocationActionCooldown = false
        }
        war.addCard(cliffside, war.me.playArea)

        val arg = MCTSArg(
            endMillisTime = Long.MAX_VALUE,
            turnCount = 1,
            turnFactor = 0.5,
            countPerTurn = 1,
            scoreCalculator = { 0.0 },
            enableMultiThread = false,
            decisionModel = PirateDemonHunterMctsExperimentModel,
        )
        val node = MonteCarloTreeNode(war, InitAction, arg)

        assertEquals(1, node.actions.size)
        assertTrue(node.actions.single() is PowerAction)
        assertEquals(cliffside.entityId, node.actions.single().creator?.entityId)
    }

    @Test
    fun `cliffside chain forces hero attack then exposes the second activation`() {
        val war = testWar()
        val cliffside = testCard(PirateDemonHunterMctsExperimentModel.DANGEROUS_CLIFFSIDE).apply {
            cardType = CardTypeEnum.LOCATION
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 3
            isExhausted = false
            isLocationActionCooldown = false
        }
        val hero = testCard("HERO_TEST").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 1
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("RIVAL_HERO_TEST").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 0
            health = 30
        }
        war.addCard(cliffside, war.me.playArea)
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        // The location creates Pirate tokens by copying a Pirate in the deck;
        // keeping the template in the deck avoids adding a competing board action.
        war.addCard(testCard("PIRATE_TEMPLATE"), war.me.deckArea)

        val arg = MCTSArg(
            endMillisTime = Long.MAX_VALUE,
            turnCount = 1,
            turnFactor = 0.5,
            countPerTurn = 1,
            scoreCalculator = { 0.0 },
            enableMultiThread = false,
            decisionModel = PirateDemonHunterMctsExperimentModel,
        )
        val root = MonteCarloTreeNode(war, InitAction, arg)
        val firstActivation = root.actions.single()
        assertTrue(firstActivation is PowerAction)

        val afterFirstActivation = root.buildNextNode(firstActivation)
        assertEquals(2, afterFirstActivation.state.war.me.playArea.cards.count {
            it.cardId == PirateDemonHunterMctsExperimentModel.CLIFFSIDE_PIRATE_TOKEN
        })
        assertTrue(afterFirstActivation.state.war.me.playArea.cards
            .filter { it.cardId == PirateDemonHunterMctsExperimentModel.CLIFFSIDE_PIRATE_TOKEN }
            .all { PirateDemonHunterMctsExperimentModel.isPirate(it) })
        assertEquals(1, afterFirstActivation.actions.size)
        assertTrue(afterFirstActivation.actions.single() is AttackAction)
        assertEquals(hero.entityId, afterFirstActivation.actions.single().creator?.entityId)

        val afterHeroAttack = afterFirstActivation.buildNextNode(afterFirstActivation.actions.single())
        assertEquals(1, afterHeroAttack.actions.size)
        assertTrue(afterHeroAttack.actions.single() is PowerAction)
        assertEquals(cliffside.entityId, afterHeroAttack.actions.single().creator?.entityId)
    }

    @Test
    fun `stale cliffside cooldown after hero attack requests a bounded perception retry`() {
        val war = testWar()
        val cliffside = testCard(PirateDemonHunterMctsExperimentModel.DANGEROUS_CLIFFSIDE).apply {
            cardType = CardTypeEnum.LOCATION
            cardRace = CardRaceEnum.UNKNOWN
            isLocationActionCooldown = true
        }
        val hero = testCard("HERO_TEST").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            atc = 1
            health = 30
            isExhausted = true
        }
        war.addCard(cliffside, war.me.playArea)
        war.addCard(hero, war.me.playArea)

        assertTrue(
            PirateDemonHunterMctsExperimentModel.shouldRetryAfterEmptySearch(war),
            "a stale location cooldown after the hero attack is a perception wait, not EndTurn",
        )

        cliffside.isLocationActionCooldown = false
        assertFalse(PirateDemonHunterMctsExperimentModel.shouldRetryAfterEmptySearch(war))

        cliffside.isLocationActionCooldown = true
        repeat(5) { war.addCard(testCard("FULL_$it"), war.me.playArea) }
        assertFalse(PirateDemonHunterMctsExperimentModel.shouldRetryAfterEmptySearch(war))
    }

    @Test
    fun `playing cliffside immediately exposes its activation and summons two pirates`() {
        val war = testWar()
        val cliffside = testCard(PirateDemonHunterMctsExperimentModel.DANGEROUS_CLIFFSIDE).apply {
            cardType = CardTypeEnum.LOCATION
            cardRace = CardRaceEnum.UNKNOWN
            cost = 4
            atc = 0
            health = 3
            isExhausted = false
            isLocationActionCooldown = false
        }
        war.addCard(cliffside, war.me.handArea)
        war.addCard(testCard("PIRATE_TEMPLATE"), war.me.deckArea)

        val root = MonteCarloTreeNode(war, InitAction, testMctsArg())
        val play = root.actions.firstOrNull { it.creator?.entityId == cliffside.entityId }
        assertTrue(play != null, "a playable cliffside location should be present in the root action list")

        val afterPlay = root.buildNextNode(play!!)
        assertEquals(1, afterPlay.actions.size)
        assertTrue(afterPlay.actions.single() is PowerAction)
        assertEquals(cliffside.entityId, afterPlay.actions.single().creator?.entityId)

        val afterActivation = afterPlay.buildNextNode(afterPlay.actions.single())
        assertEquals(2, afterActivation.state.war.me.playArea.cards.count {
            it.cardId == PirateDemonHunterMctsExperimentModel.CLIFFSIDE_PIRATE_TOKEN
        })
        assertTrue(afterActivation.state.war.me.playArea.cards
            .filter { it.cardId == PirateDemonHunterMctsExperimentModel.CLIFFSIDE_PIRATE_TOKEN }
            .all { PirateDemonHunterMctsExperimentModel.isPirate(it) })
    }

    @Test
    fun `released pirate mcts strategy wires the global plan model and live replanning`() {
        val arg = HsPirateDemonHunterMctsGlobalPlanDeckStrategy().executeMCTSOutCard(testWar()).single()

        assertTrue(arg.experimentalSearch)
        assertTrue(arg.decisionModel === PirateDemonHunterMctsGlobalPlanModel)
    }

    @Test
    fun `released pirate mcts strategy opts into global plan selection`() {
        val global = HsPirateDemonHunterMctsGlobalPlanDeckStrategy().executeMCTSOutCard(testWar()).single()

        assertEquals(MctsRootSelectionPolicy.GLOBAL_TURN_PLAN, global.rootSelectionPolicy)
        assertTrue(global.decisionModel === PirateDemonHunterMctsGlobalPlanModel)
        assertEquals("海盗瞎 V1.0", HsPirateDemonHunterMctsGlobalPlanDeckStrategy().name())
    }

    @Test
    fun `released pirate mcts strategy keeps the established mulligan baseline`() {
        val strategy = HsPirateDemonHunterMctsGlobalPlanDeckStrategy()
        val patches = testCard(PirateDemonHunterMctsExperimentModel.PATCHES_THE_PIRATE).apply { cost = 1 }
        val cheap = testCard("CHEAP_KEEP").apply { cost = 2 }
        val expensive = testCard("EXPENSIVE_REPLACE").apply { cost = 3 }
        val cards = hashSetOf(patches, cheap, expensive)

        strategy.executeChangeCard(cards)

        assertEquals(setOf(cheap), cards)
    }

    @Test
    fun `global plan penalizes missed reachable mana without requiring a card id or fixed order`() {
        val root = testWar().apply { me.resources = 3 }
        val first = testCard("GENERIC_ONE_COST").apply { cost = 1 }
        val second = testCard("GENERIC_TWO_COST").apply { cost = 2 }
        root.addCard(first, root.me.handArea)
        root.addCard(second, root.me.handArea)

        val allManaUsed = testWar().apply {
            me.resources = 3
            me.usedResources = 3
        }
        val oneManaLeft = testWar().apply {
            me.resources = 3
            me.usedResources = 2
        }

        assertEquals(3, PirateDemonHunterMctsGlobalPlanModel.maxSpendableMana(root))
        val fullPlanAdjustment = PirateDemonHunterMctsGlobalPlanModel.turnPlanAdjustment(root, allManaUsed, emptyList())
        val shortPlanAdjustment = PirateDemonHunterMctsGlobalPlanModel.turnPlanAdjustment(root, oneManaLeft, emptyList())
        assertEquals(0.0, fullPlanAdjustment)
        assertEquals(-PirateDemonHunterMctsGlobalPlanModel.MANA_OPPORTUNITY_PENALTY, shortPlanAdjustment)
    }

    @Test
    fun `global plan search prefers a discovered full-mana sequence`() {
        val war = testWar().apply { me.resources = 3 }
        val hero = testCard("HERO_TEST").apply {
            cardType = CardTypeEnum.HERO
            health = 30
            isExhausted = true
        }
        val rivalHero = testCard("RIVAL_HERO_TEST").apply {
            cardType = CardTypeEnum.HERO
            health = 30
        }
        val oneCost = testCard("GENERIC_ONE_COST").apply { cost = 1 }
        val twoCost = testCard("GENERIC_TWO_COST").apply { cost = 2 }
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(oneCost, war.me.handArea)
        war.addCard(twoCost, war.me.handArea)

        val path = MonteCarloTreeSearch().searchBestNode(
            war,
            MCTSArg(
                // Keep this stochastic search assertion stable when the full
                // strategy test class is running alongside JVM/Kotlin setup.
                endMillisTime = System.currentTimeMillis() + 5_000L,
                turnCount = 1,
                turnFactor = 0.5,
                countPerTurn = 24,
                scoreCalculator = { 0.0 },
                enableMultiThread = false,
                decisionModel = PirateDemonHunterMctsGlobalPlanModel,
                experimentalSearch = true,
                rootSelectionPolicy = MctsRootSelectionPolicy.GLOBAL_TURN_PLAN,
            ),
        )

        assertTrue(path.size >= 2)
        assertEquals(0, path.last().state.war.me.usableResource)
    }

    @Test
    fun `cannon is the only first action when ragewing and ordinary cards compete`() {
        val war = testWar()
        val cannon = testCard(PirateDemonHunterMctsExperimentModel.SHIPS_CANNON).apply { cost = 2 }
        val ragewing = testCard(PirateDemonHunterMctsExperimentModel.RAGEWING).apply {
            cost = 1
            entityName = "狂暴邪翼蝠"
        }
        val ordinary = testCard("ORDINARY_PIRATE").apply { cost = 1 }
        war.addCard(cannon, war.me.handArea)
        war.addCard(ragewing, war.me.handArea)
        war.addCard(ordinary, war.me.handArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))

        assertTrue(node.actions.isNotEmpty())
        assertTrue(node.actions.all { it.creator?.cardId == PirateDemonHunterMctsExperimentModel.SHIPS_CANNON })
    }

    @Test
    fun `coin is the only first action when it immediately unlocks cannon`() {
        val war = testWar().apply { me.resources = 2 }
        val cannon = testCard(PirateDemonHunterMctsExperimentModel.SHIPS_CANNON).apply { cost = 3 }
        val coin = testCard("COIN").apply {
            // TestCardAction's generic spell implementation intentionally
            // exposes no play action.  Keep the fixture action-generatable
            // while retaining the production coin marker.
            cardType = CardTypeEnum.MINION
            cardRace = CardRaceEnum.UNKNOWN
            cost = 0
            isCoinCard = true
        }
        val ordinary = testCard("ORDINARY_PIRATE").apply { cost = 1 }
        war.addCard(cannon, war.me.handArea)
        war.addCard(coin, war.me.handArea)
        war.addCard(ordinary, war.me.handArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))

        assertTrue(node.actions.isNotEmpty())
        assertTrue(node.actions.all { it.creator?.isCoinCard == true })
        assertTrue(PirateDemonHunterMctsExperimentModel.isMandatoryAction(node.actions.single(), war))
    }

    @Test
    fun `ragewing is deferred while an ordinary playable card remains`() {
        val war = testWar()
        val ragewing = testCard(PirateDemonHunterMctsExperimentModel.RAGEWING).apply {
            cost = 1
            entityName = "狂暴邪翼蝠"
        }
        val ordinary = testCard("ORDINARY_PIRATE").apply { cost = 1 }
        war.addCard(ragewing, war.me.handArea)
        war.addCard(ordinary, war.me.handArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))

        assertTrue(node.actions.any { it.creator?.cardId == ordinary.cardId })
        assertTrue(node.actions.none { it.creator?.cardId == PirateDemonHunterMctsExperimentModel.RAGEWING })
    }

    @Test
    fun `ragewing remains legal when it is the only playable hand action`() {
        val war = testWar()
        val ragewing = testCard(PirateDemonHunterMctsExperimentModel.RAGEWING).apply {
            cost = 1
            entityName = "狂暴邪翼蝠"
        }
        war.addCard(ragewing, war.me.handArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))

        assertTrue(node.actions.any { it.creator?.cardId == PirateDemonHunterMctsExperimentModel.RAGEWING })
        assertTrue(node.actions.none { it.javaClass.simpleName == "TurnOverAction" })
    }

    @Test
    fun `deferred cost minion cannot reopen after hero attack in the same turn`() {
        val war = testWar().apply { me.resources = 0 }
        val ragewing = testCard(PirateDemonHunterMctsExperimentModel.RAGEWING).apply {
            cost = 0
            entityName = "狂暴邪翼蝠"
        }
        val phase = PirateDemonHunterMctsExperimentModel.actionOrderPhase(
            PlayAction({}, {}, ragewing),
            war,
        )
        val fence = MctsTurnPhaseFence().apply {
            observe(MctsActionOrderPhase.HERO_ATTACK)
        }

        assertEquals(MctsActionOrderPhase.MINION_PLAY, phase)
        assertTrue(
            !fence.allows(
                phase,
                isEndTurn = false,
                endTurnLegal = true,
            ),
        )
    }

    @Test
    fun `weapon cannot reopen after hero attack in the same turn`() {
        val war = testWar().apply { me.resources = 0 }
        val weapon = testCard("REV_509").apply {
            cardType = CardTypeEnum.WEAPON
            cost = 0
        }
        val phase = PirateDemonHunterMctsExperimentModel.actionOrderPhase(
            PlayAction({}, {}, weapon),
            war,
        )
        val fence = MctsTurnPhaseFence().apply {
            observe(MctsActionOrderPhase.HERO_ATTACK)
        }

        assertEquals(MctsActionOrderPhase.MINION_PLAY, phase)
        assertTrue(
            !fence.allows(
                phase,
                isEndTurn = false,
                endTurnLegal = true,
            ),
        )
    }

    @Test
    fun `known parser-light pirate dh minions are eligible for opaque replanning`() {
        val war = testWar()
        val patches = testCard(PirateDemonHunterMctsExperimentModel.PATCHES_THE_PIRATE)
        val ragewing = testCard(PirateDemonHunterMctsExperimentModel.RAGEWING)
        val zilliax = testCard("TOY_330t11")
        val piggy = testCard(PirateDemonHunterMctsExperimentModel.PIGGY)

        assertTrue(PirateDemonHunterMctsExperimentModel.canCreateOpaqueAction(patches, war))
        assertTrue(PirateDemonHunterMctsExperimentModel.canCreateOpaqueAction(ragewing, war))
        assertTrue(PirateDemonHunterMctsExperimentModel.canCreateOpaqueAction(zilliax, war))
        assertTrue(PirateDemonHunterMctsExperimentModel.canCreateOpaqueAction(piggy, war))
    }

    @Test
    fun `cliffside in hand waits when three slots cannot be reserved`() {
        val war = testWar()
        val cliffside = testCard(PirateDemonHunterMctsExperimentModel.DANGEROUS_CLIFFSIDE).apply {
            cardType = CardTypeEnum.LOCATION
            cost = 4
        }
        repeat(5) {
            war.addCard(testCard("BOARD_$it"), war.me.playArea)
        }
        war.addCard(cliffside, war.me.handArea)

        assertEquals(2, war.me.playArea.maxSize - war.me.playArea.cards.size)
        assertTrue(PirateDemonHunterMctsExperimentModel.shouldDefer(cliffside, war))
    }

    @Test
    fun `cliffside in hand remains playable when three slots can be reserved`() {
        val war = testWar()
        val cliffside = testCard(PirateDemonHunterMctsExperimentModel.DANGEROUS_CLIFFSIDE).apply {
            cardType = CardTypeEnum.LOCATION
            cost = 4
        }
        repeat(4) {
            war.addCard(testCard("BOARD_$it"), war.me.playArea)
        }
        war.addCard(cliffside, war.me.handArea)

        assertEquals(3, war.me.playArea.maxSize - war.me.playArea.cards.size)
        assertFalse(PirateDemonHunterMctsExperimentModel.shouldDefer(cliffside, war))
    }

    @Test
    fun `cliffside activation waits when fewer than two summon slots remain`() {
        val war = testWar()
        val cliffside = testCard(PirateDemonHunterMctsExperimentModel.DANGEROUS_CLIFFSIDE).apply {
            cardType = CardTypeEnum.LOCATION
            cost = 4
            isLocationActionCooldown = false
        }
        repeat(6) {
            war.addCard(testCard("BOARD_$it"), war.me.playArea)
        }
        war.addCard(cliffside, war.me.playArea)

        val activation = PowerAction({}, {}, cliffside)
        assertEquals(0, war.me.playArea.maxSize - war.me.playArea.cards.size)
        assertFalse(PirateDemonHunterMctsExperimentModel.isMandatoryAction(activation, war))
    }

    @Test
    fun `experimental mcts never returns deferred ragewing as the root action`() {
        val war = testWar()
        val ragewing = testCard(PirateDemonHunterMctsExperimentModel.RAGEWING).apply {
            cost = 1
            entityName = "狂暴邪翼蝠"
        }
        val ordinary = testCard("ORDINARY_PIRATE").apply { cost = 1 }
        war.addCard(ragewing, war.me.handArea)
        war.addCard(ordinary, war.me.handArea)

        val path = MonteCarloTreeSearch().searchBestNode(
            war,
            testMctsArg(experimentalSearch = true).copy(
                enableMultiThread = true,
                countPerTurn = 8,
            ),
        )

        assertTrue(path.isNotEmpty())
        assertEquals(ordinary.cardId, path.first().applyAction.creator?.cardId)
        assertTrue(path.none { it.applyAction.creator?.cardId == PirateDemonHunterMctsExperimentModel.RAGEWING })
    }

    @Test
    fun `experimental mcts path keeps root action before its descendant`() {
        val war = testWar()
        val ordinary = testCard("ORDINARY_PIRATE").apply { cost = 1 }
        val hero = testCard("HERO_TEST").apply {
            cardType = CardTypeEnum.HERO
            atc = 1
            health = 30
            isExhausted = false
        }
        val rivalHero = testCard("RIVAL_HERO_TEST").apply {
            cardType = CardTypeEnum.HERO
            atc = 0
            health = 30
        }
        war.addCard(ordinary, war.me.handArea)
        war.addCard(hero, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)

        val path = MonteCarloTreeSearch().searchBestNode(
            war,
            testMctsArg(experimentalSearch = true).copy(
                enableMultiThread = true,
                countPerTurn = 24,
            ),
        )

        // The live executor consumes path.first().  A descendant must never
        // be moved ahead of the root action by path reconstruction.
        assertTrue(path.size >= 2)
        assertEquals(InitAction, path.first().parent?.applyAction)
    }

    @Test
    fun `experimental mcts does not expose end turn beside a legal action`() {
        val war = testWar()
        val ordinary = testCard("ORDINARY_PIRATE").apply { cost = 1 }
        war.addCard(ordinary, war.me.handArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))

        assertTrue(node.actions.any { it.creator?.cardId == ordinary.cardId })
        assertTrue(node.actions.none { it.javaClass.simpleName == "TurnOverAction" })
    }

    @Test
    fun `experimental mcts returns a legal root action when the budget expires before expansion`() {
        val war = testWar()
        val cannon = testCard(PirateDemonHunterMctsExperimentModel.SHIPS_CANNON).apply { cost = 2 }
        val ordinary = testCard("ORDINARY_PIRATE").apply { cost = 1 }
        war.addCard(cannon, war.me.handArea)
        war.addCard(ordinary, war.me.handArea)

        val path = MonteCarloTreeSearch().searchBestNode(
            war,
            testMctsArg(experimentalSearch = true).copy(
                endMillisTime = System.currentTimeMillis() - 1,
            ),
        )

        assertTrue(path.isNotEmpty())
        assertTrue(path.first().applyAction !== club.xiaojiawei.hsscriptcardsdk.bean.TurnOverAction)
        assertEquals(cannon.cardId, path.first().applyAction.creator?.cardId)
    }

    @Test
    fun `experimental mcts never returns end turn when a fresh root has an attackable minion`() {
        val war = testWar()
        val attacker = testCard("READY_MINION").apply {
            atc = 3
            health = 3
            isExhausted = false
        }
        val rivalHero = testCard("RIVAL_HERO_FOR_ATTACK").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
        }
        war.addCard(attacker, war.me.playArea)
        war.addCard(rivalHero, war.rival.playArea)

        val path = MonteCarloTreeSearch().searchBestNode(
            war,
            testMctsArg(experimentalSearch = true).copy(
                // Model the same no-rollout window seen in the live failure:
                // root generation is still required to return a real attack.
                endMillisTime = System.currentTimeMillis() - 1,
            ),
        )

        assertTrue(path.isNotEmpty())
        assertTrue(path.first().applyAction is AttackAction)
        assertEquals(attacker.entityId, path.first().applyAction.creator?.entityId)
    }

    @Test
    fun `experimental mcts preserves mandatory root action in parallel search path`() {
        val war = testWar()
        val cannon = testCard(PirateDemonHunterMctsExperimentModel.SHIPS_CANNON).apply { cost = 2 }
        val ordinary = testCard("ORDINARY_PIRATE").apply { cost = 1 }
        war.addCard(cannon, war.me.handArea)
        war.addCard(ordinary, war.me.handArea)

        val path = MonteCarloTreeSearch().searchBestNode(
            war,
            testMctsArg(experimentalSearch = true).copy(
                enableMultiThread = true,
            ),
        )

        assertTrue(path.isNotEmpty())
        assertEquals(cannon.cardId, path.first().applyAction.creator?.cardId)
    }

    @Test
    fun `parachute brigand is deferred behind another playable card even when free`() {
        val war = testWar().apply { me.resources = 1 }
        val brigand = testCard(PirateDemonHunterMctsExperimentModel.PARACHUTE_BRIGAND).apply { cost = 0 }
        val ordinary = testCard("ORDINARY_AFTER_BRIGAND").apply { cost = 1 }
        war.addCard(brigand, war.me.handArea)
        war.addCard(ordinary, war.me.handArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))

        assertTrue(node.actions.any { it.creator?.cardId == ordinary.cardId })
        assertTrue(node.actions.none { it.creator?.cardId == brigand.cardId })
    }

    @Test
    fun `parachute brigand remains available as the only free playable action`() {
        val war = testWar().apply { me.resources = 0 }
        val brigand = testCard(PirateDemonHunterMctsExperimentModel.PARACHUTE_BRIGAND).apply { cost = 0 }
        war.addCard(brigand, war.me.handArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))

        assertTrue(node.actions.any { it.creator?.cardId == brigand.cardId })
        assertTrue(node.actions.none { it.javaClass.simpleName == "TurnOverAction" })
    }

    @Test
    fun `parachute brigand is not resurrected when the board is full`() {
        val war = testWar()
        repeat(war.me.playArea.maxSize) { index ->
            war.addCard(testCard("BOARD_$index"), war.me.playArea)
        }
        val brigand = testCard(PirateDemonHunterMctsExperimentModel.PARACHUTE_BRIGAND).apply { cost = 0 }
        war.addCard(brigand, war.me.handArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))

        assertTrue(node.actions.none { it.creator?.cardId == brigand.cardId })
    }

    @Test
    fun `root lethal gate exposes face attacks before nonlethal minion trades`() {
        val war = testWar()
        val rivalHero = testCard("LETHAL_RIVAL_HERO").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 4
            atc = 0
        }
        val rivalMinion = testCard("LETHAL_RIVAL_MINION").apply { health = 6; atc = 0 }
        val first = testCard("LETHAL_ATTACKER_ONE").apply { cost = 0; atc = 2; isExhausted = false }
        val second = testCard("LETHAL_ATTACKER_TWO").apply { cost = 0; atc = 2; isExhausted = false }
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(rivalMinion, war.rival.playArea)
        war.addCard(first, war.me.playArea)
        war.addCard(second, war.me.playArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))

        assertTrue(node.actions.any { it is AttackAction && it.targetIsHero })
        assertTrue(node.actions.none { it is AttackAction && !it.targetIsHero })
    }

    @Test
    fun `taunt prevents the lethal gate from claiming face damage`() {
        val war = testWar()
        val rivalHero = testCard("TAUNT_RIVAL_HERO").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 4
            atc = 0
        }
        val taunt = testCard("LETHAL_TAUNT").apply { health = 8; atc = 0; isTaunt = true }
        val attacker = testCard("TAUNT_ATTACKER").apply { cost = 0; atc = 4; isExhausted = false }
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(taunt, war.rival.playArea)
        war.addCard(attacker, war.me.playArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))

        assertTrue(node.actions.any { it is AttackAction && it.targetEntityId == taunt.entityId })
        assertTrue(node.actions.none { it is AttackAction && it.targetIsHero })
    }

    @Test
    fun `weapon attack contributes to team lethal face route`() {
        val war = testWar()
        val rivalHero = testCard("WEAPON_RIVAL_HERO").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 4
            atc = 0
        }
        val rivalMinion = testCard("WEAPON_RIVAL_MINION").apply { health = 6; atc = 0 }
        val attacker = testCard("WEAPON_ATTACKER").apply { cost = 0; atc = 2; isExhausted = false }
        val hero = testCard("WEAPON_HERO").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 30
            atc = 0
            isExhausted = false
        }
        val weapon = testCard("WEAPON_FOR_LETHAL").apply {
            cardType = CardTypeEnum.WEAPON
            atc = 2
            health = 2
        }
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(rivalMinion, war.rival.playArea)
        war.addCard(attacker, war.me.playArea)
        war.addCard(hero, war.me.playArea)
        war.addCard(weapon, war.me.playArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))

        assertTrue(node.actions.any { it is AttackAction && it.creator?.entityId == hero.entityId && it.targetIsHero })
        assertTrue(node.actions.none { it is AttackAction && !it.targetIsHero })
    }

    @Test
    fun `nonlethal total damage keeps normal minion target fallback`() {
        val war = testWar()
        val rivalHero = testCard("NONLETHAL_RIVAL_HERO").apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            health = 8
            atc = 0
        }
        val rivalMinion = testCard("NONLETHAL_RIVAL_MINION").apply { health = 6; atc = 0 }
        val attacker = testCard("NONLETHAL_ATTACKER").apply { cost = 0; atc = 2; isExhausted = false }
        war.addCard(rivalHero, war.rival.playArea)
        war.addCard(rivalMinion, war.rival.playArea)
        war.addCard(attacker, war.me.playArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))

        assertTrue(node.actions.any { it is AttackAction && it.targetEntityId == rivalMinion.entityId })
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

    private fun testMctsArg(experimentalSearch: Boolean = false): MCTSArg = MCTSArg(
        endMillisTime = Long.MAX_VALUE,
        turnCount = 1,
        turnFactor = 0.5,
        countPerTurn = 1,
        scoreCalculator = { 0.0 },
        enableMultiThread = false,
        decisionModel = PirateDemonHunterMctsExperimentModel,
        experimentalSearch = experimentalSearch,
    )

    private fun testCard(cardId: String): Card = Card(TestCardAction()).apply {
        entityId = cardId + "-test"
        this.cardId = cardId
        entityName = cardId
        cardType = CardTypeEnum.MINION
        cardRace = CardRaceEnum.PIRATE
        cost = 2
        atc = 2
        health = 2
        action.belongCard = this
    }
}
