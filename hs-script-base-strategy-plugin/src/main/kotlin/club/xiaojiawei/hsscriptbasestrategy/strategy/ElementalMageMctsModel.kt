package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.bean.WarScoreCalculatorBuilder
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.CardTimingPolicy
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsActionOrderPhase
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsCardDiagnostics
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsDecisionModel
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsLethalTelemetry
import java.util.concurrent.ConcurrentHashMap
import club.xiaojiawei.hsscriptcardsdk.mcts.defaultMctsActionOrderPhase
import club.xiaojiawei.hsscriptstrategysdk.deck.MctsDiscoverCandidateOverride

/**
 * First offline-safe Elemental Mage model.
 *
 * The chain rules are deliberately expressed as pure helpers so replay tests
 * can prove the turn boundaries without pretending that an offline fixture is
 * a live Hearthstone result. The live executor will use the same mandatory
 * turn-three Elemental gate and re-plan after every accepted action.
 */
object ElementalMageMctsModel : MctsDecisionModel {
    const val MIN_ELEMENTAL_CHAIN_TURN = 3
    const val OVERFLOWING_LAVA_ID = "WW_424"
    const val ARCHIVE_ADMINISTRATOR_ID = "TTN_095"

    private val sunfireNames = setOf("阳炎耀斑", "阳炎药班", "阳炎药斑")
    private val chainDependentNames = setOf("烈炎珠", "玄炎虫", "异流熔岩", "溢流熔岩", "焰登元素", "破链角斗士")

    private data class LiveChainState(
        var turn: Int = -1,
        var consecutiveTurns: Int = 0,
        var playedElementalThisTurn: Boolean = false,
        var baselineElementalEntityIds: Set<String> = emptySet(),
    )

    private val liveChainStates = ConcurrentHashMap<String, LiveChainState>()

    data class ChainSnapshot(
        val lastTurnPlayedElemental: Boolean = false,
        val consecutiveTurns: Int = 0,
    )

    fun isElemental(card: Card): Boolean =
        card.cardRace === CardRaceEnum.ELEMENTAL ||
            card.entityName.contains("元素") ||
            card.entityName.contains("熔岩")

    fun isSunfire(card: Card): Boolean =
        sunfireNames.any { card.entityName.contains(it) }

    fun isChainDependent(card: Card): Boolean =
        chainDependentNames.any { card.entityName.contains(it) }

    fun isOverflowingLava(card: Card): Boolean =
        card.cardId == OVERFLOWING_LAVA_ID ||
            card.entityName.contains("溢流熔岩") ||
            card.entityName.contains("异流熔岩") ||
            card.entityName.contains("亦留容颜")

    /** Elemental Mage-only priority card: it discounts the next Elemental by 2. */
    fun isArchiveAdministrator(card: Card): Boolean =
        card.cardId == ARCHIVE_ADMINISTRATOR_ID || card.entityName.contains("流水档案管理员")

    data class OverflowingLavaCopyPlan(
        val consecutiveElementalTurns: Int,
        val expectedTotalMinions: Int,
        val availableSlots: Int,
        val lostCopies: Int,
        val allowed: Boolean,
    )

    /** Keep the card legal when no more than one expected copy is lost to the board cap. */
    fun overflowingLavaCopyPlan(consecutiveElementalTurns: Int, availableSlots: Int): OverflowingLavaCopyPlan {
        val chain = consecutiveElementalTurns.coerceAtLeast(0)
        val expectedTotal = chain + 1
        val safeSlots = availableSlots.coerceAtLeast(0)
        val lostCopies = (expectedTotal - safeSlots).coerceAtLeast(0)
        return OverflowingLavaCopyPlan(chain, expectedTotal, safeSlots, lostCopies, lostCopies <= 1)
    }

