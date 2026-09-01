package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
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
import kotlin.test.assertTrue

/**
 * Deterministic replay/evaluator output for the high-risk Pirate Warrior rules.
 * This intentionally evaluates the isolated model, not a live Hearthstone
 * client or an unverified parser transition.
 */
class PirateWarriorOfflineReplayTest {
    @Test
    fun `offline fixtures print model decision and evaluator status`() {
        val fixtures = loadFixtures()
        assertTrue(fixtures.isNotEmpty())

        fixtures.forEach { fixture ->
            assertTrue(fixture.evaluator in setOf("pass", "needs-review", "bug"))
            assertEquals("needs-review", fixture.runtimeReview)

            val evaluation = when (fixture.id) {
                "frontline-axe-kill" -> frontlineAxeKill()
                "frontline-axe-no-kill" -> frontlineAxeNoKill()
                "crowley-two-slots" -> crowleySlots(2)
                "crowley-three-slots" -> crowleySlots(3)
                "warrior-power-last" -> warriorPowerLast()
                "warrior-power-only" -> warriorPowerOnly()
                "hozen-other-pirates" -> hozenOtherPirates()
                else -> error("Unknown Pirate Warrior fixture: ${fixture.id}")
            }

            assertEquals(fixture.expected, evaluation.selected, fixture.id)
            assertTrue(evaluation.matches, "${fixture.id}: ${evaluation.reason}")
            println(
                "PIRATE_WARRIOR_REPLAY " +
                    "fixture=${fixture.id} " +
                    "strategy=${fixture.strategy} " +
                    "candidates=${evaluation.candidates.joinToString(",")} " +
                    "reason=${evaluation.reason} " +
                    "expected=${fixture.expected} " +
                    "selected=${evaluation.selected} " +
                    "evaluator=${fixture.evaluator} " +
                    "runtime_review=${fixture.runtimeReview}",
            )
        }
    }

    private fun frontlineAxeKill(): Evaluation {
        val war = frontlineAxeWar(heroHealth = 10, rivalHeroHealth = 30, minionHealth = 3)
        val actions = heroAttackActions(war)
        val minion = actions.first { hitsRivalMinion(it, war) }
        val face = actions.first { !hitsRivalMinion(it, war) }
        val minionLegal = PirateWarriorMctsModel.isActionLegal(minion, war)
        val faceLegal = PirateWarriorMctsModel.isActionLegal(face, war)
        val minionPrior = PirateWarriorMctsModel.actionPrior(minion, war)
        return Evaluation(
            selected = if (minionLegal && !faceLegal && minionPrior > 0.0) "AXE_MINION_KILL" else "BUG",
            candidates = listOf(
                "AXE_MINION_KILL:legal=$minionLegal:prior=$minionPrior",
                "AXE_HERO:legal=$faceLegal:prior=${PirateWarriorMctsModel.actionPrior(face, war)}",
            ),
            reason = "可击杀随从线是唯一合法且正 prior 的战斧攻击",
        )
    }

    private fun frontlineAxeNoKill(): Evaluation {
        val war = frontlineAxeWar(heroHealth = 10, rivalHeroHealth = 30, minionHealth = 7)
        war.addCard(testCard("READY_PIRATE", 1), war.me.playArea)
        val axeAction = heroAttackActions(war).first { hitsRivalMinion(it, war) }
        val legal = PirateWarriorMctsModel.isActionLegal(axeAction, war)
        val deferred = PirateWarriorMctsModel.isDeferredAction(axeAction, war)
        val prior = PirateWarriorMctsModel.actionPrior(axeAction, war)
        return Evaluation(
            selected = if (deferred && prior < 0.0) "DEFER_AXE" else "BUG",
            candidates = listOf("AXE_MINION_NONKILL:legal=$legal:deferred=$deferred:prior=$prior"),
            reason = "随从血量高于战斧攻击力，不兑现可靠抽牌效果，且有其他 Pirate 动作",
        )
    }

    private fun crowleySlots(freeSlots: Int): Evaluation {
        val war = testWar(turn = 2, mana = 5)
        repeat(7 - freeSlots) { index ->
            war.addCard(testCard("OCCUPIED_$freeSlots-$index", 1), war.me.playArea)
        }
        val crowley = testCard(PirateWarriorMctsModel.CAPTAIN_CROWLEY, 5)
        val action = PlayAction({}, {}, crowley)
        val legal = PirateWarriorMctsModel.isActionLegal(action, war)
        val prior = PirateWarriorMctsModel.actionPrior(action, war)
        return Evaluation(
            selected = if (legal) "CROWLEY_PLAY" else "CROWLEY_FILTERED",
            candidates = listOf("CAP_106:freeSlots=$freeSlots:legal=$legal:prior=$prior"),
            reason = "克罗雷的两个 token 需要至少三个可用场位",
        )
    }

    private fun warriorPowerLast(): Evaluation {
        val war = testWar(turn = 2, mana = 2)
        val power = testHeroPower()
        val minion = testCard("PLAYABLE_PIRATE", 1)
        war.addCard(power, war.me.playArea)
        war.addCard(minion, war.me.handArea)
        val powerAction = PowerAction({}, {}, power)
        val minionAction = PlayAction({}, {}, minion)
        val deferred = PirateWarriorMctsModel.isDeferredAction(powerAction, war)
        val powerPrior = PirateWarriorMctsModel.actionPrior(powerAction, war)
        val minionPrior = PirateWarriorMctsModel.actionPrior(minionAction, war)
        return Evaluation(
            selected = if (deferred && powerPrior < minionPrior) "DEFER_POWER" else "BUG",
            candidates = listOf(
                "HERO_POWER:deferred=$deferred:prior=$powerPrior",
                "PLAY_PIRATE:prior=$minionPrior",
            ),
            reason = "有可下海盗时 Armor Up 只是最后资源动作",
        )
    }

