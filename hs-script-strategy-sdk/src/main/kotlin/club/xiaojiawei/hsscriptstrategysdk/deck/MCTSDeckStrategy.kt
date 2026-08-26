package club.xiaojiawei.hsscriptstrategysdk.deck

import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import club.xiaojiawei.hsscriptcardsdk.bean.EmptyAction
import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.TurnOverAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.util.RandomUtil
import club.xiaojiawei.hsscriptcardsdk.mcts.MonteCarloTreeSearch
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum

/**
 * 蒙特卡洛树搜索算法
 * @author 肖嘉威
 * @date 2025/1/22 17:04
 */
abstract class MCTSDeckStrategy : DeckStrategy() {
    override fun executeOutCard() {
        val war = WAR
        val mctsArgList = executeMCTSOutCard(war)
        val experimentalArg = mctsArgList.firstOrNull { it.experimentalSearch }
        if (experimentalArg != null) {
            executeExperimentalTurn(war, experimentalArg)
            return
        }
        val monteCarloTreeSearch = MonteCarloTreeSearch()
        var execTime = 0L
        val size = mctsArgList.size
        var i = 0
        while (i < size) {
            val start = System.currentTimeMillis()
            try {
                val mctsArg = mctsArgList[i]
                val arg =
                    MCTSArg(
                        mctsArg.endMillisTime + execTime,
                        mctsArg.turnCount,
                        mctsArg.turnFactor,
                        mctsArg.countPerTurn,
                        mctsArg.scoreCalculator,
                        mctsArg.enableMultiThread,
                        mctsArg.debugName,
                        mctsArg.decisionModel,
                        mctsArg.experimentalSearch,
                        mctsArg.experimentalTurnBudgetMillis,
                        mctsArg.experimentalActionBudgetMillis,
                    )
                if (arg.debugName.isNotBlank()) {
                    log.info {
                        "MCTS_DEBUG_DECISION strategy=${name()} phase=${i + 1}/$size " +
                            "turn=${war.me.turn} mana=${war.me.usableResource}/${war.me.resources} " +
                            "hand=${war.me.handArea.cards.joinToString(prefix = "[", postfix = "]") { describeActionCard(it) }} " +
                            "priority=合法动作→MCTS模拟→完整路径最终状态评分；" +
                                "专用动作时序模型=${if (arg.decisionModel != null) "开启" else "关闭"}"
                    }
                }
                val bestNodes = monteCarloTreeSearch.searchBestNode(war, arg).filter { it.applyAction !is EmptyAction }
                log.info { "思考耗时：${System.currentTimeMillis() - start}ms，执行动作数：${bestNodes.size}，得分：${bestNodes.lastOrNull()?.state?.score?:"--"}" }
                if (arg.debugName.isNotBlank()) {
                    log.info {
                        "MCTS_DEBUG_SELECTED strategy=${name()} phase=${i + 1}/$size " +
                            "path=${bestNodes.mapIndexed { index, node ->
                                val triggers = node.simulatedFreeSummons.joinToString { summon ->
                                    "免费召唤${summon.card.entityName.ifBlank { summon.card.cardId }}"
                                }
                                "${index + 1}.${describeAction(node.applyAction)}" +
                                    triggers.takeIf { it.isNotBlank() }?.let { "[$it]" }.orEmpty()
                            }.joinToString(" -> ").ifBlank { "(无可执行动作，仅结束回合)" }} " +
                            "reason=搜索返回的最佳完整模拟路径；最终节点状态评分最高"
                    }
                }
                var continueCurrent = false
                for (action in bestNodes) {
                    val applyAction = action.applyAction
                    applyAction.exec.accept(war)
                    if (applyAction.recalculate) {
                        Thread.sleep(RandomUtil.getActionInterval(1500).toLong())
                        continueCurrent = true
                        break
                    }
                }
                if (continueCurrent) continue
                if (i < size - 1) {
                    Thread.sleep(RandomUtil.getActionInterval(1500).toLong())
                }
                i++
            } finally {
                execTime += (System.currentTimeMillis() - start)
                System.gc()
            }

        }
    }

    /**
     * Experimental strategies use receding-horizon control: search one
     * action, execute only that action, wait for the live state to change,
     * then search again. This keeps random cannon shots, Discover, draws and
     * board-space changes from invalidating a long simulated path.
     */
    private fun executeExperimentalTurn(war: War, template: MCTSArg) {
        val turnDeadline = System.currentTimeMillis() + template.experimentalTurnBudgetMillis
        val search = MonteCarloTreeSearch()
        var actionCount = 0
        while (war.isMyTurn && System.currentTimeMillis() < turnDeadline && actionCount < 16) {
            val searchStart = System.currentTimeMillis()
            val actionDeadline = minOf(
                turnDeadline,
                searchStart + template.experimentalActionBudgetMillis,
            )
            val arg = MCTSArg(
                actionDeadline,
                1,
                template.turnFactor,
                template.countPerTurn,
                template.scoreCalculator,
                false,
                template.debugName,
                template.decisionModel,
                true,
                template.experimentalTurnBudgetMillis,
                template.experimentalActionBudgetMillis,
            )
            val path = search.searchBestNode(war, arg)
                .filter { it.applyAction !is EmptyAction }
            val node = path.firstOrNull() ?: break
            val action = node.applyAction
            if (action === TurnOverAction) break

            val before = stateFingerprint(war)
            log.info {
                "MCTS_EXPERIMENT_STEP strategy=${name()} step=${actionCount + 1} " +
                    "action=${describeAction(action)} pathLength=${path.size}"
            }
            try {
                action.exec.accept(war)
            } catch (error: Throwable) {
                log.error(error) { "MCTS_EXPERIMENT_ACTION_FAILED strategy=${name()} action=${describeAction(action)}" }
                break
            }
            actionCount++

            if (!awaitStateChange(war, before, turnDeadline)) {
                log.warn {
                    "MCTS_EXPERIMENT_REPLAN_STOP strategy=${name()} reason=状态未确认变化 " +
                        "step=$actionCount"
                }
                break
            }
        }
        log.info { "MCTS_EXPERIMENT_TURN_DONE strategy=${name()} actions=$actionCount" }
    }

    private fun awaitStateChange(war: War, before: String, turnDeadline: Long): Boolean {
        val waitDeadline = minOf(turnDeadline, System.currentTimeMillis() + 2_000L)
        while (war.isMyTurn && System.currentTimeMillis() < waitDeadline) {
            if (stateFingerprint(war) != before) return true
            Thread.sleep(80L)
        }
        return stateFingerprint(war) != before || !war.isMyTurn
    }

    private fun stateFingerprint(war: War): String {
        val me = war.me
        val hand = me.handArea.cards.joinToString(",") { "${it.entityId}:${it.cardId}:${it.cost}" }
        val board = me.playArea.cards.joinToString(",") {
            "${it.entityId}:${it.cardId}:${it.atc}:${it.health}:${it.damage}:${it.isExhausted}"
        }
        val hero = me.playArea.hero?.let { "${it.atc}:${it.health}:${it.damage}:${it.isExhausted}" }.orEmpty()
        val weapon = me.playArea.weapon?.let { "${it.cardId}:${it.durability}" }.orEmpty()
        val location = me.playArea.cards.filter { it.cardType === CardTypeEnum.LOCATION }
            .joinToString(",") { "${it.entityId}:${it.isLocationActionCooldown}:${it.damage}" }
        val rivalBoard = war.rival.playArea.cards.joinToString(",") {
            "${it.entityId}:${it.cardId}:${it.atc}:${it.health}:${it.damage}:${it.isExhausted}"
        }
        val rivalHero = war.rival.playArea.hero?.let {
            "${it.atc}:${it.health}:${it.damage}:${it.isExhausted}"
        }.orEmpty()
        val rivalWeapon = war.rival.playArea.weapon?.let { "${it.cardId}:${it.durability}" }.orEmpty()
        return "${me.turn}|${me.usableResource}|$hand|$board|$hero|$weapon|$location|" +
            "$rivalBoard|$rivalHero|$rivalWeapon"
    }

    private fun describeActionCard(card: club.xiaojiawei.hsscriptcardsdk.bean.Card): String {
        val displayName = card.entityName.ifBlank { "UNKNOWN" }
        return "${card.cardId.ifBlank { "NO_ID" }}:$displayName(cost=${card.cost},entity=${card.entityId})"
    }

    private fun describeAction(action: Action): String {
        if (action === TurnOverAction) return "结束回合"
        val creator = action.creator
        val card = creator?.let { describeActionCard(it) } ?: "无来源卡牌"
        val kind = when (action) {
            is PlayAction -> "打出"
            is AttackAction -> "攻击"
            is PowerAction -> "使用技能/效果"
            else -> action.javaClass.simpleName
        }
        return "$kind($card)"
    }

    /**
     * 通过mcts算法出牌
     * @return mcts算法参数，返回几个参数就执行几次
     */
    abstract fun executeMCTSOutCard(war: War): List<MCTSArg>
}
