package club.xiaojiawei.hsscript.strategy

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.bean.GameRect
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import club.xiaojiawei.hsscript.status.ActionDispatchGate
import club.xiaojiawei.hsscript.status.E2ETrace
import club.xiaojiawei.hsscript.status.MctsDeckProfileTelemetry
import club.xiaojiawei.hsscript.status.Mode
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.UnknownStateScreenshot
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.MulliganScreenshot
import club.xiaojiawei.hsscript.utils.MctsRoundScreenshot
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscript.utils.go
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.enums.StepEnum
import club.xiaojiawei.hsscriptbase.util.RandomUtil
import club.xiaojiawei.hsscriptbase.util.isFalse
import club.xiaojiawei.hsscriptbase.util.isTrue
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.isValid
import club.xiaojiawei.hsscriptcardsdk.bean.safeRun
import club.xiaojiawei.hsscriptcardsdk.data.COIN_CARD_ID
import club.xiaojiawei.hsscriptcardsdk.data.BaseData
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsReplayTrace
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import club.xiaojiawei.hsscriptstrategysdk.TimelineEvent
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import club.xiaojiawei.hsscriptstrategysdk.deck.MCTSDeckStrategy
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum

/**
 * 卡牌策略执行器
 * @author 肖嘉威
 * @date 2022/11/29 17:29
 */
object DeckStrategyActuator {

    private val war = WAR
    private const val MAX_MCTS_TURN_END_REPLANS = 2

    fun reset() {
        DeckStrategyManager.currentDeckStrategy?.reset()
        checkSurrender()
    }

    fun randEmoji() {
        if (PowerLogListener.replayingExistingLog) return
        if (!canExec()) return

        (RandomUtil.nextBoolean()).isTrue {
            (RandomUtil.nextBoolean()).isTrue {
                GameUtil.sendThankEmoji()
            }.isFalse {
                GameUtil.sendGreetEmoji()
            }
        }
    }

    /**
     * 非本人回合随机做点事情
     */
    fun randomDoSomething() {
        if (PowerLogListener.replayingExistingLog) return
        if (!canExec()) return

        if (RandomUtil.nextBoolean()) {
            log.info { "随机做点事情" }
            SystemUtil.delay(RandomUtil.getRandomAround(2_000, 250))
            val minTime = 5000
            val maxTime = 12000
            while (!PauseStatus.isPause && !war.isMyTurn && !Thread.interrupted() && Mode.currMode === ModeEnum.GAMEPLAY) {
                var toList = war.rival.playArea.cards.toList()
                for (card in toList) {
                    if (RandomUtil.nextBoolean()) {
                        card.action.lClick()
                        log.info { "点击敌方战场卡牌：${card}" }
                    }
                    SystemUtil.delay(minTime, maxTime)
                }
                SystemUtil.delay(minTime, maxTime)
                if (RandomUtil.nextBoolean()) {
                    war.rival.playArea.hero?.action?.lClick()
                    log.info { "点击敌方英雄" }
                }
                SystemUtil.delay(minTime, maxTime)
                if (RandomUtil.nextBoolean()) {
                    war.rival.playArea.power?.action?.lClick()
                    log.info { "点击敌方英雄技能" }
                }
                SystemUtil.delay(minTime, maxTime)
                toList = war.me.playArea.cards.toList()
                for (card in toList) {
                    if (RandomUtil.nextBoolean()) {
                        card.action.lClick()
                        log.info { "点击我方战场卡牌：${card}" }
                    }
                    SystemUtil.delay(minTime, maxTime)
                }
                SystemUtil.delay(minTime, maxTime)
            }
        }
    }

