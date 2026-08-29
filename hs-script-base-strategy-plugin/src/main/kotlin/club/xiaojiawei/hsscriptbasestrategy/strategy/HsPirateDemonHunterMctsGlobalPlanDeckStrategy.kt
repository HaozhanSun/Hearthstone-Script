package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.MctsRootSelectionPolicy
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptstrategysdk.deck.MCTSDeckStrategy

/**
 * A/B-test strategy for global turn planning. It searches a complete
 * discovered turn plan, then the executor still dispatches one action and
 * re-reads the live game before continuing.
 */
class HsPirateDemonHunterMctsGlobalPlanDeckStrategy : MCTSDeckStrategy() {

    private val pirateDemonHunterMulligan = HsPirateDemonHunterDeckStrategy()

    override fun name(): String = "海盗瞎MCTS全局规划试验"

    override fun description(): String =
        "海盗瞎全局规划MCTS：按整回合可达资源评估动作序列，执行时仍逐步重扫描"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.CASUAL, RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.PRACTICE)

    override fun deckCode(): String = ""

    override fun id(): String = "e71234fa-7-pirate-demon-hunter-mcts-global-plan-2f0f-4d4d-a5cf"

    override fun referWeight(): Boolean = true

    override fun referPowerWeight(): Boolean = true

    override fun referChangeWeight(): Boolean = true

    override fun referCardInfo(): Boolean = true

    override fun executeChangeCard(cards: HashSet<Card>) {
        pirateDemonHunterMulligan.executeChangeCard(cards)
    }

    override fun executeMCTSOutCard(war: War): List<MCTSArg> {
        val start = System.currentTimeMillis()
        return listOf(
            MCTSArg(
                endMillisTime = start + 20_000L,
                turnCount = 1,
                turnFactor = 0.65,
                countPerTurn = 1_500,
                scoreCalculator = PirateDemonHunterMctsTrialScoreCalculatorBuilder().buildTrial(),
                enableMultiThread = false,
                debugName = name(),
                decisionModel = PirateDemonHunterMctsGlobalPlanModel,
                experimentalSearch = true,
                experimentalTurnBudgetMillis = 20_000L,
                experimentalActionBudgetMillis = 1_800L,
                rootSelectionPolicy = MctsRootSelectionPolicy.GLOBAL_TURN_PLAN,
            ),
        )
    }

    override fun executeDiscoverChooseCard(vararg cards: Card): Int =
        cards.indices.maxByOrNull { PirateDemonHunterMctsExperimentModel.discoverScore(cards[it]) } ?: 0
}
