package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.TurnOverAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.area.HandArea
import club.xiaojiawei.hsscriptcardsdk.util.CardUtil
import java.util.*
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 蒙特卡洛树节点
 * @author 肖嘉威
 * @date 2025/1/10 16:27
 */
class MonteCarloTreeNode(
    war: War,
    action: Action,
    val arg: MCTSArg,
    var parent: MonteCarloTreeNode? = null,
    private val previousWar: War? = null,
    private val simulationResult: MctsDecisionModel.SimulationResult = MctsDecisionModel.SimulationResult(),
    simulatedSummons: List<CardTriggerSimulator.TriggeredSummon>? = null,
) {

    /**
     * 应用的动作：父节点应用了此动作变成当前节点
     */
    val applyAction: Action = action

    /**
     * 所有子节点
     */
    val children: MutableList<MonteCarloTreeNode> by lazy { mutableListOf() }

    /**
     * 当前状态
     */
    val state: State = State(war, arg, simulationResult)

    /** Passive effects applied while building this simulated node. */
    val simulatedFreeSummons: List<CardTriggerSimulator.TriggeredSummon> =
        simulatedSummons ?: emptyList()

    // CardTriggerSimulator may add a free hand summon before actions are
    // generated. Apply dynamic reductions after that summon so Zilliax also
    // sees cards released by effects such as 空降歹徒.
    @Suppress("UNUSED_VARIABLE")
    private val simulatedDynamicCostReductions =
        if (arg.decisionModel == null) previousWar?.let { CardTimingPolicy.applySimulatedReductions(it, war) } else null

    /**
     * 当前所有可执行的动作
     */
    val actions: List<Action> = generateActions(war)

    /**
     * 存储action是否添加至子节点
     */
    private var actionsExpandedFlag: BitSet = BitSet(actions.size)

    /**
     * 根据指定状态生成所有可能的动作
     */
    private fun generateActions(war: War): MutableList<Action> {
        val result = mutableListOf<Action>()
        if (applyAction !== TurnOverAction) {
            val me = war.me
            val handArea = me.handArea
            val playArea = me.playArea
            result.add(TurnOverAction)
            for (card in handArea.cards) {
                val shouldDefer = arg.decisionModel?.shouldDefer(card, war)
                    ?: CardTimingPolicy.shouldDefer(card, war)
                if (shouldDefer) {
                    if (parent == null && arg.debugName.isNotBlank()) {
                        log.info {
                            "MCTS_DEBUG_ACTION_FILTER strategy=${arg.debugName} " +
                                "action=打出(${card.cardId.ifBlank { card.entityName }}) " +
                                "reason=动态减费牌延后，先完成其他手牌动作和攻击"
                        }
                    }
                    continue
                }
                if (card.isCoinCard && !hasCoinPayoff(war)) {
                    if (parent == null && arg.debugName.isNotBlank()) {
                        log.info {
                            "MCTS_DEBUG_ACTION_FILTER strategy=${arg.debugName} " +
                                "action=打出(${card.cardId.ifBlank { card.entityName }}) " +
                                "reason=硬币不能解锁当前不可用的手牌或英雄技能"
                        }
                    }
                    continue
                }
                if (!card.isUncertain && me.usableResource >= card.cost && (!playArea.isFull || card.cardType === CardTypeEnum.HERO || card.cardType === CardTypeEnum.SPELL || card.cardType === CardTypeEnum.WEAPON)) {
                    val playActions = card.action.generatePlayActions(war, me)
                    result.addAll(playActions)
                    if (playActions.isEmpty() && arg.decisionModel?.canCreateOpaqueAction(card, war) == true) {
                        result.add(createOpaquePlayAction(card))
                    }
                }
            }
            for (card in playArea.cards) {
                if (card.canAttack()) {
                    result.addAll(card.action.generateAttackActions(war, me))
                } else if (card.canPower()) {
                    val powerActions = card.action.generatePowerActions(war, me)
                    result.addAll(powerActions)
                    if (powerActions.isEmpty() && arg.decisionModel?.canCreateOpaquePowerAction(card, war) == true) {
                        result.add(createOpaquePowerAction(card))
                    }
                }
            }
            playArea.hero?.let { myHero ->
                if (myHero.canAttack()) {
                    result.addAll(myHero.action.generateAttackActions(war, me))
                }
            }
            playArea.power?.let { myPower ->
                if (me.usableResource >= myPower.cost && myPower.canPower()) {
                    result.addAll(myPower.action.generatePowerActions(war, me))
                }
            }
        }
        val mandatoryActions: List<Action> = arg.decisionModel
            ?.let { model -> result.filter { model.isMandatoryAction(it, war) } }
            ?: emptyList()
        if (mandatoryActions.isNotEmpty()) return mandatoryActions.toMutableList()

        val deferredActions = arg.decisionModel
            ?.let { model -> result.filterNot { model.isDeferredAction(it, war) } }
            ?: result
        val filteredActions = if (deferredActions.isNotEmpty()) deferredActions else result
        // In experimental/receding-horizon MCTS, EndTurn is a terminal
        // control action, not a peer of a still-legal card, attack, or power.
        // Keeping it in the root set lets UCT end the turn early; the live
        // guard then sees the real action and exhausts its replan budget.
        return if (arg.experimentalSearch && filteredActions.any { it !== TurnOverAction }) {
            filteredActions.filterNot { it === TurnOverAction }.toMutableList()
        } else {
            filteredActions.toMutableList()
        }
    }

    /**
     * Coin is useful here only as a one-mana conversion that immediately
     * unlocks another legal action. This prevents MCTS from selecting
     * Coin -> one-mana hero power when it could simply use that power without
     * consuming Coin, while preserving Coin when it enables a two-mana card
     * or a two-mana hero power.
     */
    private fun hasCoinPayoff(war: War): Boolean {
        val me = war.me
        val currentMana = me.usableResource
        val coinMana = currentMana + 1
        val boardFull = me.playArea.isFull
        val handPayoff = me.handArea.cards.any { card ->
            !card.isUncertain &&
                !card.isCoinCard &&
                !CardTimingPolicy.shouldDefer(card, war) &&
                card.cost > currentMana &&
                card.cost <= coinMana &&
                (!boardFull || card.cardType === CardTypeEnum.HERO || card.cardType === CardTypeEnum.SPELL || card.cardType === CardTypeEnum.WEAPON)
        }
        val powerPayoff = me.playArea.power?.let { power ->
            !power.isExhausted && power.cost > currentMana && power.cost <= coinMana && power.canPower()
        } == true
        return handPayoff || powerPayoff
    }

    /**
     * A conservative fallback for a collectible card whose parser has no
     * specialized action. The real executor still calls the card's real
     * CardAction; the simulator only spends mana and removes the card. The
     * experimental model marks the resulting node as requiring a re-plan.
     */
    private fun createOpaquePlayAction(card: Card): PlayAction {
        val entityId = card.entityId
        return PlayAction(
            { newWar ->
                newWar.cardMap[entityId]?.action?.power()
            },
            { newWar ->
                val current = newWar.cardMap[entityId]
                if (current != null) {
                    current.area.player.usedResources += current.cost.coerceAtLeast(0)
                    val removed = current.area.removeByEntityId(entityId)
                    if (removed != null && removed.cardType !== CardTypeEnum.SPELL) {
                        CardUtil.handleCardExhaustedWhenIntoPlayArea(removed)
                        newWar.me.playArea.safeAdd(removed)
                    }
                }
            },
            card,
            true,
        )
    }

    /**
     * Fallback for a playable location whose card database entry has no
     * specialized generatePowerActions implementation. The live executor
     * still invokes the real CardAction; the simulator only consumes the
     * location activation and marks its cooldown. Deck-specific hooks model
     * the actual summon/effect and may force the next action in the chain.
     */
    private fun createOpaquePowerAction(card: Card): PowerAction {
        val entityId = card.entityId
        return PowerAction(
            { newWar ->
                newWar.cardMap[entityId]?.action?.power()
            },
            { newWar ->
                newWar.cardMap[entityId]?.let { current ->
                    current.isLocationActionCooldown = true
                }
            },
            card,
            true,
        )
    }

    /**
     * 在当前节点状态下构建下一个节点
     */
    fun buildNextNode(
        action: Action,
        arg: MCTSArg = this.arg,
        cloneWar: Boolean = true
    ): MonteCarloTreeNode {
        val beforeWar = if (cloneWar) state.war else state.war.clone()
        val newWar = if (cloneWar) state.war.clone() else state.war
//        新战局应用旧动作
        var result = arg.decisionModel?.beforeSimulatedAction(newWar, action)
            ?: MctsDecisionModel.SimulationResult()
        action.simulate.accept(newWar)

        val simulatedSummons = if (
            action is PlayAction &&
            action.creator != null &&
            action.creator!!.area is HandArea &&
            !action.recalculate
        ) {
            CardTriggerSimulator.simulateAfterPlay(newWar, action.creator!!)
        } else {
            emptyList()
        }
        val afterResult = arg.decisionModel?.afterSimulatedAction(beforeWar, newWar, action)
            ?: MctsDecisionModel.SimulationResult()
        result = MctsDecisionModel.SimulationResult(
            expectedReward = result.expectedReward + afterResult.expectedReward,
            stopRollout = result.stopRollout || afterResult.stopRollout,
        )
        val nextNode = MonteCarloTreeNode(newWar, action, arg, this, beforeWar, result, simulatedSummons)
        return nextNode
    }

    /**
     * 扩展动作至树中
     * @return 扩展后的新节点，为null表示扩展失败
     */
    fun expand(action: Action, arg: MCTSArg = this.arg): MonteCarloTreeNode? {
        val index = actions.indexOf(action)
        if (index >= 0 && !isExpanded(index)) {
            val nextNode = buildNextNode(action, arg)
            this.actionsExpandedFlag[index] = true
            this.children.add(nextNode)
            return nextNode
        }
        return null
    }

    /**
     * 是否已扩展
     */
    fun isExpanded(index: Int): Boolean {
        if (index >= 0 && index < actions.size) {
            return actionsExpandedFlag[index]
        }
        return true
    }

    /**
     * 是否已完全扩展
     */
    fun isFullExpanded(): Boolean {
        return actions.size == children.size
    }

    /**
     * 获取未扩展的动作
     */
    fun getUnExpanded(): MutableList<Action> {
        val result = mutableListOf<Action>()
        for ((index, action) in actions.withIndex()) {
            if (!isExpanded(index)) {
                result.add(action)
            }
        }
        return result
    }

    /**
     * 是否为叶子节点，即没有任何动作
     */
    fun isLeaf(): Boolean {
        return actions.isEmpty()
    }

    /**
     * 是否为结束节点
     */
    fun isEnd(): Boolean {
        return state.isEnd || state.stopRollout || isLeaf()
    }

    class State(
        val war: War,
        private val arg: MCTSArg,
        private val simulationResult: MctsDecisionModel.SimulationResult,
    ) {

        val score: Double by lazy { calcScore(war, arg) }

        var winCount: Int = 0

        var visitCount: Int = 0

        var valueSum: Double = 0.0

        var lastWin: Boolean = false

        val stopRollout: Boolean = simulationResult.stopRollout

        val isEnd = war.isEnd()

        fun update(win: Boolean?, reward: Double? = null) {
            visitCount++
            val isWin = win?.let {
                lastWin = it
                it
            } ?: lastWin
            if (isWin) {
                winCount++
            }
            valueSum += reward ?: if (isWin) 1.0 else 0.0
        }

        fun averageValue(): Double = if (visitCount == 0) 0.0 else valueSum / visitCount.toDouble()

        fun calcUCB(totalCount: Int, c: Double = 2.0): Double {
            return if (visitCount == 0)
                Int.MAX_VALUE.toDouble()
            else if (arg.experimentalSearch)
                averageValue() + sqrt(c * ln(totalCount.coerceAtLeast(1).toDouble()) / visitCount.toDouble())
            else
                winCount / visitCount + sqrt(c * ln(totalCount.toDouble()) / visitCount.toDouble())
        }

        private fun calcScore(war: War, arg: MCTSArg): Double {
            val currentScore = arg.scoreCalculator.apply(war) + simulationResult.expectedReward +
                (arg.decisionModel?.scoreAdjustment(war) ?: 0.0)
            val surplusTurn = max(arg.turnCount - 1, 0)
//        判断是否需要进行反演
            if (surplusTurn > 0) {
                val inverseArg = MCTSArg(
                    arg.endMillisTime,
                    surplusTurn,
                    arg.turnFactor * arg.turnFactor,
                    (arg.countPerTurn * arg.turnFactor).toInt(),
                    arg.scoreCalculator,
                    arg.enableMultiThread,
                    arg.debugName,
                    arg.decisionModel,
                    arg.experimentalSearch,
                    arg.experimentalTurnBudgetMillis,
                    arg.experimentalActionBudgetMillis,
                )
                val inverseWar = war.clone()
                inverseWar.me.apply {
                    playArea.hero?.atc = 0
//                triggerTurnEnd内部可能修改cards，使用副本遍历
                    val cardCopy = playArea.cards.toList()
                    for (card in cardCopy) {
                        if (card.isAlive()) {
                            card.action.triggerTurnEnd(war)
                        }
                    }
                }
                inverseWar.exchangePlayer()
                inverseWar.currentPlayer = inverseWar.me
                inverseWar.me.apply {
                    //            重置战场疲劳
                    for (card in playArea.cards) {
                        card.resetExhausted()
                        card.numTurnsInPlay++
                    }
                    for (card in handArea.cards) {
                        card.numTurnsInHand++
                    }
                    playArea.hero?.resetExhausted()
                    playArea.power?.resetExhausted()
                    playArea.weapon?.resetExhausted()
//                triggerTurnStart内部可能修改cards，使用副本遍历
                    val cardCopy = playArea.cards.toList()
                    for (card in cardCopy) {
                        if (card.isAlive()) {
                            card.action.triggerTurnStart(war)
                        }
                    }
                }

                val bestActions =
                    MonteCarloTreeSearch(maxDepth = MCTS_DEFAULT_DEPTH + 5).searchBestNode(inverseWar, inverseArg)
                return if (bestActions.isEmpty()) {
                    currentScore
                } else {
                    currentScore - bestActions.last().state.score * arg.turnFactor
                }
            } else {
                return currentScore
            }
        }

    }

}