    fun changeCard(): Boolean {
        log.info { "收到自动换牌执行请求" }
        if (!ConfigUtil.getBoolean(ConfigEnum.STRATEGY)) {
            log.warn { "自动换牌请求被跳过：策略执行开关未开启" }
            return false
        }

        // The INPUT event can arrive while the initial hand/player model is
        // still being populated. Wait first, then validate the live state;
        // checking validPlayer() before this delay can make the thread exit
        // before it ever clicks the mulligan UI.
        val distortionEnabled = ConfigUtil.getBoolean(ConfigEnum.DISTORTION)
        val mulliganDelay = RandomUtil.getMulliganDelay(distortionEnabled)
        log.info { "自动换牌等待${mulliganDelay}毫秒（畸变：$distortionEnabled）" }
        SystemUtil.delay(mulliganDelay)
        val canExecute = canExec()
        log.info { "自动换牌延迟结束，当前状态可执行：$canExecute" }
        if (!canExecute) {
            log.warn { "自动换牌请求被跳过：延迟后当前策略不可执行" }
            return false
        }

        if (PauseStatus.isPause) return false
        log.info { "执行换牌策略" }
        war.run {
            log.info { "1号玩家牌库数量：" + player1.deckArea.cards.size }
            log.info { "2号玩家牌库数量：" + player2.deckArea.cards.size }
        }

        val me = war.me
        try {
            val copyHandCards = HashSet(me.handArea.cards)
            copyHandCards.removeIf { it.cardId == COIN_CARD_ID }

            val activeStrategy = DeckStrategyManager.currentDeckStrategy
            activeStrategy?.executeChangeCard(copyHandCards)

            // The public base strategies implement this same rule, but a
            // strategy plugin can accidentally leave an expensive card in
            // the keep set. Keep the normal mulligan contract at the
            // actuator boundary for every normal game: cards costing 3+
            // are always replaced and cards costing 0-2 are always kept.
            // A strategy that explicitly requests surrender is exempt because
            // it does not need a playable opening hand.
            val isSurrenderStrategy = activeStrategy?.needSurrender == true
            if (!isSurrenderStrategy) {
                val forcedExpensiveCards = copyHandCards.filter { it.cost > 2 }
                if (forcedExpensiveCards.isNotEmpty()) {
                    forcedExpensiveCards.forEach(copyHandCards::remove)
                    log.warn {
                        "换牌规则：正常局强制换掉费用>2的${forcedExpensiveCards.size}张牌，费用<=2保留"
                    }
                }
            }
            log.info {
                val policy = when {
                    isSurrenderStrategy -> "投降策略不换牌"
                    BaseData.enableChangeWeight -> "按换牌权重"
                    else -> "费用>2换掉、费用<=2保留"
                }
                "换牌规则：策略=${activeStrategy?.name() ?: "无"}，$policy"
            }
            val cardsToReplace = me.handArea.cards.filter { card ->
                card.cardId != COIN_CARD_ID && !copyHandCards.contains(card)
            }
            val cardsToKeep = me.handArea.cards.filter { card ->
                card.cardId != COIN_CARD_ID && copyHandCards.contains(card)
            }
            log.info {
                "换牌选择：换掉=${cardsToReplace.joinToString { mulliganCardLabel(it) }}；" +
                    "保留=${cardsToKeep.joinToString { mulliganCardLabel(it) }}"
            }
            // Match the public implementation's coordinate contract: keep
            // the original hand index and use the live hand size to select
            // the three- or four-card opening-hand layout. The coin is not a
            // replacement candidate, but it must not be removed before the
            // visual index is calculated.
            val handCards = me.handArea.cards.toList()
            val handSize = me.handArea.cardSize()
            val visibleMulliganCards = handCards.filter { it.cardId != COIN_CARD_ID }
            val mulliganUsesFourSlotLayout = handSize >= 4
            val mulliganLayout = when {
                mulliganUsesFourSlotLayout -> "FOUR_SLOT_WITH_COIN_OR_FULL_HAND"
                visibleMulliganCards.size == 3 -> "THREE_DISCOVER"
                else -> "HAND_FALLBACK"
            }
            val mulliganCardRect: (Int) -> GameRect = { handIndex ->
                when {
                    mulliganUsesFourSlotLayout -> GameUtil.getFourDiscoverCardRect(handIndex.coerceIn(0, 3))
                    handSize == 3 -> GameUtil.getThreeDiscoverCardRect(handIndex.coerceIn(0, 2))
                    else -> GameUtil.getMyHandCardRect(handIndex, handSize)
                }
            }
            val mulliganCardIndices = handCards.indices.filter { handCards[it].cardId != COIN_CARD_ID }
            val mulliganClickPositions = mulliganCardIndices.map { mulliganCardRect(it).getCenterClickPos() }
            val mulliganUiReady = MulliganScreenshot.awaitInteractiveHand(mulliganClickPositions)
            if (!mulliganUiReady) {
                log.warn {
                    "换牌UI未检测到目标数量的稳定可交互卡牌边框，放弃本次盲点输入和确认，等待阶段超时保护"
                }
                return false
            }
            // Capture only after the visual readiness probe so this evidence
            // is the real opening-hand screen rather than the transition out
            // of matchmaking.
            MulliganScreenshot.capture("before-selection", WarEx.warCount + 1)
            for (handIndex in handCards.indices) {
                val card = handCards[handIndex]
                if (card.cardId == COIN_CARD_ID) continue
                if (!copyHandCards.contains(card) && mulliganUiReady) {
                    log.info {
                        "换掉起始卡牌：【entityId:${card.entityId}，entityName:${card.entityName}，" +
                            "cardId:${card.cardId}，cost=${card.cost}】"
                    }
                    // The opening cards are centered like a discover choice;
                    // the normal fanned-hand coordinates are only valid after
                    // the mulligan stage has completed.
                    log.info {
                        "MULLIGAN_ACTION cardId=${card.cardId} cost=${card.cost} " +
                            "index=$handIndex handSize=$handSize visibleSize=${visibleMulliganCards.size} " +
                            "layout=$mulliganLayout target=INITIAL_HAND_CARD"
                    }
                    val target = mulliganCardRect(handIndex)
                    val clickPos = target.getCenterClickPos()
                    // Use the original public action path. It preserves the
                    // original hand index/layout mapping and calls GameRect
                    // lClick(), which is the upstream input primitive.
                    GameUtil.chooseDiscoverCard(handIndex, handSize)
                    var selectionVerified = MulliganScreenshot.awaitCardSelected(clickPos)
                    log.info {
                        "MULLIGAN_INPUT_SENT cardId=${card.cardId} index=$handIndex " +
                            "pos=(${clickPos.x},${clickPos.y}) accepted=true verifiedRedX=$selectionVerified"
                    }
                    if (!selectionVerified) {
                        log.warn {
                            "MULLIGAN_INPUT_ABORTED cardId=${card.cardId} index=$handIndex reason=red-x-not-observed"
                        }
                        return false
                    }
                    MulliganScreenshot.capture("after-selection-$handIndex", WarEx.warCount + 1)
                    SystemUtil.delayShortMedium()
                }
            }
            log.info {
                "MULLIGAN_DECISION_SUBMITTED replace=${cardsToReplace.size} " +
                    "keep=${cardsToKeep.size} rule=cost>2-replace,cost<=2-keep"
            }
            log.info { "换牌选择已完成，按上游流程执行确认动作" }
            log.info { "执行换牌策略完毕" }
            checkSurrender()
            return true
        } finally {
            // Preserve the upstream confirm cleanup. The phase can transition
            // before the worker sees it; these clicks target only the confirm
            // control and use the shared randomized short delay.
            try {
                for (i in 0..2) {
                    if (Thread.currentThread().isInterrupted) break
                    GameUtil.CONFIRM_RECT.lClick(false)
                    SystemUtil.delayShort()
                }
                if (!Thread.currentThread().isInterrupted) {
                    GameUtil.CENTER_RECT.lClick(false)
                }
                log.info { "自动换牌确认已提交" }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn { "自动换牌确认被中断" }
            } catch (e: Throwable) {
                log.warn(e) { "自动换牌确认清理动作失败" }
            }
        }
    }

