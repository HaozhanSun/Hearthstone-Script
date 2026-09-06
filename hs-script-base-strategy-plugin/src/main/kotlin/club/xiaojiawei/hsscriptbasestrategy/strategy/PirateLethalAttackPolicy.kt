package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum

/**
 * Shared first-pass lethal calculation for the two Pirate MCTS models.
 *
 * The calculation only counts attack actions that the normal action generator
 * currently exposes against the opposing hero. That makes taunt, rush-only
 * attacks, exhausted attackers, immune heroes, and other target restrictions
 * part of the calculation instead of treating printed attack as automatically
 * face damage.
 */
object PirateLethalAttackPolicy {
    fun isLethalFaceAction(action: Action, war: War): Boolean {
        if (action !is AttackAction || !isFaceAction(action, war)) return false
        val rivalHero = war.rival.playArea.hero ?: return false
        val remainingLife = (rivalHero.bloodLimit() - rivalHero.damage).coerceAtLeast(0)
        return legalFaceDamage(war) >= remainingLife
    }

    fun legalFaceDamage(war: War): Int {
        val me = war.me
        val minionDamage = me.playArea.cards
            .asSequence()
            .filter { it.cardType === CardTypeEnum.MINION && it.canAttack() }
            .filter { card ->
                runCatching { card.action.generateAttackActions(war, me) }
                    .getOrDefault(emptyList())
                    .any { isFaceAction(it, war) }
            }
            .sumOf { it.atc.coerceAtLeast(0) }

        val weaponDamage = me.playArea.hero?.let { hero ->
            val weaponAttack = me.playArea.weapon?.atc?.coerceAtLeast(0) ?: 0
            if (weaponAttack <= 0) return@let 0

            val canAttack = hero.canAttack() || hero.canAttack(ignoreAtc = true)
            if (!canAttack) return@let 0
            val hasFaceAction = runCatching { hero.action.generateAttackActions(war, me) }
                .getOrDefault(emptyList())
                .any { isFaceAction(it, war) }
            if (hasFaceAction) weaponAttack else 0
        } ?: 0

        return minionDamage + weaponDamage
    }

    private fun isFaceAction(action: AttackAction, war: War): Boolean {
        val rivalHeroId = war.rival.playArea.hero?.entityId
        return action.targetIsHero || action.targetEntityId == rivalHeroId
    }
}
