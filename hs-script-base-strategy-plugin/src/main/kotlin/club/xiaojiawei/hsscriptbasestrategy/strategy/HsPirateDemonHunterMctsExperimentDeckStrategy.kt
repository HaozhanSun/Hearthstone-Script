package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptstrategysdk.deck.MCTSDeckStrategy

/**
 * Receding-horizon MCTS for the 40-card Pirate Demon Hunter list.
 *
 * It deliberately has its own name and ID so the existing generic
 * [HsPirateDemonHunterMctsDeckStrategy] remains available for A/B testing.
 */
class HsPirateDemonHunterMctsExperimentDeckStrategy : MCTSDeckStrategy() {

    private val pirateDemonHunterMulligan = HsPirateDemonHunterDeckStrategy()

    override fun name(): String = "海盗瞎MCTS试验"

    override fun description(): String =
        "海盗瞎专用试验MCTS：建场优先、炮塔先下、船长/猴孙无同伴降权，放大战刃与盲眼法官按手牌资源择时"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.CASUAL, RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.PRACTICE)

    override fun deckCode(): String = ""

    override fun id(): String = "e71234fa-7-pirate-demon-hunter-mcts-trial-97e9-1f4e126cd33b"

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
                decisionModel = PirateDemonHunterMctsExperimentModel,
                experimentalSearch = true,
                experimentalTurnBudgetMillis = 20_000L,
                experimentalActionBudgetMillis = 1_800L,
            ),
        )
    }

    override fun executeDiscoverChooseCard(vararg cards: Card): Int =
        cards.indices.maxByOrNull { PirateDemonHunterMctsExperimentModel.discoverScore(cards[it]) } ?: 0
}