    fun outCard() {
        if (!canExec()) return

        E2ETrace.markOutCardStarted()

        if (PowerLogListener.replayingExistingLog) {
            log.info { "E2E恢复回放：跳过历史出牌与回合收尾点击" }
            return
        }

        if (Mode.currMode !== ModeEnum.GAMEPLAY) {
            // A replayed/stale Power.log can leave WarEx.inWar=true while the
            // visible client is on another screen or another live match. Do
            // not let a stale model send MCTS clicks into that screen.
            log.info {
                "MCTS_INPUT_DEFERRED reason=mode-not-gameplay " +
                    "mode=${Mode.currMode?.name ?: "NONE"} " +
                    "powerLog=${PowerLogListener.logFile?.path()}"
            }
            return
        }

        val surrenderNumber = ConfigUtil.getInt(ConfigEnum.OVER_TURN_SURRENDER)

        if (surrenderNumber >= 0 && war.me.turn >= surrenderNumber) {
            log.info { "到达投降回合-[${surrenderNumber}]" }
            GameUtil.surrender()
            return
        }

        // 等待动画结束
        SystemUtil.delay(RandomUtil.getTurnStartDelay())
        if (!war.isMyTurn || PauseStatus.isPause) return

        // The E2E runner must verify the complete game-over path without
        // depending on an opponent or an expensive late-game search. This is
        // deliberately opt-in and never affects normal runs.
        if (System.getProperty("hs.script.e2e.surrender-after-out-card") == "true") {
            log.info { "E2E专用：已进入有效我方出牌阶段，执行脚本投降以完成对局闭环" }
            GameUtil.surrender()
            return
        }

        var strategyForTurn: DeckStrategy? = null
        try {
            val strategy = DeckStrategyManager.currentDeckStrategy
            strategyForTurn = strategy
            if (strategy is MCTSDeckStrategy) {
                MctsDeckProfileTelemetry.observe(war, strategy)
            }
            war.me.safeRun {
                log.info {
                    "决策：${strategy?.name() ?: "无策略"}，水晶=${it.usableResource}，手牌=${it.handArea.cards.size}"
                }
            }
            try {
                strategy?.executeOutCard()
                log.info { "决策：本回合动作已提交" }
                checkSurrender()
            } catch (t: Throwable) {
                // A strategy/plugin must not strand the game on our turn.
                // Keep the common cleanup/end-turn path alive and retain the
                // original failure in the script log for diagnosis.
                if (isExpectedTurnExit(t)) {
                    log.info { "回合已在策略执行期间结束，跳过剩余出牌动作" }
                } else {
                    log.error(t) { "执行出牌策略异常，继续执行回合收尾" }
                    val evidence = UnknownStateScreenshot.capture(
                        category = UnknownStateScreenshot.CATEGORY_ACTION_FAILURE,
                        trigger = "deck-strategy-exception",
                        state = "mode=${Mode.currMode?.name ?: "NONE"}|turn=${war.me.turn}",
                        phase = "game-turn",
                        label = "action-failure-screen",
                    )
                    log.warn {
                        "ACTION_FAILURE_SCREENSHOT path=${evidence?.file?.absolutePath ?: "not-saved"} " +
                            "link=${evidence?.link ?: "none"}"
                    }
                }
            }
        } finally {
            runCatching {
                if (strategyForTurn is MCTSDeckStrategy) {
                    finishMctsTurn(strategyForTurn as MCTSDeckStrategy)
                } else if (TurnEndActionGuard.ensureSafeToEndTurn()) {
                    clickEndTurnUntilTransition()
                } else {
                    log.warn { "回合收尾被可行动作守卫阻止：本次不点击结束回合" }
                }
            }.onFailure {
                if (isExpectedTurnExit(it)) {
                    log.info { "回合收尾随阶段切换中断，按正常结束处理" }
                } else {
                    log.error(it) { "回合收尾操作异常" }
                }
            }
        }
    }

