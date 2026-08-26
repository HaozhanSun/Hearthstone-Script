package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War

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

    /** Non-binding action prior used by the experimental expander/rollout. */
    fun actionPrior(action: Action, war: War): Double = 0.0

    /** Apply deterministic effects that happen before the action resolves. */
    fun beforeSimulatedAction(war: War, action: Action): SimulationResult = SimulationResult()

    /** Apply deterministic effects and expected-only rewards after resolution. */
    fun afterSimulatedAction(before: War, after: War, action: Action): SimulationResult = SimulationResult()

    /** State features that are not represented by the generic score calculator. */
    fun scoreAdjustment(war: War): Double = 0.0
}
