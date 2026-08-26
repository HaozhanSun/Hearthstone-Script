package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptbase.util.RandomUtil
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.isValid
import club.xiaojiawei.hsscriptcardsdk.data.CARD_DATA_TRIE
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.CardTimingPolicy
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy

/**
 * A deliberately explicit starting point for a Pirate Demon Hunter deck.
 *
 * Card identity comes from the card ID (with the entity name as a diagnostic
 * fallback), not from attack/health values.  The expected stats below are
 * documentation and a useful perception warning; they are not used to infer
 * a card's identity because buffs and enchantments can change them in play.
 *
 * Add future hard-coded rules to [FIRST_PRIORITY_RULES],
 * [SECOND_PRIORITY_RULES], and [LOWEST_PRIORITY_CARD_IDS].
 */
class HsPirateDemonHunterDeckStrategy : DeckStrategy() {

    private data class CardRule(
        val label: String,
        val cardId: String,
        val entityName: String,
        val expectedCost: Int,
        val expectedAttack: Int,
        val expectedHealth: Int,
        val priority: Int,
    ) {
        fun matches(card: Card): Boolean =
            card.cardId == cardId ||
                card.cardId.contains(cardId) ||
                card.entityName == entityName
    }

    private data class DeferredPriorityCard(
        val rule: CardRule,
        val card: Card,
    )

    companion object {
        private const val TREASURE_DISTRIBUTOR_ID = "TOY_518"
        private const val SHIPS_CANNON_ID = "GVG_075"

        /** Highest priority: play this before every other playable card. */
        private val FIRST_PRIORITY_RULES = listOf(
            CardRule(
                label = "宝藏经销商",
                cardId = TREASURE_DISTRIBUTOR_ID,
                entityName = "宝藏经销商",
                expectedCost = 1,
                expectedAttack = 1,
                expectedHealth = 2,
                priority = 1_000,
            ),
        )

        /** Second priority: below [FIRST_PRIORITY_RULES], above all other cards. */
        private val SECOND_PRIORITY_RULES = listOf(
            CardRule(
                label = "船载火炮",
                cardId = SHIPS_CANNON_ID,
                entityName = "船载火炮",
                expectedCost = 2,
                expectedAttack = 2,
                expectedHealth = 3,
                priority = 900,
            ),
        )

        /**
         * Future cards that should be held until every other useful action is
         * exhausted.  Keep this ID-based so this remains editable and robust
         * when two cards have the same cost and stats.
         */
        internal fun shouldDeferPriorityCard(cardCost: Int, currentMana: Int, coinAvailable: Boolean): Boolean =
            coinAvailable && cardCost > currentMana && cardCost <= currentMana + 1

        internal fun shouldMulligan(card: Card): Boolean =
            CardTimingPolicy.isPatchesThePirate(card) || card.cost > 2
    }

    private val allRules: List<CardRule> = FIRST_PRIORITY_RULES + SECOND_PRIORITY_RULES

    override fun name(): String = "海盗瞎激进"

