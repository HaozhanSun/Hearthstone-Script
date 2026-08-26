package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.InitAction
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
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
        assertEquals(1, afterFirstActivation.actions.size)
        assertTrue(afterFirstActivation.actions.single() is AttackAction)
        assertEquals(hero.entityId, afterFirstActivation.actions.single().creator?.entityId)

        val afterHeroAttack = afterFirstActivation.buildNextNode(afterFirstActivation.actions.single())
        assertEquals(1, afterHeroAttack.actions.size)
        assertTrue(afterHeroAttack.actions.single() is PowerAction)
        assertEquals(cliffside.entityId, afterHeroAttack.actions.single().creator?.entityId)
    }

    @Test
    fun `released pirate mcts strategy wires the dedicated model and live replanning`() {
        val arg = HsPirateDemonHunterMctsDeckStrategy().executeMCTSOutCard(testWar()).single()

        assertTrue(arg.experimentalSearch)
        assertTrue(arg.decisionModel === PirateDemonHunterMctsExperimentModel)
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
    fun `experimental mcts does not expose end turn beside a legal action`() {
        val war = testWar()
        val ordinary = testCard("ORDINARY_PIRATE").apply { cost = 1 }
        war.addCard(ordinary, war.me.handArea)

        val node = MonteCarloTreeNode(war, InitAction, testMctsArg(experimentalSearch = true))

        assertTrue(node.actions.any { it.creator?.cardId == ordinary.cardId })
        assertTrue(node.actions.none { it.javaClass.simpleName == "TurnOverAction" })
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
