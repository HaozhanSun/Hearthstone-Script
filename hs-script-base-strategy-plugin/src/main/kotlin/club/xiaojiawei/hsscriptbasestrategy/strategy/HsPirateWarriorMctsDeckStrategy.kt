package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.MctsRootSelectionPolicy
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptstrategysdk.deck.MCTSDeckStrategy

/** Released entry point for the isolated Pirate Warrior MCTS model. */
class HsPirateWarriorMctsDeckStrategy : MCTSDeckStrategy() {
    override fun name(): String = "海盗战 MCTS"

    override fun description(): String =
        "海盗战 MCTS：船载火炮 P0、首回合任务、宝藏经销商铺场与海盗光环奖励"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.CASUAL, RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.PRACTICE)

    override fun deckCode(): String = ""

    override fun id(): String = "e71234fa-8-pirate-warrior-mcts-9b1f-4d29-8f4f"

    override fun referWeight(): Boolean = true
    override fun referPowerWeight(): Boolean = true
    override fun referChangeWeight(): Boolean = true
    override fun referCardInfo(): Boolean = true

    override fun executeChangeCard(cards: HashSet<Card>) {
        val patches = cards.filter { PirateWarriorMctsModel.isCard(it, PirateWarriorMctsModel.PATCHES_THE_PIRATE) }
        cards.removeAll(patches.toSet())
        if (patches.isNotEmpty()) {
            log.info { "海盗战 MCTS：起手直接换掉海盗帕奇斯 count=${patches.size}" }
        }
    }

    override fun executeMCTSOutCard(war: War): List<MCTSArg> {
        DecisionTrace.record(
            war = war,
            event = "PIRATE_WARRIOR_MCTS_START",
            reason = "isolated model search started",
            rule = "SHIP_CANNON_P0>QUEST_T1>TREASURE_DISTRIBUTOR;PATCHES_BOTTOM",
            priority = 0,
        )
        log.info {
            "海盗战 MCTS：开始搜索 turn=${war.me.turn} mana=${war.me.usableResource} " +
                "hand=${war.me.handArea.cards.joinToString { it.cardId }} " +
                "rules=SHIP_CANNON_P0>QUEST_T1>TREASURE_DISTRIBUTOR;PATCHES_BOTTOM"
        }
        return listOf(
            MCTSArg(
                endMillisTime = System.currentTimeMillis() + 20_000L,
                turnCount = 1,
                turnFactor = 0.65,
                countPerTurn = 1_500,
                scoreCalculator = PirateWarriorMctsScoreCalculatorBuilder().build(),
                enableMultiThread = false,
                debugName = name(),
                decisionModel = PirateWarriorMctsModel,
                experimentalSearch = true,
                experimentalTurnBudgetMillis = 20_000L,
                experimentalActionBudgetMillis = 1_800L,
                rootSelectionPolicy = MctsRootSelectionPolicy.VISITS_THEN_VALUE,
            ),
        )
    }

    override fun executeDiscoverChooseCard(vararg cards: Card): Int =
        cards.indices.maxByOrNull { PirateWarriorMctsModel.discoverScore(cards[it]) } ?: 0
}
