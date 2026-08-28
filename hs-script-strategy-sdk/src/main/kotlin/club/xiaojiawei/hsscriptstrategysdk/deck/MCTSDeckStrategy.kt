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
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsDecisionModel
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsReplayTrace
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum

/**
 * 蒙特卡洛树搜索算法
 * @author 肖嘉威
 * @date 2025/1/22 17:04
 */
abstract class MCTSDeckStrategy : DeckStrategy() {
    @Volatile
    private var lastExperimentalTurnHadUnconfirmedDispatch = false

    @Volatile
    private var lastExperimentalTurnProducedAction = false

    @Volatile
    private var activeDecisionModel: MctsDecisionModel? = null

    private var experimentalTurnNumber: Int? = null
    private val suppressedExperimentalCreatorIds = mutableSetOf<String>()

    fun hasUnconfirmedExperimentalDispatch(): Boolean =
        lastExperimentalTurnHadUnconfirmedDispatch

    /**
     * The app-side turn-end guard must not resurrect stale residual creators
     * after a fresh MCTS pass returned only EndTurn/no action.  In that case
     * the strategy has already reconciled the live state and the guard may
     * safely finish the turn.
     */
    fun hasLastExperimentalTurnProducedAction(): Boolean =
        lastExperimentalTurnProducedAction

    /**
     * Creators whose last live dispatch produced no observable confirmation
     * in this turn.  The app-side MCTS end-turn observer uses this set to
     * avoid re-planning an action that this strategy has already quarantined.
     */
    fun suppressedExperimentalCreatorIds(): Set<String> = synchronized(this) {
        suppressedExperimentalCreatorIds.toSet()
    }