    private fun warriorPowerOnly(): Evaluation {
        val war = testWar(turn = 2, mana = 2)
        val power = testHeroPower()
        war.addCard(power, war.me.playArea)
        val action = PowerAction({}, {}, power)
        val deferred = PirateWarriorMctsModel.isDeferredAction(action, war)
        return Evaluation(
            selected = if (!deferred) "HERO_POWER" else "BUG",
            candidates = listOf("HERO_POWER:deferred=$deferred:prior=${PirateWarriorMctsModel.actionPrior(action, war)}"),
            reason = "没有其他有价值动作时不能把英雄技能 hard-ban",
        )
    }

    private fun hozenOtherPirates(): Evaluation {
        val war = testWar(turn = 2, mana = 3)
        val hozen = testCard(PirateWarriorMctsModel.HOZEN_ROUGHHOUSER, 3, 2).apply {
            entityId = "HOZEN_IN_HAND"
        }
        val otherPirate = testCard("OTHER_PIRATE", 1, 2).apply {
            entityId = "OTHER_PIRATE_ON_BOARD"
            health = 2
        }
        val otherHozen = testCard(PirateWarriorMctsModel.HOZEN_ROUGHHOUSER, 3, 2).apply {
            entityId = "SECOND_HOZEN_ON_BOARD"
            health = 4
        }
        val nonPirate = testCard("NON_PIRATE", 1, 2).apply {
            entityId = "NON_PIRATE_ON_BOARD"
            cardRace = CardRaceEnum.UNKNOWN
            health = 2
        }
        war.addCard(hozen, war.me.handArea)
        war.addCard(otherPirate, war.me.playArea)
        war.addCard(otherHozen, war.me.playArea)
        war.addCard(nonPirate, war.me.playArea)

        val play = hozen.action.generatePlayActions(war, war.me).single()
        val after = MonteCarloTreeNode(war, InitAction, testArg()).buildNextNode(play).state.war
        val pirateAfter = after.me.playArea.findByEntityId(otherPirate.entityId)
        val hozenAfter = after.me.playArea.findByEntityId(otherHozen.entityId)
        val nonPirateAfter = after.me.playArea.findByEntityId(nonPirate.entityId)
        val matches = pirateAfter?.atc == 3 && pirateAfter.health == 3 &&
            hozenAfter?.atc == 3 && hozenAfter.health == 5 &&
            nonPirateAfter?.atc == 2 && nonPirateAfter.health == 2
        return Evaluation(
            selected = if (matches) "HOZEN_OTHER_PIRATES_PLUS_ONE" else "BUG",
            candidates = listOf(
                "VAC_938:otherPirate=${pirateAfter?.atc}/${pirateAfter?.health}",
                "VAC_938:secondHozen=${hozenAfter?.atc}/${hozenAfter?.health}",
                "VAC_938:nonPirate=${nonPirateAfter?.atc}/${nonPirateAfter?.health}",
            ),
            reason = "只在 PlayAction 结果中物化当时其他场上 Pirate 的 +1/+1",
            matches = matches,
        )
    }

    private fun loadFixtures(): List<Fixture> =
        requireNotNull(javaClass.getResourceAsStream("/offline/pirate-warrior/fixtures.tsv"))
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val fields = line.split('|')
                require(fields.size == 6) { "Malformed fixture row: $line" }
                Fixture(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5])
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

    private fun frontlineAxeWar(heroHealth: Int, rivalHeroHealth: Int, minionHealth: Int): War {
        val war = testWar(turn = 2, mana = 4)
        war.addCard(testHero("MY_HERO", heroHealth, attack = 3), war.me.playArea)
        war.addCard(testCard(PirateWarriorMctsModel.FRONTLINE_AXE, 4, attack = 3).apply {
            cardType = CardTypeEnum.WEAPON
            cardRace = CardRaceEnum.UNKNOWN
            durability = 3
        }, war.me.playArea)
        war.addCard(testHero("RIVAL_HERO", rivalHeroHealth), war.rival.playArea)
        war.addCard(testCard("RIVAL_MINION", 1, attack = 1).apply {
            cardRace = CardRaceEnum.UNKNOWN
            health = minionHealth
        }, war.rival.playArea)
        return war
    }

    private fun heroAttackActions(war: War): List<AttackAction> =
        war.me.playArea.hero?.action?.generateAttackActions(war, war.me).orEmpty()

    private fun hitsRivalMinion(action: AttackAction, war: War): Boolean {
        val after = war.clone().also {
            PirateWarriorMctsModel.beforeSimulatedAction(it, action)
            action.simulate.accept(it)
        }
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

    private fun testHero(cardId: String, health: Int, attack: Int = 0): Card =
        testCard(cardId, cost = 0, attack = attack).apply {
            cardType = CardTypeEnum.HERO
            cardRace = CardRaceEnum.UNKNOWN
            this.health = health
        }

    private data class Fixture(
        val id: String,
        val focus: String,
        val strategy: String,
        val expected: String,
        val evaluator: String,
        val runtimeReview: String,
    )

    private data class Evaluation(
        val selected: String,
        val candidates: List<String>,
        val reason: String,
        val matches: Boolean = selected != "BUG",
    )
}
