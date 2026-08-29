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
import club.xiaojiawei.hsscriptcardsdk.mcts.CardTimingPolicy
import club.xiaojiawei.hsscriptcardsdk.mcts.MonteCarloTreeNode
import club.xiaojiawei.hsscriptcardsdk.mcts.MonteCarloTreeSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PirateDemonHunterMctsExperimentModelTest {

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
        assertEquals("海盗瞎MCTS试验", HsPirateDemonHunterMctsExperimentDeckStrategy().name())
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
    fun `released pirate mcts strategy wires the dedicated model and live replanning`() {
        val arg = HsPirateDemonHunterMctsDeckStrategy().executeMCTSOutCard(testWar()).single()

        assertTrue(arg.experimentalSearch)
        assertTrue(arg.decisionModel === PirateDemonHunterMctsExperimentModel)
    }

    @Test
    fun `global plan strategy opts into plan selection without changing the baseline strategy`() {
        val baseline = HsPirateDemonHunterMctsDeckStrategy().executeMCTSOutCard(testWar()).single()
        val global = HsPirateDemonHunterMctsGlobalPlanDeckStrategy().executeMCTSOutCard(testWar()).single()

        assertEquals(MctsRootSelectionPolicy.VISITS_THEN_VALUE, baseline.rootSelectionPolicy)
        assertEquals(MctsRootSelectionPolicy.GLOBAL_TURN_PLAN, global.rootSelectionPolicy)
        assertTrue(global.decisionModel === PirateDemonHunterMctsGlobalPlanModel)
        assertEquals("海盗瞎MCTS全局规划试验", HsPirateDemonHunterMctsGlobalPlanDeckStrategy().name())
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