    /** Track live elemental-chain continuity between the executor's re-plans. */
    fun observeLiveDecision(war: War) {
        val key = war.me.gameId.takeIf { it.isNotBlank() } ?: "start-${war.startTime}"
        val state = liveChainStates.computeIfAbsent(key) { LiveChainState() }
        val turn = war.me.turn
        val currentElementalIds = war.me.playArea.cards
            .filter(::isElemental)
            .map { it.entityId }
            .toSet()
        if (state.turn != turn) {
            if (state.turn >= 0 && turn == state.turn + 1) {
                state.consecutiveTurns = if (state.playedElementalThisTurn) state.consecutiveTurns + 1 else 0
            } else if (state.turn != -1) {
                state.consecutiveTurns = 0
            }
            state.turn = turn
            state.playedElementalThisTurn = false
            state.baselineElementalEntityIds = currentElementalIds
        } else if (currentElementalIds.any { it !in state.baselineElementalEntityIds }) {
            state.playedElementalThisTurn = true
        }
        if (liveChainStates.size > 32) {
            liveChainStates.keys.take(liveChainStates.size - 32).forEach(liveChainStates::remove)
        }
        // Keep the common, read-only lethal telemetry active for this strategy
        // too.  The shared recorder deduplicates repeated rescans, while the
        // Elemental model remains responsible only for its chain bookkeeping.
        MctsLethalTelemetry.recordBeforeAttackDecision(
            war = war,
            strategy = "元素法 V1.4",
            step = turn,
            selectedAction = null,
        )
    }

    fun currentConsecutiveElementalTurns(war: War): Int {
        val key = war.me.gameId.takeIf { it.isNotBlank() } ?: "start-${war.startTime}"
        return liveChainStates[key]?.consecutiveTurns ?: 0
    }

    fun updateChain(previous: ChainSnapshot, turn: Int, playedElemental: Boolean): ChainSnapshot {
        if (!playedElemental) return ChainSnapshot(lastTurnPlayedElemental = false, consecutiveTurns = 0)
        val nextCount = if (previous.lastTurnPlayedElemental && turn > 0) {
            previous.consecutiveTurns + 1
        } else {
            1
        }
        return ChainSnapshot(lastTurnPlayedElemental = true, consecutiveTurns = nextCount)
    }

    fun elementalAvailable(war: War): Boolean =
        war.me.handArea.cards.any { isPlayable(it, war) && isElemental(it) }

    /**
     * Protect the turn-three-onward elemental chain only when the next turn
     * would otherwise have no playable Elemental in hand. The shared generic
     * Discover policy ranks choices inside this narrowed candidate set.
     */
    fun discoverChainOverride(
        offered: List<Card>,
        hand: List<Card>,
        nextTurnMana: Int,
        nextTurnNumber: Int,
    ): MctsDiscoverCandidateOverride? {
        if (nextTurnNumber < MIN_ELEMENTAL_CHAIN_TURN) return null
        val handCanContinue = hand.any { card ->
            isElemental(card) && isKnownEnoughForDiscover(card) && card.cost in 0..nextTurnMana
        }
        if (handCanContinue) return null
        val continuationIndices = offered.indices.filter { index ->
            val card = offered[index]
            isElemental(card) &&
                card.cardType === CardTypeEnum.MINION &&
                isKnownEnoughForDiscover(card) &&
                card.cost in 0..nextTurnMana
        }
        return continuationIndices.takeIf { it.isNotEmpty() }?.let {
            MctsDiscoverCandidateOverride(it, "elemental-chain-break-next-turn-no-playable-hand-elemental")
        }
    }

    private fun isKnownEnoughForDiscover(card: Card): Boolean =
        !card.isUncertain || MctsCardDiagnostics.braveOpaqueFallbackAllowed(card)

    fun isPlayable(card: Card, war: War): Boolean =
        (!card.isUncertain || MctsCardDiagnostics.braveOpaqueFallbackAllowed(card)) &&
            card.cost in 0..war.me.usableResource

    fun mustPlayElementalFirst(war: War): Boolean =
        war.me.turn >= MIN_ELEMENTAL_CHAIN_TURN && elementalAvailable(war)

    fun sunfireIsAllowed(card: Card): Boolean = card.cost <= 2

    override fun shouldDefer(card: Card, war: War): Boolean =
        isSunfire(card) && !sunfireIsAllowed(card)

    override fun canCreateOpaqueAction(card: Card, war: War): Boolean =
        MctsCardDiagnostics.safeOpaqueMinionFallbackAllowed(card)

