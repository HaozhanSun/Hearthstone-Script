package club.xiaojiawei.hsscriptcardsdk.bean

import java.util.function.Function
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsDecisionModel

/**
 * @author 肖嘉威
 * @date 2025/1/15 10:59
 */

typealias ScoreCalculator = Function<War, Double>

data class MCTSArg(
    /**
     * 结束时间
     */
    val endMillisTime: Long,
    /**
     * 模拟的回合数
     */
    val turnCount: Int,
    /**
     * 回合因子：小于1时表示越后面的回合的影响越小，反之越大，[turnCount]大于1时生效
     */
    val turnFactor: Double,
    /**
     * 每回合模拟的次数
     */
    val countPerTurn: Int,
    /**
     * 评分计算器，(输入游戏状态，返回游戏状态的评分，一个好的评分系统应该至少考虑战场和手牌)
     */
    val scoreCalculator: ScoreCalculator,
    /**
     * 开启多线程计算
     */
    val enableMultiThread: Boolean,
    /**
     * Optional human-readable label used to connect MCTS engine diagnostics
     * back to the selected deck strategy.
     */
    val debugName: String = "",
    /** Optional deck-specific simulation model. Null preserves legacy behavior. */
    val decisionModel: MctsDecisionModel? = null,
    /** Enables the isolated experimental UCT/value-search branch. */
    val experimentalSearch: Boolean = false,
    /** Maximum wall-clock time for the experimental turn controller. */
    val experimentalTurnBudgetMillis: Long = 20_000L,
    /** Maximum wall-clock time for one experimental re-plan. */
    val experimentalActionBudgetMillis: Long = 1_800L,
)