    /**
     * MCTS is the sole action owner for MCTS strategies. The generic guard
     * remains available for legacy strategies, but it must not independently
     * play cards or attack after MCTS has returned a stale/empty path.
     */
    private fun finishMctsTurn(strategy: MCTSDeckStrategy) {
        var replans = 0
        var clearYellowRetries = 0
        while (war.isMyTurn && !PauseStatus.isPause) {
            if (Mode.currMode !== ModeEnum.GAMEPLAY || PowerLogListener.replayingExistingLog) {
                log.info {
                    "MCTS_TURN_END_DEFERRED turn=${war.me.turn} " +
                        "reason=mode-or-replay-boundary"
                }
                return
            }
            // Re-read every live action immediately before End Turn.  This is
            // intentionally a second, independent pass after MCTS returns:
            // animations, random summons, dynamic costs, and stale parser
            // entities can all change the action set during the handoff.
            val liveActionableCreatorIds = strategy.actionableCreatorIds(
                war,
                purpose = "turn-end-full-rescan",
            )
            val inspection = TurnEndActionGuard.inspectForMctsEndTurn(
                clearYellowRetries = clearYellowRetries,
                ignoredCreatorIds = strategy.suppressedExperimentalCreatorIds(),
                mctsActionableCreatorIds = liveActionableCreatorIds,
            )
            if (inspection.safeToEnd) {
                val completedTurn = war.me.turn
                MctsReplayTrace.record(
                    war,
                    "turn_end_selected",
                    "the app-side guard accepted EndTurn after MCTS exhausted or deferred live actions",
                    mapOf(
                        "strategy" to strategy.name(),
                        "safeToEnd" to inspection.safeToEnd,
                        "requiresReplan" to inspection.requiresReplan,
                        "buttonColor" to inspection.buttonColor.name,
                        "mctsActionableCreatorIds" to liveActionableCreatorIds,
                        "fullRescan" to true,
                        "remainingMana" to war.me.usableResource,
                        "hand" to war.me.handArea.cards.map { "${it.cardId}:${it.entityName}(cost=${it.cost})" },
                    ),
                )
                MctsRoundScreenshot.capture(war, completedTurn)
                clickEndTurnUntilTransition()
                return
            }

            if (!inspection.requiresReplan) {
                if (inspection.buttonColor == TurnEndActionGuard.EndTurnButtonColor.YELLOW) {
                    clearYellowRetries++
                }
                SystemUtil.delayShortMedium()
                continue
            }

            if (replans >= MAX_MCTS_TURN_END_REPLANS) {
                val evidence = UnknownStateScreenshot.capture(
                    category = UnknownStateScreenshot.CATEGORY_TURN_END_STUCK,
                    trigger = "mcts-turn-end-replan-exhausted",
                    state = "mode=${Mode.currMode?.name ?: "NONE"}|turn=${war.me.turn}",
                    phase = "game-turn-end",
                    label = "turn-end-replan-exhausted",
                )
                log.error {
                    "MCTS_TURN_END_REPLAN_EXHAUSTED turn=${war.me.turn} replans=$replans " +
                        "remainingActions=true; no legacy fallback action was dispatched " +
                        "screenshot=${evidence?.file?.absolutePath ?: "not-saved"} " +
                        "screenshotLink=${evidence?.link ?: "none"}"
                }
                return
            }

            replans++
            log.warn {
                "MCTS_TURN_END_REPLAN turn=${war.me.turn} attempt=$replans/$MAX_MCTS_TURN_END_REPLANS " +
                    "reason=live-state-still-actionable-after-strategy-return"
            }
            SystemUtil.delayShortMedium()
            strategy.executeOutCard()
        }
    }

