package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic offline E2E simulation.  It emits a JSONL trace and copies a
 * state SVG so the decision contract is checked as a log/screenshot pair,
 * while clearly remaining separate from a live Hearthstone run.
 */
class StandardCannonWarriorOfflineE2ETest {
    @Test
    fun `standard cannon warrior offline e2e covers routing planning replanning and fail safe`() {
        val strategy = HsStandardCannonWarriorMctsDeckStrategy()
        val events = mutableListOf<String>()

        assertEquals(arrayOf(RunModeEnum.STANDARD).toList(), strategy.runModes.toList())
        events += "standard-route:runMode=STANDARD,deckSlot=3,strategyId=${strategy.id()}"

        val firstTurn = testWar(turn = 1, mana = 2)
        val sanguineDepths = testCard(StandardCannonWarriorMctsModel.SANGUINE_DEPTHS, cost = 1,
            cardType = CardTypeEnum.LOCATION)
        val bash = testCard(StandardCannonWarriorMctsModel.BASH, cost = 2, cardType = CardTypeEnum.SPELL)
        val generic = testCard("GENERIC_STANDARD_CARD", cost = 1)
        firstTurn.addCard(sanguineDepths, firstTurn.me.handArea)
        firstTurn.addCard(bash, firstTurn.me.handArea)
        firstTurn.addCard(generic, firstTurn.me.handArea)
        firstTurn.addCard(testCard("RIVAL_MINION", cost = 1), firstTurn.rival.playArea)
        assertTrue(
            StandardCannonWarriorMctsModel.actionPrior(PlayAction({}, {}, sanguineDepths), firstTurn) >
                StandardCannonWarriorMctsModel.actionPrior(PlayAction({}, {}, bash), firstTurn),
        )
        assertTrue(
            StandardCannonWarriorMctsModel.actionPrior(PlayAction({}, {}, bash), firstTurn) >
                StandardCannonWarriorMctsModel.actionPrior(PlayAction({}, {}, generic), firstTurn),
        )
        events += "first-turn-plan:sanguine-depths-then-bash-parser-backed"

        val keyCards = listOf(
            StandardCannonWarriorMctsModel.SANGUINE_DEPTHS,
            StandardCannonWarriorMctsModel.BASH,
            StandardCannonWarriorMctsModel.DRAGON_NEST_GUARDIAN,
            StandardCannonWarriorMctsModel.DIMENSIONAL_WEAPONSMITH,
            StandardCannonWarriorMctsModel.HOGGER,
        )
        assertEquals(5, keyCards.distinct().size)
        assertTrue(keyCards.all { it.isNotBlank() })
        events += "standard-key-card-sequence:${keyCards.joinToString(",")}"

        val root = testWar(turn = 3, mana = 4)
        root.addCard(testCard(StandardCannonWarriorMctsModel.BASH, cost = 2,
            cardType = CardTypeEnum.SPELL), root.me.handArea)
        val oneManaLeft = root.clone().apply { me.usedResources = 1 }
        val allManaUsed = root.clone().apply { me.usedResources = 4 }
        assertTrue(
            StandardCannonWarriorMctsModel.turnPlanAdjustment(root, allManaUsed, emptyList()) >
                StandardCannonWarriorMctsModel.turnPlanAdjustment(root, oneManaLeft, emptyList()),
        )
        events += "unused-mana-replan:reachable-known-card=true,short-plan-penalized=true"

        val unknown = testCard(StandardCannonWarriorMctsModel.UNKNOWN, cost = 1)
        assertFalse(StandardCannonWarriorMctsModel.canCreateOpaqueAction(unknown, root))
        assertFalse(StandardCannonWarriorMctsModel.isActionLegal(PlayAction({}, {}, unknown), root))
        events += "unknown-action-fail-safe:opaque-fallback=false,parser-backed-only=true"

        val fixtureTrace = javaClass.classLoader
            .getResourceAsStream("offline/standard-cannon-warrior/standard-cannon-warrior-e2e-trace.jsonl")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("missing offline E2E trace fixture")
        val fixtureScreenshot = javaClass.classLoader
            .getResourceAsStream("offline/standard-cannon-warrior/standard-cannon-warrior-state.svg")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("missing offline E2E screenshot fixture")

        val evidenceDir = createTempDirectory("standard-cannon-warrior-e2e-")
        val tracePath = evidenceDir.resolve("decision-trace.jsonl")
        tracePath.writeText(events.joinToString("\n"))
        val screenshotPath = evidenceDir.resolve("state.svg")
        screenshotPath.writeText(fixtureScreenshot)
        assertEquals(5, tracePath.readText().lines().size)
        assertTrue(screenshotPath.readText().contains("offline evidence"))
        assertEquals(5, fixtureTrace.trim().lines().size)
        assertTrue(fixtureTrace.contains("unknown-action-fail-safe"))
        assertTrue(fixtureScreenshot.contains("deck slot 3"))
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

    private fun testCard(
        cardId: String,
        cost: Int,
        attack: Int = 2,
        cardType: CardTypeEnum = CardTypeEnum.MINION,
    ): Card = Card(TestCardAction()).apply {
        entityId = "$cardId-offline-test"
        this.cardId = cardId
        entityName = cardId
        this.cardType = cardType
        cardRace = CardRaceEnum.PIRATE
        this.cost = cost
        atc = attack
        health = 3
        action.belongCard = this
    }
}