    override fun description(): String =
        "海盗瞎的硬编码激进策略：宝藏经销商优先，船载火炮第二，其余规则可继续添加"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.CASUAL, RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.PRACTICE)

    /** Optional deck code can be filled in later without changing the strategy ID. */
    override fun deckCode(): String = ""

    override fun id(): String = "e71234fa-6-pirate-demon-hunter-97e9-1f4e126cd33b"

    override fun referWeight(): Boolean = true

    override fun referPowerWeight(): Boolean = true

    override fun referChangeWeight(): Boolean = true

    override fun referCardInfo(): Boolean = true

    override fun executeChangeCard(cards: HashSet<Card>) {
        // Keep the working upstream mulligan baseline: retain 0/1/2-cost
        // cards and replace cards costing 3 or more. Patches is an explicit
        // exception: it is always thrown back because it is meant to be
        // pulled from the deck by a played Pirate, not kept in the opener.
        cards.filter(CardTimingPolicy::isPatchesThePirate).forEach { patches ->
            log.info {
                "MULLIGAN_FORCE_REPLACE card=${patches.entityName.ifBlank { patches.cardId }} " +
                    "cardId=${patches.cardId} reason=海盗帕奇斯应由海盗从牌库召唤"
            }
        }
        cards.removeIf(::shouldMulligan)
    }

    override fun executeOutCard() {
        val me = WAR.me
        if (!me.isValid()) return

        try {
            val handAtStart = me.handArea.cards.toList()
            DecisionTrace.record(
                war = WAR,
                event = "decision_cycle_started",
                reason = "开始计算本回合出牌顺序",
                hand = handAtStart,
                outcome = "evaluating",
            )

            // Resolve rules in strict descending priority.  Re-read live
            // resources for every candidate: a previous click may already
            // have consumed the last crystal even though the turn-start
            // snapshot still contained mana.
            val priorityEntityIds = mutableSetOf<String>()
            val deferredPriorityCards = mutableListOf<DeferredPriorityCard>()
            for (rule in allRules) {
                val card = me.handArea.cards.toList().firstOrNull { rule.matches(it) }
                if (card == null) {
                    DecisionTrace.record(
                        war = WAR,
                        event = "candidate_evaluated",
                        reason = "优先级规则没有匹配到手牌",
                        rule = rule.label,
                        priority = rule.priority,
                        outcome = "no_candidate",
                    )
                    continue
                }

                logRuleObservation(rule, card)
                val currentMana = WAR.me.usableResource
                if (!isPlayable(card, currentMana, WAR.me.playArea.isFull)) {
                    recordCandidateSkipped(card, "优先级规则当前不可出牌", rule.label, currentMana)
                    // If one coin can make this priority card playable, defer
                    // it until after the coin instead of permanently removing
                    // it from the candidate set. Otherwise a 2-cost Ship's
                    // Cannon at 1 mana can be skipped, then a coin can raise
                    // mana to 2 and the generic sorter can incorrectly play a
                    // same-cost card such as 空降歹徒 first.
                    if (
                        shouldDeferPriorityCard(
                            cardCost = card.cost,
                            currentMana = currentMana,
                            coinAvailable = me.handArea.cards.any { it.isCoinCard },
                        )
                    ) {
                        deferredPriorityCards += DeferredPriorityCard(rule, card)
                        log.info {
                            "海盗恶魔猎手：延迟优先级牌 ${DecisionTrace.displayName(card)}，等待硬币后重新评估"
                        }
                    }
                    continue
                }

                val selection = DecisionTrace.record(
                    war = WAR,
                    event = "decision_selected",
                    reason = "匹配到硬编码优先级规则，按优先级从高到低出牌",
                    candidate = card,
                    rule = rule.label,
                    priority = rule.priority,
                    outcome = "selected",
                    action = "play_card",
                )
                log.info { "海盗恶魔猎手：决策#${selection.sequence} 出牌 ${DecisionTrace.displayName(card)}，原因=${rule.label}优先级${rule.priority}，当前法力=${currentMana}" }
                if (dispatchCardAction(card, selection.sequence, "执行硬编码优先级出牌 ${rule.label}")) {
                    priorityEntityIds += card.entityId
                }
            }

            if (deferredPriorityCards.isNotEmpty()) {
                val coin = me.handArea.cards.toList().firstOrNull {
                    it.isCoinCard && isPlayable(it, WAR.me.usableResource, WAR.me.playArea.isFull)
                }
                if (coin != null) {
                    val coinSelection = DecisionTrace.record(
                        war = WAR,
                        event = "decision_selected",
                        reason = "硬币可使更高优先级牌变为可出牌，先使用硬币",
                        candidate = coin,
                        rule = "硬币-释放优先级牌",
                        priority = 950,
                        outcome = "selected",
                        action = "play_card",
                    )
                    log.info {
                        "海盗恶魔猎手：决策#${coinSelection.sequence} 出牌 ${DecisionTrace.displayName(coin)}，" +
                            "原因=硬币为优先级牌释放法力，当前法力=${WAR.me.usableResource}"
                    }
                    val coinDispatched = dispatchCardAction(coin, coinSelection.sequence, "硬币为优先级牌释放法力")
                    if (!coinDispatched) {
                        log.warn { "海盗恶魔猎手：硬币派发未被接受，跳过硬币后优先级重评估" }
                    } else {
                        priorityEntityIds += coin.entityId
                        Thread.sleep(RandomUtil.getActionInterval(1000).toLong())

                        // Re-read the hand and mana after the accepted coin action;
                        // do not trust the turn-start Card object or resource snapshot.
                        for (deferred in deferredPriorityCards) {
                            val liveCard = me.handArea.cards.toList().firstOrNull {
                                it.entityId == deferred.card.entityId
                            } ?: continue
                            val currentMana = WAR.me.usableResource
                            if (!isPlayable(liveCard, currentMana, WAR.me.playArea.isFull)) {
                                recordCandidateSkipped(
                                    liveCard,
                                    "硬币后重新评估仍不可出牌",
                                    deferred.rule.label,
                                    currentMana,
                                )
                                continue
                            }
                            val selection = DecisionTrace.record(
                                war = WAR,
                                event = "decision_selected",
                                reason = "硬币后重新评估硬编码优先级规则",
                                candidate = liveCard,
                                rule = deferred.rule.label,
                                priority = deferred.rule.priority,
                                outcome = "selected",
                                action = "play_card",
                            )
                            log.info {
                                "海盗恶魔猎手：决策#${selection.sequence} 出牌 ${DecisionTrace.displayName(liveCard)}，" +
                                    "原因=硬币后重新评估${deferred.rule.label}优先级${deferred.rule.priority}，当前法力=${currentMana}"
                            }
                            if (dispatchCardAction(
                                    liveCard,
                                    selection.sequence,
                                    "硬币后重新评估硬编码优先级出牌 ${deferred.rule.label}",
                                )
                            ) {
                                priorityEntityIds += liveCard.entityId
                            }
                        }
                    }
                }
            }

            // The remaining cards are intentionally simple and editable:
            // normal cards are attempted first, while cards listed in the
            // low-priority placeholder are attempted last.
            val remainingCards = me.handArea.cards.toList().filterNot { card ->
                priorityEntityIds.contains(card.entityId)
            }
            val lowestPriorityPresent = remainingCards.filter { card ->
                CardTimingPolicy.isEndOfTurnCostReductionCard(card)
            }
            if (lowestPriorityPresent.isNotEmpty()) {
                log.info {
                    "海盗恶魔猎手：保留最低优先级牌 " +
                    lowestPriorityPresent.joinToString { DecisionTrace.displayName(it) }
                }
            }

            val normalCards = remainingCards.filterNot { lowestPriorityPresent.contains(it) }
            val orderedNormalCards = normalCards.toMutableList()
            orderedNormalCards.sortWith(Comparator { left, right ->
                val costComparison = left.cost.compareTo(right.cost)
                if (costComparison != 0) {
                    costComparison
                } else {
                    right.atc.compareTo(left.atc)
                }
            })
            playCardsInOrder(orderedNormalCards, reason = "普通牌按费用升序、攻击力降序")
            if (lowestPriorityPresent.isNotEmpty()) {
                log.info {
                    "海盗恶魔猎手：最低优先级牌出牌前先完成攻击阶段，" +
                        "让狂暴邪翼蝠/奇莉亚斯获得最大减费"
                }
                executeAllAvailableMinionAttacks()
                playCardsInOrder(lowestPriorityPresent, reason = "动态减费牌最低优先级，攻击后最后尝试")
            }
        } finally {
            // A failed or rejected card action must not bypass combat.  This
            // is the strategy's explicit attack phase; it is intentionally in
            // finally so an action exception cannot turn the whole turn into
            // an unconditional end-turn click.
            runCatching {
                log.info { "海盗恶魔猎手：进入攻击阶段，出牌异常不会跳过攻击" }
                executeAllAvailableMinionAttacks()
                DecisionTrace.record(
                    war = WAR,
                    event = "attack_phase_completed",
                    reason = "出牌阶段结束后逐个检查并执行可攻击随从",
                    outcome = "completed",
                    action = "attack",
                )
            }.onFailure { error ->
                log.error(error) { "海盗恶魔猎手：攻击阶段异常，仍继续回合收尾" }
                DecisionTrace.record(
                    war = WAR,
                    event = "attack_phase_failed",
                    reason = "攻击阶段发生异常",
                    outcome = "exception",
                    action = "attack",
                )
            }
        }
    }

    override fun executeDiscoverChooseCard(vararg cards: Card): Int = 1

    private fun logRuleObservation(rule: CardRule, card: Card) {
        val statMismatch = card.cost != rule.expectedCost ||
            card.atc != rule.expectedAttack ||
            card.health != rule.expectedHealth
        if (statMismatch) {
            log.warn {
                "海盗恶魔猎手：${rule.label} 已按 cardId/entityName 识别，但当前属性变化 " +
                    "actual=${card.cost}/${card.atc}/${card.health} " +
                    "expected=${rule.expectedCost}/${rule.expectedAttack}/${rule.expectedHealth}"
            }
        }
    }

    private fun isPlayable(card: Card, usableResource: Int, boardFull: Boolean): Boolean {
        if (card.cost > usableResource) return false
        if (card.cardType === CardTypeEnum.MINION && boardFull) return false
        return true
    }

    /**
     * The upstream cleanPlay() is a board-trade optimizer. It is correct for
     * the generic strategy, but it may intentionally choose no attack when a
     * trade scores poorly. Pirate Demon Hunter is an aggressive deck and must
     * not silently leave an otherwise attackable minion idle, so this strategy
     * uses the public CardAction.attack() path and records every candidate.
     */
    private fun executeAllAvailableMinionAttacks() {
        val me = WAR.me
        val candidates = me.playArea.cards.toList()
            .filter { it.cardType === CardTypeEnum.MINION }

        log.info {
            "ATTACK_PHASE_SCAN turn=${me.turn} candidates=" +
                candidates.joinToString(" | ") { card ->
                    "${DecisionTrace.displayName(card)}/${card.entityId}" +
                        " canAttack=${card.canAttack()} exhausted=${card.isExhausted}" +
                        " atk=${card.atc} frozen=${card.isFrozen} cantAttack=${card.isCantAttack}"
                }
        }

        var dispatched = 0
        var rejected = 0
        var skipped = 0
        for (candidate in candidates) {
            val card = me.playArea.cards.firstOrNull { it.entityId == candidate.entityId } ?: candidate
            if (!card.canAttack()) {
                skipped++
                log.info {
                    "ATTACK_CANDIDATE_SKIPPED attacker=${DecisionTrace.displayName(card)} " +
                        "entityId=${card.entityId} reason=NOT_ATTACKABLE exhausted=${card.isExhausted} " +
                        "atk=${card.atc} frozen=${card.isFrozen} cantAttack=${card.isCantAttack}"
                }
                DecisionTrace.record(
                    war = WAR,
                    event = "attack_candidate",
                    reason = "逐个攻击检查发现当前不可攻击",
                    candidate = card,
                    outcome = "skipped_unattackable",
                    action = "attack",
                )
                continue
            }

            val target = findAttackTarget()
            if (target == null) {
                skipped++
                log.warn {
                    "ATTACK_CANDIDATE_SKIPPED attacker=${DecisionTrace.displayName(card)} " +
                        "entityId=${card.entityId} reason=NO_VALID_TARGET"
                }
                DecisionTrace.record(
                    war = WAR,
                    event = "attack_candidate",
                    reason = "没有可攻击的敌方目标",
                    candidate = card,
                    outcome = "skipped_no_target",
                    action = "attack",
                )
                continue
            }

            val accepted = runCatching { card.action.attack(target) != null }.getOrElse { error ->
                log.error(error) {
                    "ATTACK_DISPATCH attacker=${DecisionTrace.displayName(card)} " +
                        "target=${DecisionTrace.displayName(target)} accepted=false reason=exception"
                }
                false
            }
            if (accepted) dispatched++ else rejected++
            log.info {
                "ATTACK_DISPATCH attacker=${DecisionTrace.displayName(card)} entityId=${card.entityId} " +
                    "target=${DecisionTrace.displayName(target)} targetEntityId=${target.entityId} accepted=$accepted"
            }
            DecisionTrace.record(
                war = WAR,
                event = "attack_candidate",
                reason = "逐个攻击检查后调用 CardAction.attack",
                candidate = card,
                outcome = if (accepted) "dispatched" else "rejected",
                action = "attack",
            )
        }
        log.info {
            "ATTACK_PHASE_RESULT candidates=${candidates.size} dispatched=$dispatched " +
                "rejected=$rejected skipped=$skipped"
        }
    }

    private fun findAttackTarget(): Card? =
        WAR.rival.playArea.cards.firstOrNull { it.isTaunt && it.canBeAttacked() }
            ?: WAR.rival.playArea.hero?.takeIf { it.canBeAttacked() }

    private fun playCardsInOrder(cards: List<Card>, reason: String) {
        for (card in cards) {
            val currentMana = WAR.me.usableResource
            if (!isPlayable(card, currentMana, WAR.me.playArea.isFull)) {
                recordCandidateSkipped(card, "普通牌候选当前不可出牌", null, currentMana)
                continue
            }
            val selection = DecisionTrace.record(
                war = WAR,
                event = "decision_selected",
                reason = reason,
                candidate = card,
                outcome = "selected",
                action = "play_card",
            )
            log.info { "海盗恶魔猎手：决策#${selection.sequence} 出牌 ${DecisionTrace.displayName(card)}，原因=$reason，当前法力=${currentMana}" }
            dispatchCardAction(card, selection.sequence, reason)
        }
    }

    private fun dispatchCardAction(card: Card, selectionSequence: Long, reason: String): Boolean {
        val currentMana = WAR.me.usableResource
        if (!isPlayable(card, currentMana, WAR.me.playArea.isFull)) {
            recordCandidateSkipped(card, "出牌前二次可用性检查拦截", null, currentMana)
            return false
        }
        try {
            card.action.autoPower(CARD_DATA_TRIE[card.cardId])
            DecisionTrace.record(
                war = WAR,
                event = "action_dispatched",
                reason = reason,
                candidate = card,
                outcome = "dispatched",
                action = "play_card",
                relatedSequence = selectionSequence,
            )
            return true
        } catch (error: Throwable) {
            DecisionTrace.record(
                war = WAR,
                event = "action_failed",
                reason = reason,
                candidate = card,
                outcome = "exception",
                action = "play_card",
                relatedSequence = selectionSequence,
            )
            throw error
        }
    }

    private fun recordCandidateSkipped(card: Card, reason: String, rule: String?, currentMana: Int) {
        val insufficientMana = currentMana < card.cost
        log.info {
            "CARD_PLAY_SKIPPED reason=${if (insufficientMana) "INSUFFICIENT_MANA" else "BOARD_FULL"} " +
                "card=${DecisionTrace.displayName(card)} cardId=${card.cardId} cost=${card.cost} " +
                "currentMana=$currentMana rule=${rule ?: "none"}"
        }
        DecisionTrace.record(
            war = WAR,
            event = "candidate_evaluated",
            reason = reason,
            candidate = card,
            rule = rule,
            outcome = if (insufficientMana) "skipped_insufficient_mana" else "skipped_board_full",
            action = "play_card",
        )
    }
}