    private fun clickEndTurnUntilTransition() {
        GameUtil.cancelAction()
        for (i in 0 until 20) {
            if (!war.isMyTurn) break
            if (i > 3) {
                GameUtil.getThreeDiscoverCardRect(0).lClick()
                SystemUtil.delayShortMedium()
            }
            GameUtil.lClickTurnOver(false)
            SystemUtil.delayShortMedium()
        }
    }

    private fun isExpectedTurnExit(t: Throwable): Boolean =
        t is InterruptedException ||
            t is java.util.concurrent.CancellationException ||
            Thread.currentThread().isInterrupted ||
            !war.isMyTurn ||
            Mode.currMode !== ModeEnum.GAMEPLAY

    fun resumeAfterExistingLogReplay() {
        if (PowerLogListener.replayingExistingLog
            || war.currentPhase !== WarPhaseEnum.GAME_TURN
            || war.currentTurnStep !== StepEnum.MAIN_ACTION
            || war.currentPlayer !== war.me
            || !war.me.isValid()
        ) {
            return
        }

        war.isMyTurn = true
        E2ETrace.markOurTurnSeen()
        log.info { "E2E恢复回放完成：当前仍是我方行动阶段，补执行一次出牌策略" }
        go {
            outCard()
        }
    }

    fun discoverChooseCard(cards: List<Card>) {
        if (!canExec()) return

        if (PowerLogListener.replayingExistingLog) {
            log.info { "E2E恢复回放：跳过历史发现选牌点击" }
            return
        }

        log.info { "执行发现选牌策略" }

        SystemUtil.delayMedium()
        var index = -1
        try {
            index = (DeckStrategyManager.currentDeckStrategy?.executeDiscoverChooseCard(*cards.toTypedArray())
                ?: 0).coerceIn(0, cards.size - 1)
        } catch (e: Exception) {
            log.error(e) { "执行发现选择策略异常" }
        } finally {
            if (index == -1) {
                index = 0
                GameUtil.chooseDiscoverCard(index, cards.size)
            }
        }
        val card = cards[index]
        war.me.let {
            GameUtil.chooseDiscoverCard(index, cards.size)
            SystemUtil.delayShort()
        }
        log.info { "执行发现选牌策略完毕，选择第${index + 1}张，${card}" }

        checkSurrender()
    }