    override fun isMandatoryAction(action: Action, war: War): Boolean {
        if (!mustPlayElementalFirst(war)) return false
        return action is PlayAction && action.creator?.let(::isElemental) == true
    }

    override fun isActionLegal(action: Action, war: War): Boolean {
        val creator = action.creator
        if (action is PlayAction && creator != null && isOverflowingLava(creator)) {
            val plan = overflowingLavaCopyPlan(
                currentConsecutiveElementalTurns(war),
                war.me.playArea.maxSize - war.me.playArea.cards.size,
            )
            return plan.allowed && CardTimingPolicy.isActionLegal(action, war)
        }
        if (action is PlayAction && creator != null && isSunfire(creator)) {
            return sunfireIsAllowed(creator)
        }
        return CardTimingPolicy.isActionLegal(action, war)
    }

    override fun actionFilterReason(action: Action, war: War): String? {
        val creator = action.creator ?: return null
        if (!isOverflowingLava(creator)) return null
        val plan = overflowingLavaCopyPlan(
            currentConsecutiveElementalTurns(war),
            war.me.playArea.maxSize - war.me.playArea.cards.size,
        )
        return "溢流熔岩复制计划：连续元素回合=${plan.consecutiveElementalTurns} " +
            "预计总随从=${plan.expectedTotalMinions} 可用空位=${plan.availableSlots} " +
            "损失复制=${plan.lostCopies} 允许=${plan.allowed}"
    }

    override fun isLethalAction(action: Action, war: War): Boolean =
        MctsLethalTelemetry.isLethalFaceAction(action, war)

    override fun actionOrderPhase(action: Action, war: War): MctsActionOrderPhase? = when {
        action is PlayAction && action.creator?.cardType === CardTypeEnum.MINION ->
            MctsActionOrderPhase.MINION_PLAY
        action is PlayAction && action.creator?.cardType === CardTypeEnum.SPELL ->
            MctsActionOrderPhase.SPELL_PLAY
        // The live executor wraps this model in a monotonic phase fence after
        // the first action of a cycle.  Returning null for an attack makes the
        // fence reject a genuinely generated attack once a spell has already
        // been played (null means "unclassified", not "always allowed").
        // Preserve the intended order by classifying attacks explicitly.
        else -> defaultMctsActionOrderPhase(action)
    }

    override fun actionPrior(action: Action, war: War): Double {
        val card = action.creator ?: return 0.0
        if (isArchiveAdministrator(card)) return 1_000.0
        if (isOverflowingLava(card)) {
            val plan = overflowingLavaCopyPlan(
                currentConsecutiveElementalTurns(war),
                war.me.playArea.maxSize - war.me.playArea.cards.size,
            )
            return if (plan.allowed) 34.0 + plan.consecutiveElementalTurns else -1_000.0
        }
        if (isElemental(card)) return 42.0 + card.atc.coerceAtLeast(0) * 0.4
        if (isSunfire(card)) return if (sunfireIsAllowed(card)) -4.0 else -1_000.0
        if (isChainDependent(card)) return 12.0
        return 0.0
    }

    override fun scoreAdjustment(war: War): Double {
        val boardElementals = war.me.playArea.cards.count { isElemental(it) && it.isAlive() }
        val handElementals = war.me.handArea.cards.count { isElemental(it) }
        return boardElementals * 1.5 + handElementals * 0.5
    }

    override fun turnPlanAdjustment(root: War, terminal: War, path: List<Action>): Double {
        if (root.me.turn < MIN_ELEMENTAL_CHAIN_TURN) return 0.0
        val hasElementalInRoot = root.me.handArea.cards.any { isPlayable(it, root) && isElemental(it) }
        if (!hasElementalInRoot) return 0.0
        val playedElemental = path.any { it is PlayAction && it.creator?.let(::isElemental) == true }
        return if (playedElemental) 18.0 else -80.0
    }

    fun discoverScore(card: Card): Double =
        (if (isElemental(card)) 12.0 else 0.0) +
            card.atc.coerceAtLeast(0) * 0.4 + card.blood().coerceAtLeast(0) * 0.25
}

class ElementalMageMctsScoreCalculatorBuilder : WarScoreCalculatorBuilder()