    /**
     * Return only creators for which the same live action model used by MCTS
     * can currently expose an executable action.  This prevents the app-side
     * end-turn check from treating a partially parsed card with a fitting
     * printed cost as actionable when MCTS has no action to dispatch.
     */
    fun actionableCreatorIds(war: War, purpose: String = "mcts-live-scan"): Set<String> {
        val me = war.me
        val model = activeDecisionModel
        val suppressed = suppressedExperimentalCreatorIds()
        val result = linkedSetOf<String>()
        val decisions = mutableListOf<Map<String, Any?>>()
        fun decision(details: Map<String, Any?>) {
            decisions += details
        }
        me.handArea.cards.forEach { card ->
            if (card.entityId in suppressed) {
                decision(mapOf("kind" to "HAND_CARD", "cardId" to card.cardId, "entityId" to card.entityId, "outcome" to "FILTERED", "reason" to "suppressed-after-unconfirmed-dispatch"))
                return@forEach
            }
            if (card.isUncertain) {
                decision(mapOf("kind" to "HAND_CARD", "cardId" to card.cardId, "entityId" to card.entityId, "outcome" to "FILTERED", "reason" to "uncertain-card"))
                return@forEach
            }
            if (card.cost > me.usableResource) {
                decision(mapOf("kind" to "HAND_CARD", "cardId" to card.cardId, "entityId" to card.entityId, "cost" to card.cost, "mana" to me.usableResource, "outcome" to "FILTERED", "reason" to "insufficient-mana"))
                return@forEach
            }
            if (me.playArea.isFull &&
                (card.cardType === CardTypeEnum.MINION || card.cardType === CardTypeEnum.LOCATION)
            ) {
                decision(mapOf("kind" to "HAND_CARD", "cardId" to card.cardId, "entityId" to card.entityId, "outcome" to "FILTERED", "reason" to "board-full-for-permanent"))
                return@forEach
            }
            // Mirror MonteCarloTreeNode: a card filtered by a deck timing
            // hook is not an actionable residual for the end-turn guard.
            if (model?.shouldDefer(card, war) == true) {
                decision(mapOf("kind" to "HAND_CARD", "cardId" to card.cardId, "entityId" to card.entityId, "outcome" to "FILTERED", "reason" to "decision-model-should-defer"))
                return@forEach
            }
            val parsedResult = runCatching { card.action.generatePlayActions(war, me) }
            val parsedActions = parsedResult.getOrElse {
                decision(mapOf("kind" to "HAND_CARD", "cardId" to card.cardId, "entityId" to card.entityId, "outcome" to "FILTERED", "reason" to "play-action-generation-error:${it::class.java.simpleName}"))
                emptyList()
            }
            if (parsedResult.isFailure) return@forEach
            val parsed = parsedActions.any { model?.isDeferredAction(it, war) != true }
            val deferredParsed = parsedActions.count { model?.isDeferredAction(it, war) == true }
            val opaque = parsedActions.isEmpty() && model?.canCreateOpaqueAction(card, war) == true
            // Match MonteCarloTreeNode exactly: an opaque action is only a
            // fallback when the parser produced no action at all.  A parsed
            // action that the model deliberately deferred (for example
            // Blindeye Judge) must not be reintroduced by this fallback.
            if (parsed || opaque) {
                result += card.entityId
            }
            decision(
                mapOf(
                    "kind" to "HAND_CARD",
                    "cardId" to card.cardId,
                    "entityId" to card.entityId,
                    "cost" to card.cost,
                    "mana" to me.usableResource,
                    "rawPlayActions" to parsedActions.size,
                    "modelDeferredActions" to deferredParsed,
                    "opaqueFallback" to opaque,
                    "outcome" to if (parsed || opaque) "ACTIONABLE" else "FILTERED",
                    "reason" to when {
                        parsed -> "parsed-play-action"
                        opaque -> "opaque-fallback"
                        else -> "no-live-play-action"
                    },
                ),
            )
        }
        me.playArea.cards.forEach { card ->
            if (card.entityId in suppressed) {
                decision(mapOf("kind" to "BOARD_CARD", "cardId" to card.cardId, "entityId" to card.entityId, "outcome" to "FILTERED", "reason" to "suppressed-after-unconfirmed-dispatch"))
                return@forEach
            }
            if (card.canAttack()) {
                val attackResult = runCatching { card.action.generateAttackActions(war, me) }
                val attackActions = attackResult.getOrElse { emptyList() }
                if (attackActions.any { model?.isDeferredAction(it, war) != true }) {
                    result += card.entityId
                }
                decision(mapOf("kind" to "BOARD_CARD", "cardId" to card.cardId, "entityId" to card.entityId, "rawAttackActions" to attackActions.size, "outcome" to if (attackActions.any { model?.isDeferredAction(it, war) != true }) "ACTIONABLE" else "FILTERED", "reason" to if (attackResult.isFailure) "attack-action-generation-error:${attackResult.exceptionOrNull()!!::class.java.simpleName}" else "attack-actions"))
            }
            if (card.canPower()) {
                val powerResult = runCatching { card.action.generatePowerActions(war, me) }
                val powerActions = powerResult.getOrElse { emptyList() }
                if (powerActions.any { model?.isDeferredAction(it, war) != true }) {
                    result += card.entityId
                }
                decision(mapOf("kind" to "BOARD_CARD", "cardId" to card.cardId, "entityId" to card.entityId, "rawPowerActions" to powerActions.size, "outcome" to if (powerActions.any { model?.isDeferredAction(it, war) != true }) "ACTIONABLE" else "FILTERED", "reason" to if (powerResult.isFailure) "power-action-generation-error:${powerResult.exceptionOrNull()!!::class.java.simpleName}" else "power-actions"))
            }
            val opaquePower = model?.canCreateOpaquePowerAction(card, war) == true
            if (opaquePower) result += card.entityId
            if (opaquePower) decision(mapOf("kind" to "BOARD_CARD", "cardId" to card.cardId, "entityId" to card.entityId, "outcome" to "ACTIONABLE", "reason" to "opaque-power-fallback"))
        }
        me.playArea.hero?.let { hero ->
            if (hero.entityId in suppressed) {
                decision(mapOf("kind" to "HERO", "entityId" to hero.entityId, "outcome" to "FILTERED", "reason" to "suppressed-after-unconfirmed-dispatch"))
                return@let
            }
            if (hero.canAttack() && runCatching {
                    hero.action.generateAttackActions(war, me).isNotEmpty()
                }.getOrDefault(false)
            ) result += hero.entityId
            decision(mapOf("kind" to "HERO", "entityId" to hero.entityId, "outcome" to if (hero.entityId in result) "ACTIONABLE" else "FILTERED", "reason" to if (hero.entityId in result) "attack-actions" else "no-attack-actions-or-not-attackable"))
        }
        me.playArea.power?.let { power ->
            if (power.entityId in suppressed) {
                decision(mapOf("kind" to "HERO_POWER", "entityId" to power.entityId, "outcome" to "FILTERED", "reason" to "suppressed-after-unconfirmed-dispatch"))
                return@let
            }
            if (power.canPower() && runCatching {
                    power.action.generatePowerActions(war, me).isNotEmpty()
                }.getOrDefault(false)
            ) result += power.entityId
            val opaquePower = model?.canCreateOpaquePowerAction(power, war) == true
            if (opaquePower) result += power.entityId
            decision(mapOf("kind" to "HERO_POWER", "entityId" to power.entityId, "outcome" to if (power.entityId in result) "ACTIONABLE" else "FILTERED", "reason" to if (opaquePower) "opaque-power-fallback" else "power-actions-or-not-powerable"))
        }
        MctsReplayTrace.record(
            war,
            "live_actionability_scan",
            "full live action scan completed before end-turn decision",
            mapOf(
                "strategy" to name(),
                "purpose" to purpose,
                "mana" to me.usableResource,
                "resources" to me.resources,
                "usedResources" to me.usedResources,
                "boardSlotsFree" to (me.playArea.maxSize - me.playArea.cards.size).coerceAtLeast(0),
                "actionableCreatorIds" to result,
                "decisions" to decisions,
            ),
        )
        return result
    }

