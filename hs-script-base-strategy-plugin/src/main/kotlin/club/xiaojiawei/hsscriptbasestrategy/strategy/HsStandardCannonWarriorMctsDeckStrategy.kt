package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.MctsRootSelectionPolicy
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptstrategysdk.deck.MCTSDeckStrategy

/** Independent Standard entry point for the screenshot-derived Warrior deck. */
class HsStandardCannonWarriorMctsDeckStrategy : MCTSDeckStrategy() {
    override fun name(): String = "Standard Cannon Warrior V1.1"

    override fun description(): String =
        "Standard Cannon Warrior V1.1：精确截图卡组、已确认 ID 优先、未知卡牌 parser-backed fail-closed、发现选择规则 V1.1"

    override fun getRunMode(): Array<RunModeEnum> = arrayOf(RunModeEnum.STANDARD)

    override fun deckCode(): String = ""

    override fun id(): String = "e71234fa-9-standard-cannon-warrior-v1-0-9b1f-4d29-8f4f"

    override fun referWeight(): Boolean = true
    override fun referPowerWeight(): Boolean = true
    override fun referChangeWeight(): Boolean = true
    override fun referCardInfo(): Boolean = true

    override fun executeChangeCard(cards: HashSet<Card>) {
        cards.removeIf { card -> card.cost >= 5 }
    }

    override fun executeMCTSOutCard(war: War): List<MCTSArg> {
        log.info {
            "Standard Cannon Warrior V1.1：开始搜索 turn=${war.me.turn} " +
                "mana=${war.me.usableResource} confirmedIds=${StandardCannonWarriorMctsModel.confirmedCardIds.size}"
        }
        return listOf(
            MCTSArg(
                endMillisTime = System.currentTimeMillis() + 20_000L,
                turnCount = 1,
                turnFactor = 0.65,
                countPerTurn = 1_500,
                scoreCalculator = StandardCannonWarriorScoreCalculatorBuilder().build(),
                enableMultiThread = false,
                debugName = name(),
                decisionModel = StandardCannonWarriorMctsModel,
                experimentalSearch = true,
                experimentalTurnBudgetMillis = 20_000L,
                experimentalActionBudgetMillis = 1_800L,
                rootSelectionPolicy = MctsRootSelectionPolicy.GLOBAL_TURN_PLAN,
            ),
        )
    }

    override fun executeDiscoverChooseCard(vararg cards: Card): Int =
        cards.indices.maxByOrNull { StandardCannonWarriorMctsModel.discoverScore(cards[it]) } ?: 0
}


