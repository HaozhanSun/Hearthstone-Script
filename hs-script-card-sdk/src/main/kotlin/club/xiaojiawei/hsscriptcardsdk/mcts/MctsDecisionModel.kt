package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum

/**
 * Coarse phases for the live/receding-horizon action order. Card-specific
 * priorities still choose among actions in the same phase; this fence keeps a
 * later phase from winning the current live root.
 */
enum class MctsActionOrderPhase(val monotonicRank: Int) {
    /** Explicit Pirate DH exception after a hero attack. */
    POST_HERO_ATTACK_LOCATION(5),
    /** Explicit Pirate DH exception between two Cliffside activations. */
    CLIFFSIDE_HERO_ATTACK(4),
    MINION_PLAY(0),
    SPELL_PLAY(1),
    MINION_ATTACK(2),
    HERO_POWER(3),
    HERO_ATTACK(4),
}

/** Shared classifier for decks that explicitly opt into this action order. */
fun defaultMctsActionOrderPhase(action: Action): MctsActionOrderPhase? = when {
    action is PlayAction && action.creator?.cardType === CardTypeEnum.MINION ->
        MctsActionOrderPhase.MINION_PLAY
    action is AttackAction && action.creator?.cardType === CardTypeEnum.MINION ->
        MctsActionOrderPhase.MINION_ATTACK
    action is PowerAction && action.creator?.cardType === CardTypeEnum.HERO_POWER ->
        MctsActionOrderPhase.HERO_POWER
    action is AttackAction && action.creator?.cardType === CardTypeEnum.HERO ->
        MctsActionOrderPhase.HERO_ATTACK
    else -> null
}
/**
 * Optional, deck-specific hooks for MCTS.
 *
 * The legacy search path does not install a model.  Implementations must only
 * mutate the cloned simulation war supplied by the searcher; the live WAR is
 * never exposed here.
 */
interface MctsDecisionModel {
    data class SimulationResult(
        val expectedReward: Double = 0.0,
        val stopRollout: Boolean = false,
    )

    /** A soft timing rule. Returning true removes a card from this node. */
    fun shouldDefer(card: Card, war: War): Boolean = false

    /** Whether a card with no parsed action may use the generic opaque action. */
    fun canCreateOpaqueAction(card: Card, war: War): Boolean = false

    /**
     * Whether a playable board card with no parsed power action may use the
     * generic opaque power action. This is primarily for locations whose
     * database entry identifies the card but has no bespoke parser plugin.
     */
    fun canCreateOpaquePowerAction(card: Card, war: War): Boolean = false

    /**
     * Whether an empty/EndTurn search result should be retried briefly because
     * the live parser is expected to publish a just-completed action's state.
     *
     * This is a perception-latency hook, not a way to keep searching forever:
     * the caller applies its normal bounded retry count.  A deck model may use
     * it for transitions such as a hero attack that unlocks a location after
     * the cooldown flag has caught up in Power.log.
     */
    fun shouldRetryAfterEmptySearch(war: War): Boolean = false

    /**
     * Classify an action for the live/receding-horizon phase fence. The
     * default is deliberately opt-in: only a deck model that understands the
     * semantics of its cards should install a hard action-order fence.
     */
    fun actionOrderPhase(action: Action, war: War): MctsActionOrderPhase? = null

    /**
     * Hard legality after the generic parser has produced an action.
     *
     * This is intentionally separate from [isDeferredAction].  A deferred
     * action may become a valid last-resort choice, while an action rejected
     * here must never be resurrected when every other candidate is filtered.
     */
    fun isActionLegal(action: Action, war: War): Boolean = true

    /**
     * A hard sequencing hook for actions whose timing is part of the card's
     * meaning. Returning true restricts the current node to these actions.
     */
    fun isMandatoryAction(action: Action, war: War): Boolean = false

    /**
     * Whether this candidate should be removed while another useful action
     * exists. Unlike [isMandatoryAction], this does not turn every other
     * candidate into a single forced chain.
     */
    fun isDeferredAction(action: Action, war: War): Boolean = false

    /**
     * Hard first-pass lethal gate. When the root contains one or more actions
     * that are part of a currently legal face-lethal route, the tree exposes
     * that route before applying the ordinary phase fence or soft priors.
     */
    fun isLethalAction(action: Action, war: War): Boolean = false

    /** Non-binding action prior used by the experimental expander/rollout. */
    fun actionPrior(action: Action, war: War): Double = 0.0

    /** Apply deterministic effects that happen before the action resolves. */
    fun beforeSimulatedAction(war: War, action: Action): SimulationResult = SimulationResult()

    /** Apply deterministic effects and expected-only rewards after resolution. */
    fun afterSimulatedAction(before: War, after: War, action: Action): SimulationResult = SimulationResult()

    /** State features that are not represented by the generic score calculator. */
    fun scoreAdjustment(war: War): Double = 0.0

    /**
     * Additional score for a discovered complete turn plan. The default is
     * zero so existing strategies retain their current root selection. This
     * hook is intentionally plan-level rather than card-ID-specific: it can
     * reward resource-efficient plans without forcing a particular card order.
     */
    fun turnPlanAdjustment(root: War, terminal: War, path: List<Action>): Double = 0.0
}
