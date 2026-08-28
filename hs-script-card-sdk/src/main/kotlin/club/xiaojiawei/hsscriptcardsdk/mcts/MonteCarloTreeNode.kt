package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
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
        val rootScan = if (parent == null && arg.debugName.isNotBlank()) mutableListOf<Map<String, Any?>>() else null

        fun addScan(details: Map<String, Any?>) {
            rootScan?.add(details)
        }

        fun cardDescription(card: Card): String =
            "${card.cardId.ifBlank { "NO_ID" }}:${card.entityName.ifBlank { "UNKNOWN" }}"

        fun actionDescription(action: Action): String {
            if (action === TurnOverAction) return "结束回合"
            val card = action.creator?.let(::cardDescription) ?: "NO_CREATOR"
            val kind = when (action) {
                is PlayAction -> "PLAY"
                is AttackAction -> "ATTACK"
                is PowerAction -> "POWER"
                else -> action.javaClass.simpleName
            }
            return "$kind($card,entity=${action.creator?.entityId ?: ""})"
        }

        fun scanCard(
            card: Card,
            outcome: String,
            reason: String,
            rawPlayActions: Int? = null,
            addedActions: Int = 0,
            opaqueFallback: Boolean = false,
        ) {
            addScan(
                linkedMapOf(
                    "kind" to "HAND_CARD",
                    "cardId" to card.cardId,
                    "name" to card.entityName,
                    "entityId" to card.entityId,
                    "type" to card.cardType.name,
                    "cost" to card.cost,
                    "mana" to war.me.usableResource,
                    "uncertain" to card.isUncertain,
                    "outcome" to outcome,
                    "reason" to reason,
                    "rawPlayActions" to rawPlayActions,
                    "addedActions" to addedActions,
                    "opaqueFallback" to opaqueFallback,
                ),
            )
        }

        if (applyAction !== TurnOverAction) {
            val me = war.me
            val handArea = me.handArea
            val playArea = me.playArea
            result.add(TurnOverAction)
            for (card in handArea.cards) {
                val shouldDefer = arg.decisionModel?.shouldDefer(card, war)
                    ?: CardTimingPolicy.shouldDefer(card, war)
                if (shouldDefer) {
                    scanCard(card, "FILTERED", "decision-model-should-defer")
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
                    scanCard(card, "FILTERED", "coin-has-no-immediate-payoff")
                    if (parent == null && arg.debugName.isNotBlank()) {
                        log.info {
                            "MCTS_DEBUG_ACTION_FILTER strategy=${arg.debugName} " +
                                "action=打出(${card.cardId.ifBlank { card.entityName }}) " +
                                "reason=硬币不能解锁当前不可用的手牌或英雄技能"
                        }
                    }
                    continue
                }
                if (card.isUncertain) {
                    scanCard(card, "FILTERED", "uncertain-card")
                    continue
                }
                if (me.usableResource < card.cost) {
                    scanCard(card, "FILTERED", "insufficient-mana")
                    continue
                }
                if (playArea.isFull && card.cardType !== CardTypeEnum.HERO && card.cardType !== CardTypeEnum.SPELL && card.cardType !== CardTypeEnum.WEAPON) {
                    scanCard(card, "FILTERED", "board-full-for-permanent")
                    continue
                }
                val playActionsResult = runCatching { card.action.generatePlayActions(war, me) }
                val playActions = playActionsResult.getOrElse {
                    scanCard(card, "FILTERED", "play-action-generation-error:${it::class.java.simpleName}")
                    emptyList()
                }
                if (playActionsResult.isFailure) continue
                result.addAll(playActions)
                val opaqueAllowed = playActions.isEmpty() && arg.decisionModel?.canCreateOpaqueAction(card, war) == true
                if (opaqueAllowed) {
                    result.add(createOpaquePlayAction(card))
                }
                scanCard(
                    card,
                    if (playActions.isNotEmpty() || opaqueAllowed) "ADDED" else "FILTERED",
                    when {
                        playActions.isNotEmpty() -> "parsed-play-actions"
                        opaqueAllowed -> "opaque-fallback"
                        else -> "no-play-action-and-no-opaque-fallback"
                    },
                    rawPlayActions = playActions.size,
                    addedActions = playActions.size + if (opaqueAllowed) 1 else 0,
                    opaqueFallback = opaqueAllowed,
                )
            }
            for (card in playArea.cards) {
                val attackActions = if (card.canAttack()) {
                    runCatching { card.action.generateAttackActions(war, me) }.getOrElse {
                        addScan(mapOf("kind" to "BOARD_CARD", "entityId" to card.entityId, "cardId" to card.cardId, "outcome" to "FILTERED", "reason" to "attack-action-generation-error:${it::class.java.simpleName}"))
                        emptyList()
                    }
                } else emptyList()
                if (attackActions.isNotEmpty()) {
                    result.addAll(attackActions)
                    addScan(mapOf("kind" to "BOARD_CARD", "entityId" to card.entityId, "cardId" to card.cardId, "outcome" to "ADDED", "reason" to "attack-actions", "rawActions" to attackActions.size))
                } else if (card.canPower()) {
                    val powerActions = runCatching { card.action.generatePowerActions(war, me) }.getOrElse {
                        addScan(mapOf("kind" to "BOARD_CARD", "entityId" to card.entityId, "cardId" to card.cardId, "outcome" to "FILTERED", "reason" to "power-action-generation-error:${it::class.java.simpleName}"))
                        emptyList()
                    }
                    result.addAll(powerActions)
                    val opaquePower = powerActions.isEmpty() && arg.decisionModel?.canCreateOpaquePowerAction(card, war) == true
                    if (opaquePower) result.add(createOpaquePowerAction(card))
                    addScan(mapOf("kind" to "BOARD_CARD", "entityId" to card.entityId, "cardId" to card.cardId, "outcome" to if (powerActions.isNotEmpty() || opaquePower) "ADDED" else "FILTERED", "reason" to when { powerActions.isNotEmpty() -> "power-actions"; opaquePower -> "opaque-power-fallback"; else -> "no-power-action-and-no-opaque-fallback" }, "rawActions" to powerActions.size, "opaqueFallback" to opaquePower))
                } else {
                    addScan(mapOf("kind" to "BOARD_CARD", "entityId" to card.entityId, "cardId" to card.cardId, "outcome" to "FILTERED", "reason" to "not-attackable-and-not-powerable"))
                }
            }
            playArea.hero?.let { myHero ->
                if (myHero.canAttack()) {
                    val actions = runCatching { myHero.action.generateAttackActions(war, me) }.getOrElse {
                        addScan(mapOf("kind" to "HERO", "entityId" to myHero.entityId, "outcome" to "FILTERED", "reason" to "attack-action-generation-error:${it::class.java.simpleName}"))
                        emptyList()
                    }
                    result.addAll(actions)
                    addScan(mapOf("kind" to "HERO", "entityId" to myHero.entityId, "outcome" to if (actions.isNotEmpty()) "ADDED" else "FILTERED", "reason" to if (actions.isNotEmpty()) "attack-actions" else "no-attack-actions", "rawActions" to actions.size))
                } else {
                    addScan(mapOf("kind" to "HERO", "entityId" to myHero.entityId, "outcome" to "FILTERED", "reason" to "hero-cannot-attack"))
                }
            }
            playArea.power?.let { myPower ->
                if (me.usableResource >= myPower.cost && myPower.canPower()) {
                    val actions = runCatching { myPower.action.generatePowerActions(war, me) }.getOrElse {
                        addScan(mapOf("kind" to "HERO_POWER", "entityId" to myPower.entityId, "outcome" to "FILTERED", "reason" to "power-action-generation-error:${it::class.java.simpleName}"))
                        emptyList()
                    }
                    result.addAll(actions)
                    addScan(mapOf("kind" to "HERO_POWER", "entityId" to myPower.entityId, "outcome" to if (actions.isNotEmpty()) "ADDED" else "FILTERED", "reason" to if (actions.isNotEmpty()) "power-actions" else "no-power-actions", "rawActions" to actions.size))
                } else {
                    addScan(mapOf("kind" to "HERO_POWER", "entityId" to myPower.entityId, "outcome" to "FILTERED", "reason" to if (me.usableResource < myPower.cost) "insufficient-mana" else "cannot-power"))
                }
            }
        }
        val mandatoryActions: List<Action> = arg.decisionModel
            ?.let { model -> result.filter { model.isMandatoryAction(it, war) } }
            ?: emptyList()
        if (mandatoryActions.isNotEmpty()) {
            addScan(mapOf("kind" to "TREE_FILTER", "outcome" to "MANDATORY_ONLY", "reason" to "decision-model-mandatory-actions", "actions" to mandatoryActions.map(::actionDescription)))
            rootScan?.let { scan ->
                MctsReplayTrace.record(
                    war,
                    "action_scan",
                    "root action scan completed with mandatory-action restriction",
                    mapOf("strategy" to arg.debugName, "phase" to "root", "preFilterActionCount" to result.size, "mandatoryActionCount" to mandatoryActions.size, "finalActionCount" to mandatoryActions.size, "decisions" to scan),
                )
            }
            return mandatoryActions.toMutableList()
        }

        val deferredDecisions = result.map { action ->
            action to (arg.decisionModel?.isDeferredAction(action, war) == true)
        }
        val deferredActions = if (arg.decisionModel != null) {
            deferredDecisions.filterNot { it.second }.map { it.first }
        } else result
        deferredDecisions.filter { it.second }.forEach { (action, _) ->
            addScan(mapOf("kind" to "ACTION_FILTER", "outcome" to "FILTERED", "reason" to "decision-model-deferred-action", "action" to actionDescription(action)))
        }
        val filteredActions = if (deferredActions.isNotEmpty()) deferredActions else result
        // In experimental/receding-horizon MCTS, EndTurn is a terminal
        // control action, not a peer of a still-legal card, attack, or power.
        // Keeping it in the root set lets UCT end the turn early; the live
        // guard then sees the real action and exhausts its replan budget.
        val finalActions = if (arg.experimentalSearch && filteredActions.any { it !== TurnOverAction }) {
            filteredActions.filterNot { it === TurnOverAction }.toMutableList()
        } else {
            filteredActions.toMutableList()
        }
        addScan(mapOf("kind" to "TREE_FILTER", "outcome" to "FINAL", "reason" to if (arg.experimentalSearch && filteredActions.any { it !== TurnOverAction }) "experimental-end-turn-removed" else "end-turn-retained", "preFilterActionCount" to result.size, "deferredActionCount" to deferredActions.size, "finalActionCount" to finalActions.size, "finalActions" to finalActions.map(::actionDescription)))
        rootScan?.let { scan ->
            MctsReplayTrace.record(
                war,
                "action_scan",
                "root action scan completed with per-branch telemetry",
                mapOf("strategy" to arg.debugName, "phase" to "root", "preFilterActionCount" to result.size, "mandatoryActionCount" to mandatoryActions.size, "deferredActionCount" to deferredActions.size, "finalActionCount" to finalActions.size, "decisions" to scan),
            )
        }
        return finalActions
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

