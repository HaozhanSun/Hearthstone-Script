package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.InitAction
import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptbase.bean.LRunnable
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.TurnOverAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptbase.config.CALC_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.util.randomSelect
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Function
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * 蒙特卡洛树搜索
 * @author 肖嘉威
 * @date 2025/1/10 10:04
 */
const val MCTS_DEFAULT_DEPTH = 10

class MonteCarloTreeSearch(val maxDepth: Int = MCTS_DEFAULT_DEPTH) {

    private fun select(rootNode: MonteCarloTreeNode, endTime: Long): MonteCarloTreeNode {
        if (rootNode.arg.experimentalSearch) {
            var node = rootNode
            var level = 0
            while (node.isFullExpanded() && !node.isLeaf() && System.currentTimeMillis() < endTime) {
                node = node.children.maxWithOrNull(
                    compareBy<MonteCarloTreeNode> { it.state.calcUCB(node.state.visitCount) }
                        .thenBy { it.state.visitCount },
                ) ?: break
                level++
                if (level >= maxDepth) break
            }
            return node
        }
        var node: MonteCarloTreeNode = rootNode
        var maxUCB = Int.MIN_VALUE.toDouble()
        var level = 0
        while (node.isFullExpanded() && !node.isLeaf() && System.currentTimeMillis() < endTime) {
            val parentNode = node
            val children = node.children
            for (child in children) {
                val ucb = child.state.calcUCB(parentNode.state.visitCount)
                if (ucb > maxUCB) {
                    maxUCB = ucb
                    node = child
                }
            }
            level++
            if (level > maxDepth) {
                break
            }
        }
        return node
    }

    private fun expand(node: MonteCarloTreeNode): MonteCarloTreeNode? {
        var nextNode: MonteCarloTreeNode? = null
        if (!node.isFullExpanded()) {
            val unExpanded = node.getUnExpanded()
            val action = if (node.arg.experimentalSearch && node.arg.decisionModel != null) {
                unExpanded.maxWithOrNull(
                    compareBy<Action> { node.arg.decisionModel.actionPrior(it, node.state.war) }
                        .thenBy { it.javaClass.simpleName },
                ) ?: unExpanded.randomSelect()
            } else {
                unExpanded.randomSelect()
            }
            nextNode = node.expand(action)
        }
        return nextNode
    }

    private fun simulateValue(node: MonteCarloTreeNode, rootNode: MonteCarloTreeNode, endTime: Long): Double {
        var tempNode = node
        var isFirstTempNode = true
        var depth = 0
        while (!tempNode.isEnd() && System.currentTimeMillis() < endTime && depth < maxDepth) {
            val actions = tempNode.actions
            val decisionModel = tempNode.arg.decisionModel
            val action = if (tempNode.arg.experimentalSearch && decisionModel != null) {
                actions.maxWithOrNull(
                    compareBy<Action> { decisionModel.actionPrior(it, tempNode.state.war) }
                        .thenBy { it.javaClass.simpleName },
                ) ?: actions.randomSelect()
            } else {
                actions.randomSelect()
            }

            val nextTempNode = if (isFirstTempNode) {
                isFirstTempNode = false
                tempNode.buildNextNode(action, cloneWar = true)
            } else tempNode.buildNextNode(action, cloneWar = false)

            tempNode = nextTempNode
            depth++
        }
        val score = tempNode.state.score
        return (score - rootNode.state.score).coerceIn(-1000.0, 1000.0)
    }

    private fun simulate(node: MonteCarloTreeNode, rootNode: MonteCarloTreeNode, endTime: Long): Boolean =
        simulateValue(node, rootNode, endTime) > 0.0

    private fun simulateExperimental(node: MonteCarloTreeNode, rootNode: MonteCarloTreeNode, endTime: Long): Double =
        simulateValue(node, rootNode, endTime)

    private fun backPropagation(node: MonteCarloTreeNode, win: Boolean?, reward: Double? = null) {
        var tempNode: MonteCarloTreeNode? = node
        while (tempNode != null) {
            tempNode.state.update(win, reward)
            tempNode = tempNode.parent
        }
    }

