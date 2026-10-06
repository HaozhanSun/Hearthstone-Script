package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.MctsRootSelectionPolicy
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptstrategysdk.deck.MCTSDeckStrategy
import club.xiaojiawei.hsscriptstrategysdk.deck.MctsDiscoverCandidateOverride

/** Selectable entry point for the offline-first Elemental Mage MCTS. */
class HsElementalMageMctsDeckStrategy : MCTSDeckStrategy() {
    override fun name(): String = ElementalMageMctsStrategyVersion.displayName()

    override fun description(): String =
        "元素法 MCTS V${ElementalMageMctsStrategyVersion.REVISION}：三费起每回合优先保持元素链，阳炎耀斑费用≤2且最后使用"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.WILD)

    override fun deckCode(): String = ""

    override fun id(): String = "e71234fa-12-elemental-mage-mcts-v1-2-9b1f-4d29-8f4f"

    override fun referWeight(): Boolean = true
    override fun referPowerWeight(): Boolean = true
    override fun referChangeWeight(): Boolean = true
    override fun referCardInfo(): Boolean = true

    override fun executeChangeCard(cards: HashSet<Card>) {
        cards.removeIf { card -> card.cost >= 4 && !ElementalMageMctsModel.isElemental(card) }
    }

    override fun executeMCTSOutCard(war: War): List<MCTSArg> {
        ElementalMageMctsModel.observeLiveDecision(war)
        return listOf(
            MCTSArg(
            endMillisTime = System.currentTimeMillis() + 20_000L,
            turnCount = 1,
            turnFactor = 0.65,
            countPerTurn = 1_500,
            scoreCalculator = ElementalMageMctsScoreCalculatorBuilder().build(),
            enableMultiThread = false,
            debugName = name(),
            decisionModel = ElementalMageMctsModel,
            experimentalSearch = true,
            experimentalTurnBudgetMillis = 20_000L,
            experimentalActionBudgetMillis = 1_800L,
            rootSelectionPolicy = MctsRootSelectionPolicy.GLOBAL_TURN_PLAN,
            ),
        )
    }

    override fun executeDiscoverChooseCard(vararg cards: Card): Int =
        cards.indices.maxByOrNull { ElementalMageMctsModel.discoverScore(cards[it]) } ?: 0

    override fun discoverCandidateOverride(
        cards: List<Card>,
        hand: List<Card>,
        nextTurnMana: Int,
        nextTurnNumber: Int,
    ): MctsDiscoverCandidateOverride? = ElementalMageMctsModel.discoverChainOverride(
        offered = cards,
        hand = hand,
        nextTurnMana = nextTurnMana,
        nextTurnNumber = nextTurnNumber,
    )
}
