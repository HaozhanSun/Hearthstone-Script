package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptstrategysdk.deck.MCTSDeckStrategy

/**
 * MCTS variant for the Pirate Demon Hunter deck.
 *
 * This is intentionally separate from [HsPirateDemonHunterDeckStrategy]: it
 * keeps the deck-specific mulligan, but uses the generic MCTS action search
 * and default battlefield score instead of the hard-coded play-order rules.
 * That makes it possible to compare the two strategies from the UI.
 */
class HsPirateDemonHunterMctsDeckStrategy : MCTSDeckStrategy() {

    private val pirateDemonHunterMulligan = HsPirateDemonHunterDeckStrategy()

    override fun name(): String = "海盗瞎 MCTS"

    override fun description(): String =
        "海盗瞎专用MCTS策略：使用海盗瞎换牌规则与专用动作时序模型决定出牌和攻击"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.CASUAL, RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.PRACTICE)

    override fun deckCode(): String = ""

    override fun id(): String = "e71234fa-7-pirate-demon-hunter-mcts-97e9-1f4e126cd33b"

    override fun executeChangeCard(cards: HashSet<Card>) {
        // Keep the same working Pirate Demon Hunter mulligan baseline so the
        // experiment compares play decisions rather than changing mulligan.
        pirateDemonHunterMulligan.executeChangeCard(cards)
    }

    override fun executeMCTSOutCard(war: War): List<MCTSArg> {
        val start = System.currentTimeMillis()
        // The released "海盗瞎 MCTS" must use the same deck-specific model as
        // the trial strategy.  The old generic model silently discarded opaque
        // Pirate DH actions (including VAC_925/EDR_840) and logged the fixed
        // order as disabled, which allowed EndTurn to win the root ranking.
        // Keep the bounded receding-horizon controller so every action is
        // replanned against the live Power.log state.
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

    override fun executeDiscoverChooseCard(vararg cards: Card): Int = 1
}