    private fun buildBest(rootNode: MonteCarloTreeNode): MutableList<MonteCarloTreeNode> {
        val result = mutableListOf<MonteCarloTreeNode>()

        if (rootNode.arg.experimentalSearch) {
            var node: MonteCarloTreeNode? = rootNode.children.maxWithOrNull(
                compareBy<MonteCarloTreeNode> { it.state.visitCount }
                    .thenBy { it.state.averageValue() },
            )
            while (node != null) {
                result.addFirst(node)
                node = node.children.maxWithOrNull(
                    compareBy<MonteCarloTreeNode> { it.state.visitCount }
                        .thenBy { it.state.averageValue() },
                )
            }
            return result
        }

        var maxNode: MonteCarloTreeNode? = rootNode
        var maxScore = Int.MIN_VALUE.toDouble()
        var maxVisit = Int.MIN_VALUE
        var children = rootNode.children.toList()
        while (children.isNotEmpty()) {
            val list = mutableListOf<MonteCarloTreeNode>()
            for (child in children) {
                if (child.isEnd()) {
                    val score = child.state.score
                    if (score > maxScore) {
                        maxNode = child
                        maxScore = score
                    }
//                    if (child.state.visitCount > maxVisit) {
//                        maxNode = child
//                        maxVisit = child.state.visitCount
//                    }
//                    val ucb = child.state.calcUCB(totalCount)
//                    if (ucb > maxUCB) {
//                        maxUCB = ucb
//                        maxNode = child
//                    }
                }
                list.addAll(child.children)
            }
            children = list
        }

        var tempNode: MonteCarloTreeNode? = maxNode
        while (tempNode != null) {
            result.addFirst(tempNode)
            tempNode = tempNode.parent
        }

//        var node: MonteCarloTreeNode? = rootNode
//        while (node != null) {
//            result.add(node)
//            var maxVisit = Int.MIN_VALUE
//            var maxNode: MonteCarloTreeNode? = null
//            for (child in node.children) {
//                if (child.state.visitCount > maxVisit) {
//                    maxNode = child
//                    maxVisit = child.state.visitCount
//                }
//            }
//            node = maxNode
//        }

        return result
    }

