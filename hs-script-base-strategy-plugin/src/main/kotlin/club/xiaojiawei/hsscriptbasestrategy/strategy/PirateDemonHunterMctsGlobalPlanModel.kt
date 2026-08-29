package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsDecisionModel

/**
 * The global-plan experiment keeps the existing Pirate DH card model and
 * changes only the root-plan objective. It prefers a plan that preserves the
 * largest amount of actually reachable mana spend, while retaining the
 * normal MCTS state score for board and combat quality.
 */
object PirateDemonHunterMctsGlobalPlanModel : MctsDecisionModel by PirateDemonHunterMctsExperimentModel {
    const val MANA_OPPORTUNITY_PENALTY = 4.0

    override fun turnPlanAdjustment(root: War, terminal: War, path: List<Action>): Double {
        if (root.me.usableResource <= 0) return 0.0

        val maximumReachableSpend = maxSpendableMana(root)
        if (maximumReachableSpend <= 0) return 0.0

        val actualSpend = (root.me.usableResource - terminal.me.usableResource)
            .coerceIn(0, root.me.usableResource)
        val missedSpend = (maximumReachableSpend - actualSpend).coerceAtLeast(0)

        // This is deliberately a soft opportunity-cost penalty. It does not
        // force every point of mana to be spent when no legal sequence can do
        // so, and it does not mention any particular card ID or card order.
        return -missedSpend * MANA_OPPORTUNITY_PENALTY
    }

    /** Upper bound on mana that the current root can spend legally. */
    fun maxSpendableMana(war: War): Int {
        val mana = war.me.usableResource.coerceAtLeast(0)
        if (mana == 0) return 0

        val freeSlots = (war.me.playArea.maxSize - war.me.playArea.cards.size)
            .coerceAtLeast(0)
        val options = mutableListOf<SpendOption>()

        war.me.handArea.cards.forEach { card ->
            if (isPlayableHandCard(card, war, mana, freeSlots)) {
                options += SpendOption(card.cost, if (usesBoardSlot(card)) 1 else 0)
            }
        }

        war.me.playArea.power?.let { power ->
            if (
                power.cost in 1..mana &&
                    !power.isExhausted &&
                    power.canPower() &&
                    runCatching { power.action.generatePowerActions(war, war.me) }
                        .getOrDefault(emptyList())
                        .isNotEmpty()
            ) {
                options += SpendOption(power.cost, 0)
            }
        }

        if (options.isEmpty()) return 0

        // Small knapsack with a board-slot dimension. This avoids rewarding
        // an impossible “play five minions” plan when only one slot remains.
        val reachable = Array(mana + 1) { BooleanArray(freeSlots + 1) }
        reachable[0][0] = true
        options.forEach { option ->
            for (spentMana in mana downTo option.cost) {
                for (usedSlots in freeSlots downTo option.slot) {
                    if (reachable[spentMana - option.cost][usedSlots - option.slot]) {
                        reachable[spentMana][usedSlots] = true
                    }
                }
            }
        }
        return (mana downTo 0).firstOrNull { spent -> reachable[spent].any { it } } ?: 0
    }

    private fun isPlayableHandCard(card: Card, war: War, mana: Int, freeSlots: Int): Boolean {
        if (card.isUncertain || card.cost !in 1..mana) return false
        if (usesBoardSlot(card) && freeSlots == 0) return false
        if (shouldDefer(card, war)) return false

        val result = runCatching { card.action.generatePlayActions(war, war.me) }
        if (result.isFailure) return false
        val actions = result.getOrDefault(emptyList())
        return actions.any { !isDeferredAction(it, war) } ||
            (actions.isEmpty() && canCreateOpaqueAction(card, war))
    }

    private fun usesBoardSlot(card: Card): Boolean =
        card.cardType === CardTypeEnum.MINION || card.cardType === CardTypeEnum.LOCATION

    private data class SpendOption(val cost: Int, val slot: Int)
}