    fun chooseTimeLine(timeLineEvent: TimelineEvent) {
        if (!canExec()) return

        if (PowerLogListener.replayingExistingLog) {
            log.info { "E2E恢复回放：跳过历史时间线点击" }
            return
        }

        log.info { "执行时间线选择" }

        SystemUtil.delayMedium()
        try {
            DeckStrategyManager.currentDeckStrategy?.execChooseTimeLine(timeLineEvent)
        } catch (e: Exception) {
            log.error(e) { "执行时间线选择异常" }
        }

        if (timeLineEvent.isKeepTime()) GameUtil.keepTimeline() else {
            GameUtil.rewindTimeline()
            SystemUtil.delayHuge()
        }

        log.info { "执行时间线选择完毕，选择${timeLineEvent.chooseEventCard}" }

        checkSurrender()
    }

    private fun canExec(): Boolean {
        return ActionDispatchGate.allow("strategy-dispatch") &&
            ConfigUtil.getBoolean(ConfigEnum.STRATEGY) && validPlayer() && !checkSurrender()
    }

    private fun validPlayer(): Boolean {
        if (!war.rival.isValid() && war.me.isValid()) {
            log.warn { "玩家无效" }
            return false
        }
        return true
    }

    /**
     * Keep the operational mulligan summary on one line. Hearthstone often
     * reports hidden cards as UNKNOWN ENTITY with embedded newlines; logging
     * that raw value makes the UI look like a burst of unrelated errors.
     * The full card/entity details remain in the file log from the per-card
     * diagnostic below.
     */
    private fun mulliganCardLabel(card: Card): String {
        val cardId = card.cardId.ifBlank { "UNKNOWN" }
        val name = card.entityName.replace(Regex("\\s+"), " ").trim()
        val displayName = if (name.isBlank() || name.startsWith("UNKNOWN ENTITY")) {
            cardId
        } else {
            "$name/$cardId"
        }
        return "$displayName(cost=${card.cost})"
    }

    private fun checkSurrender(): Boolean {
        DeckStrategyManager.currentDeckStrategy?.let {
            if (it.needSurrender) {
                go {
                    log.info { "策略请求投降" }
                    GameUtil.surrender()
                }
                it.needSurrender = false
                return true
            }
        }
        return false
    }

}