    fun searchBestNode(
        war: War, arg: MCTSArg
    ): MutableList<MonteCarloTreeNode> {
        val totalMillisTime = arg.endMillisTime - System.currentTimeMillis()
        val newWar = war.clone()
//        因为对手手牌不可知，所以去除模拟，todo 非正确处理方式
        newWar.rival.handArea.cards.clear()
        val newArg = MCTSArg(
            arg.endMillisTime,
            arg.turnCount,
            arg.turnFactor,
            arg.countPerTurn,
            arg.scoreCalculator,
            false,
            arg.debugName,
            arg.decisionModel,
            arg.experimentalSearch,
            arg.experimentalTurnBudgetMillis,
            arg.experimentalActionBudgetMillis,
        )
        val endTime = arg.endMillisTime
        val rootNode = MonteCarloTreeNode(newWar, InitAction, newArg)
        val results = Collections.synchronizedList(mutableListOf<MutableList<MonteCarloTreeNode>>())
        val tasks = mutableListOf<CompletableFuture<Void>>()
        val tasker = Function<MonteCarloTreeNode, MutableList<MonteCarloTreeNode>> { newRootNode ->
            var totalCount = 0
            var node: MonteCarloTreeNode

            while (totalCount < newArg.countPerTurn && System.currentTimeMillis() < endTime) {
                node = select(newRootNode, endTime)
                var win: Boolean? = null
                if (!node.isEnd()) {
                    expand(node)?.let {
                        node = it
                        if (newArg.experimentalSearch) {
                            val reward = simulateExperimental(node, newRootNode, endTime)
                            win = reward > 0.0
                            backPropagation(node, win, reward)
                        } else {
                            win = simulate(node, newRootNode, endTime)
                            backPropagation(node, win)
                        }
                    }
                }
                if (!newArg.experimentalSearch) {
                    backPropagation(node, win)
                }
                totalCount++
            }

            buildBest(newRootNode)
        }

        if (arg.enableMultiThread) {
            val maxTaskSize = Runtime.getRuntime().availableProcessors()
            val size = rootNode.actions.size
            val countPerTask = ceil(size / maxTaskSize.toDouble()).toInt()
            var index = 0
            while (index < size && System.currentTimeMillis() < endTime) {
                val endIndex = min(index + countPerTask, size)
                val rootNodesList = mutableListOf<MonteCarloTreeNode>()
                val counts = endIndex - index
                val childArg = MCTSArg(
                    arg.endMillisTime,
                    arg.turnCount,
                    arg.turnFactor,
                    floor(arg.countPerTurn / counts.toDouble()).toInt(),
                    arg.scoreCalculator,
                    false,
                    arg.debugName,
                    arg.decisionModel,
                    arg.experimentalSearch,
                    arg.experimentalTurnBudgetMillis,
                    arg.experimentalActionBudgetMillis,
                )
                for (i in index until endIndex) {
                    rootNode.expand(rootNode.actions[i], childArg)?.let { newRootNode ->
                        rootNodesList.add(newRootNode)
                    }
                }
                tasks.add(
                    CompletableFuture.runAsync(
                        LRunnable {
                            for (newRootNode in rootNodesList.reversed()) {
                                results.add(tasker.apply(newRootNode))
                            }
                        }, CALC_THREAD_POOL
                    )
                )
                index = endIndex
            }
        } else {
            results.add(tasker.apply(rootNode))
        }

        if (tasks.isNotEmpty()) {
            try {
                CompletableFuture.allOf(*tasks.toTypedArray()).get(totalMillisTime, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                log.warn(e) { "计算超时" }
            } catch (e: InterruptedException) {
                log.warn(e) { "计算中断" }
            } catch (e: Exception) {
                log.error(e) { "计算异常" }
            }
        }

        if (arg.debugName.isNotBlank()) {
            val rootTotal = rootNode.state.visitCount
            val rankedChildren = rootNode.children
                .sortedWith(
                    compareByDescending<MonteCarloTreeNode> { it.state.score }
                        .thenByDescending { it.state.visitCount },
                )
                .take(12)
            log.info {
                "MCTS_DEBUG_RANKING strategy=${arg.debugName} " +
                    "legal=${rootNode.actions.size} expanded=${rootNode.children.size} " +
                    "rootVisits=$rootTotal ranking=即时状态评分↓,访问次数↓"
            }
            rankedChildren.forEachIndexed { index, child ->
                val visits = child.state.visitCount
                val winRate = if (visits == 0) 0.0 else child.state.winCount.toDouble() / visits
                val ucb = child.state.calcUCB(rootTotal)
                    log.info {
                        "MCTS_DEBUG_CANDIDATE strategy=${arg.debugName} rank=${index + 1} " +
                            "action=${describeAction(child.applyAction)} " +
                            "stateScore=${"%.3f".format(child.state.score)} " +
                            "visits=$visits wins=${child.state.winCount} winRate=${"%.1f".format(winRate * 100)}% " +
                            "ucb=${"%.3f".format(ucb)} " +
                            "simulatedFreeSummons=" +
                            (child.simulatedFreeSummons.joinToString { summon ->
                                "${summon.card.cardId.ifBlank { summon.card.entityName }}(${summon.reason})"
                            }.ifBlank { "none" })
                    }
            }
        }

        var maxScore = Int.MIN_VALUE.toDouble()
        var bestResult: MutableList<MonteCarloTreeNode>? = null
        if (results.isEmpty()) {
            bestResult = buildBest(rootNode)
        } else {
            for (result in results) {
                if (result.isNotEmpty()) {
                    val score = if (arg.experimentalSearch) {
                        val first = result.first()
                        first.state.visitCount * 1_000.0 + first.state.averageValue()
                    } else {
                        result.last().state.score
                    }
                    if (score > maxScore) {
                        maxScore = score
                        bestResult = result
                    }
                }
            }
        }

        var finalResult = bestResult ?: mutableListOf()
        // Action generation can consume the complete experimental action
        // budget (especially while the card parser is still warming up).
        // In that case the root has legal actions but no expanded children,
        // and the old empty path made the live turn-end guard re-plan the
        // same turn until its safety cap was exhausted.  Preserve the
        // model's mandatory/deferred filtering and return one deterministic
        // legal root action so the receding-horizon executor can make real
        // progress even when there was no time for a rollout.
        if (arg.experimentalSearch && finalResult.isEmpty() && rootNode.actions.isNotEmpty()) {
            val fallback = rootNode.actions
                .filterNot { it === TurnOverAction }
                .maxWithOrNull(
                    compareBy<Action> { arg.decisionModel?.actionPrior(it, rootNode.state.war) ?: 0.0 }
                        .thenBy { it.javaClass.simpleName },
                ) ?: rootNode.actions.first()
            rootNode.expand(fallback)?.let { expanded ->
                finalResult = mutableListOf(expanded)
                if (arg.debugName.isNotBlank()) {
                    log.info {
                        "MCTS_DEBUG_ROOT_FALLBACK strategy=${arg.debugName} " +
                            "action=${describeAction(fallback)} reason=legal-root-action-without-expanded-child"
                    }
                }
            }
        }
        if (arg.debugName.isNotBlank()) {
            log.info {
                "MCTS_DEBUG_BEST_PATH strategy=${arg.debugName} " +
                    "path=${finalResult.filter { it.applyAction !is club.xiaojiawei.hsscriptcardsdk.bean.EmptyAction }
                        .joinToString(" -> ") { describeAction(it.applyAction) }
                        .ifBlank { "(empty)" }} " +
                    "selectionRule=best complete simulated path by final state score"
            }
        }
        return finalResult
    }

    private fun describeAction(action: Action): String {
        if (action === TurnOverAction) return "结束回合"
        val creator = action.creator
        val card = creator?.let {
            "${it.cardId.ifBlank { "NO_ID" }}:${it.entityName.ifBlank { "UNKNOWN" }}" +
                "(cost=${it.cost},entity=${it.entityId})"
        } ?: "无来源卡牌"
        val kind = when (action) {
            is PlayAction -> "打出"
            is AttackAction -> "攻击"
            is PowerAction -> "使用技能/效果"
            else -> action.javaClass.simpleName
        }
        return "$kind($card)"
    }

}
