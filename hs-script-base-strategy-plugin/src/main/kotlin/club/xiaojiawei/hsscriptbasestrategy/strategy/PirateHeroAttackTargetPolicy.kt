package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.util.CardUtil

/**
 * Shared safety rule for the two Pirate MCTS models' hero attacks.
 *
 * A hero-face attack is legal only when it is lethal. Otherwise, if an enemy
 * minion is a legal attack target, keep exactly one deterministic minion
 * target: the first killable target in board order, or the first legal target
 * when no kill is available. Only an empty enemy board permits a nonlethal
 * face fallback. Unknown target metadata is rejected rather than bypassing
 * the rule.
 */
object PirateHeroAttackTargetPolicy {
    fun isLegal(action: Action, war: War): Boolean {
        if (action !is AttackAction || action.creator?.cardType !== CardTypeEnum.HERO) return true

        val targetId = action.targetEntityId ?: return false
        val rivalHero = war.rival.playArea.hero
        val heroAttack = action.creator?.atc?.coerceAtLeast(0) ?: return true
        val targetIsHero = action.targetIsHero || targetId == rivalHero?.entityId
        val legalMinions = legalEnemyMinions(war)

        if (targetIsHero) {
            // Card.bloodLimit() includes armor; damage is the damage already
            // taken, so this is the current effective health+armor.
            val remainingHeroLife = rivalHero?.let { (it.bloodLimit() - it.damage).coerceAtLeast(0) }
                ?: return true
            return heroAttack >= remainingHeroLife || legalMinions.isEmpty()
        }

        val preferredTarget = legalMinions.firstOrNull { canKill(it, heroAttack) }
            ?: legalMinions.firstOrNull()
            ?: return false
        return preferredTarget.entityId == targetId
    }

    private fun legalEnemyMinions(war: War): List<Card> {
        val taunts = CardUtil.getTauntCards(war.rival.playArea.cards, true)
        val legalTargets = if (taunts.isNotEmpty()) taunts else war.rival.playArea.cards
        return legalTargets.filter { target ->
            target.cardType === CardTypeEnum.MINION &&
                target.canBeAttacked()
        }
    }

    private fun canKill(target: Card, heroAttack: Int): Boolean =
        !target.isDivineShield &&
            heroAttack >= (target.bloodLimit() - target.damage).coerceAtLeast(0)
}