    override fun executeOutCard() {
        val war = WAR
        val mctsArgList = executeMCTSOutCard(war)
        MctsReplayTrace.record(
            war,
            "turn_controller_started",
            "MCTS began planning from the live turn state",
            mapOf("strategy" to name(), "argumentCount" to mctsArgList.size),
        )
        val experimentalArg = mctsArgList.firstOrNull { it.experimentalSearch }
        if (experimentalArg != null) {
            activeDecisionModel = experimentalArg.decisionModel
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
                if (bestNodes.isEmpty()) {
                    MctsReplayTrace.record(
                        war,
                        "turn_end_candidate",
                        "the search returned an empty executable path",
                        mapOf(
                            "strategy" to name(),
                            "phase" to i + 1,
                            "mana" to war.me.usableResource,
                            "handSize" to war.me.handArea.cards.size,
                        ),
                    )
                }
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
                    val before = stateFingerprint(war)
                    MctsReplayTrace.record(
                        war,
                        "action_dispatched",
                        "live executor dispatched the selected MCTS path action",
                        mapOf(
                            "strategy" to name(),
                            "phase" to i + 1,
                            "action" to describeAction(applyAction),
                            "pathLength" to bestNodes.size,
                            "pathIndex" to bestNodes.indexOf(action) + 1,
                            "stateBefore" to before,
                        ),
                    )
                    applyAction.exec.accept(war)
                    MctsReplayTrace.record(
                        war,
                        "action_dispatch_returned",
                        "live action callback returned; the next search will re-read state",
                        mapOf(
                            "strategy" to name(),
                            "phase" to i + 1,
                            "action" to describeAction(applyAction),
                            "stateChangedImmediately" to (stateFingerprint(war) != before),
                        ),
                    )
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
        lastExperimentalTurnHadUnconfirmedDispatch = false
        lastExperimentalTurnProducedAction = false
        synchronized(this) {
            if (experimentalTurnNumber != war.me.turn) {
                experimentalTurnNumber = war.me.turn
                suppressedExperimentalCreatorIds.clear()
            }
        }
        val turnDeadline = System.currentTimeMillis() + template.experimentalTurnBudgetMillis
        val search = MonteCarloTreeSearch()
        var actionCount = 0
        val blockedCreatorIds = suppressedExperimentalCreatorIds().toMutableSet()
        while (war.isMyTurn && System.currentTimeMillis() < turnDeadline && actionCount < 16) {
            val searchStart = System.currentTimeMillis()
            val actionDeadline = minOf(
                turnDeadline,
                searchStart + template.experimentalActionBudgetMillis,
            )
            val decisionModel = template.decisionModel?.let { model ->
                if (blockedCreatorIds.isEmpty()) model
                else TemporarilyBlockedActionModel(model, blockedCreatorIds)
            }
            val arg = MCTSArg(
                actionDeadline,
                1,
                template.turnFactor,
                template.countPerTurn,
                template.scoreCalculator,
                false,
                template.debugName,
                decisionModel,
                true,
                template.experimentalTurnBudgetMillis,
                template.experimentalActionBudgetMillis,
            )
            val path = search.searchBestNode(war, arg)
                .filter { it.applyAction !is EmptyAction }
            val node = path.firstOrNull() ?: run {
                MctsReplayTrace.record(
                    war,
                    "turn_end_candidate",
                    "experimental search returned no executable action after root filtering",
                    mapOf(
                        "strategy" to name(),
                        "step" to actionCount + 1,
                        "mana" to war.me.usableResource,
                        "hand" to war.me.handArea.cards.map { describeActionCard(it) },
                    ),
                )
                MctsReplayTrace.record(
                    war,
                    "controller_branch",
                    "no-executable-path-returned",
                    mapOf("strategy" to name(), "step" to actionCount + 1, "turnDeadlineReached" to (System.currentTimeMillis() >= turnDeadline)),
                )
                break
            }
            val action = node.applyAction
            if (action === TurnOverAction) {
                MctsReplayTrace.record(
                    war,
                    "turn_end_candidate",
                    "experimental search selected the explicit EndTurn action",
                    mapOf("strategy" to name(), "step" to actionCount + 1),
                )
                MctsReplayTrace.record(
                    war,
                    "controller_branch",
                    "explicit-end-turn-action-returned",
                    mapOf("strategy" to name(), "step" to actionCount + 1),
                )
                break
            }

            // A search result can be built from the parser snapshot that was
            // used at the start of this re-plan.  If the preceding dispatch
            // was not confirmed, do not trust a stale path to return that
            // same creator again.  The model hook hides it during expansion,
            // but this final check is the dispatch boundary and protects the
            // live client even if a concurrent/stale search result bypasses
            // that filter.  Break so the caller can perform one fresh turn-
            // end inspection instead of spinning on the same action.
            val creatorId = action.creator?.entityId?.takeIf { it.isNotBlank() }
            if (creatorId != null && creatorId in blockedCreatorIds) {
                log.info {
                    "MCTS_EXPERIMENT_ACTION_SKIPPED strategy=${name()} " +
                        "action=${describeAction(action)} step=${actionCount + 1} " +
                        "reason=blocked-after-unconfirmed-dispatch"
                }
                MctsReplayTrace.record(
                    war,
                    "controller_branch",
                    "action-skipped-after-unconfirmed-dispatch",
                    mapOf("strategy" to name(), "step" to actionCount + 1, "creatorId" to creatorId, "action" to describeAction(action)),
                )
                break
            }

            val before = stateFingerprint(war)
            MctsReplayTrace.record(
                war,
                "action_dispatched",
                "experimental MCTS selected and dispatched one receding-horizon action",
                mapOf(
                    "strategy" to name(),
                    "step" to actionCount + 1,
                    "action" to describeAction(action),
                    "path" to path.map { describeAction(it.applyAction) },
                    "stateBefore" to before,
                ),
            )
            log.info {
                "MCTS_EXPERIMENT_STEP strategy=${name()} step=${actionCount + 1} " +
                    "action=${describeAction(action)} pathLength=${path.size}"
            }
            // GAME_OVER can race with the action worker.  The phase handler
            // has already cancelled tasks, but a search result may have been
            // selected just before that cancellation became visible here.
            // Do not send a stale click and do not report a normal terminal
            // race as a strategy error.
            if (!war.isMyTurn) {
                log.info {
                    "MCTS_EXPERIMENT_ACTION_IGNORED strategy=${name()} " +
                        "action=${describeAction(action)} reason=turn-ended-before-dispatch"
                }
                MctsReplayTrace.record(
                    war,
                    "controller_branch",
                    "turn-ended-before-dispatch",
                    mapOf("strategy" to name(), "step" to actionCount + 1, "action" to describeAction(action)),
                )
                break
            }
            try {
                action.exec.accept(war)
                MctsReplayTrace.record(
                    war,
                    "action_dispatch_returned",
                    "experimental action callback returned",
                    mapOf(
                        "strategy" to name(),
                        "step" to actionCount + 1,
                        "action" to describeAction(action),
                        "stateChangedImmediately" to (stateFingerprint(war) != before),
                    ),
                )
            } catch (error: Throwable) {
                if (!war.isMyTurn) {
                    log.info {
                        "MCTS_EXPERIMENT_ACTION_IGNORED strategy=${name()} " +
                            "action=${describeAction(action)} reason=turn-ended-during-dispatch"
                    }
                } else {
                    log.error(error) {
                        "MCTS_EXPERIMENT_ACTION_FAILED strategy=${name()} action=${describeAction(action)}"
                    }
                }
                MctsReplayTrace.record(
                    war,
                    "controller_branch",
                    if (!war.isMyTurn) "action-failed-after-turn-ended" else "action-dispatch-exception",
                    mapOf("strategy" to name(), "step" to actionCount + 1, "action" to describeAction(action), "error" to error::class.java.simpleName),
                )
                break
            }
            actionCount++
            lastExperimentalTurnProducedAction = true

            if (!awaitStateChange(war, before, turnDeadline)) {
                lastExperimentalTurnHadUnconfirmedDispatch = true
                MctsReplayTrace.record(
                    war,
                    "action_unconfirmed",
                    "no observable state change arrived before the bounded re-plan deadline",
                    mapOf(
                        "strategy" to name(),
                        "step" to actionCount,
                        "action" to describeAction(action),
                        "stateBefore" to before,
                    ),
                )
                val creatorId = action.creator?.entityId?.takeIf { it.isNotBlank() }
                if (creatorId != null) {
                    // A stale parser snapshot can expose an action that the
                    // live Hearthstone client has already rejected (for
                    // example a minion whose attack was spent in the prior
                    // action).  Do not retry that same creator forever and
                    // exhaust the outer turn-end guard; hide it only for the
                    // remainder of this turn and let MCTS choose another
                    // currently visible action.
                    synchronized(this) {
                        suppressedExperimentalCreatorIds += creatorId
                    }
                    blockedCreatorIds += creatorId
                    log.info {
                        "MCTS_EXPERIMENT_ACTION_SUPPRESSED strategy=${name()} " +
                            "creator=${describeActionCard(action.creator!!)} step=$actionCount " +
                            "reason=no-state-change-after-dispatch;replan-without-creator"
                    }
                    MctsReplayTrace.record(
                        war,
                        "controller_branch",
                        "action-unconfirmed-creator-suppressed-and-replanned",
                        mapOf("strategy" to name(), "step" to actionCount, "creatorId" to creatorId, "action" to describeAction(action)),
                    )
                    continue
                }
                log.info {
                    "MCTS_EXPERIMENT_REPLAN_STOP strategy=${name()} reason=状态未确认变化 " +
                        "step=$actionCount"
                }
                MctsReplayTrace.record(
                    war,
                    "controller_branch",
                    "action-unconfirmed-without-creator-stopped-replan",
                    mapOf("strategy" to name(), "step" to actionCount, "action" to describeAction(action)),
                )
                break
            }
            MctsReplayTrace.record(
                war,
                "action_confirmed",
                "Power.log/state fingerprint confirmed the dispatched action",
                mapOf(
                    "strategy" to name(),
                    "step" to actionCount,
                    "action" to describeAction(action),
                    "stateChanged" to (stateFingerprint(war) != before),
                ),
            )
        }
        MctsReplayTrace.record(
            war,
            "turn_controller_done",
            "MCTS finished its bounded action loop; app-side guard decides whether ending the turn is safe",
            mapOf(
                "strategy" to name(),
                "actions" to actionCount,
                "remainingMana" to war.me.usableResource,
                "remainingHand" to war.me.handArea.cards.map { describeActionCard(it) },
            ),
        )
        MctsReplayTrace.record(
            war,
            "controller_branch",
            when {
                !war.isMyTurn -> "turn-ended-during-controller-loop"
                System.currentTimeMillis() >= turnDeadline -> "turn-budget-expired"
                actionCount >= 16 -> "action-count-cap-reached"
                else -> "controller-loop-exited-without-terminal-condition"
            },
            mapOf("strategy" to name(), "actions" to actionCount, "remainingMana" to war.me.usableResource),
        )
        log.info { "MCTS_EXPERIMENT_TURN_DONE strategy=${name()} actions=$actionCount" }
    }

    private fun awaitStateChange(war: War, before: String, turnDeadline: Long): Boolean {
        // Hearthstone can acknowledge the click visually before the matching
        // Power.log entity updates reach the parser.  Two seconds was short
        // enough to re-submit a successful hero attack and eventually exhaust
        // the outer turn-end replan guard.  Keep this bounded by the turn
        // budget, but allow a normal animation/logging round-trip to settle.
        val waitDeadline = minOf(turnDeadline, System.currentTimeMillis() + 8_000L)
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
     * Temporarily removes a creator whose last live dispatch produced no
     * observable state change.  Delegation preserves every deck-specific
     * timing, mandatory-chain, scoring, and opaque-action hook.
     */
    private class TemporarilyBlockedActionModel(
        private val delegate: MctsDecisionModel,
        private val blockedCreatorIds: Set<String>,
    ) : MctsDecisionModel by delegate {
        private fun isBlocked(action: Action): Boolean =
            action.creator?.entityId?.let(blockedCreatorIds::contains) == true

        override fun isMandatoryAction(action: Action, war: War): Boolean =
            !isBlocked(action) && delegate.isMandatoryAction(action, war)

        override fun isDeferredAction(action: Action, war: War): Boolean =
            isBlocked(action) || delegate.isDeferredAction(action, war)
    }

    /**
     * 通过mcts算法出牌
     * @return mcts算法参数，返回几个参数就执行几次
     */
    abstract fun executeMCTSOutCard(war: War): List<MCTSArg>
}
