package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.util.CardUtil
import club.xiaojiawei.hsscriptcardsdk.bean.DEFAULT_WAR_SCORE_CALCULATOR

/**
 * Shared safety rule for the two Pirate MCTS models' hero attacks.
 *
 * A hero-face attack is legal when the hero cannot currently kill an enemy
 * minion. If it can kill one, keep exactly one deterministic minion target:
 * the highest-threat killable target. If the hero needs friendly minion
 * damage first, the same target remains selected and the models expose the
 * setup attacks before the hero attack. Taunt remains a mandatory target even
 * when it cannot be killed. Unknown target metadata is rejected rather than
 * bypassing the rule.
 */
object PirateHeroAttackTargetPolicy {
    private data class TargetPlan(
        val target: Card,
        val requiresFriendlySetup: Boolean,
    )

    fun isLegal(action: Action, war: War): Boolean {
        if (action !is AttackAction || action.creator?.cardType !== CardTypeEnum.HERO) return true

        val targetId = action.targetEntityId ?: return false
        val rivalHero = war.rival.playArea.hero
        val heroAttack = effectiveHeroAttack(action.creator, war)
        val targetIsHero = action.targetIsHero || targetId == rivalHero?.entityId
        val plan = targetPlan(war, heroAttack)

        if (targetIsHero) {
            // Lethal face actions are handled by PirateLethalAttackPolicy.
            // This policy allows a nonlethal face attack only when there is
            // no required minion target (or no attackable minion at all).
            return plan == null
        }

        return plan?.target?.entityId == targetId
    }

    /** True when a friendly minion attack must happen before the hero attack. */
    fun requiresFriendlySetupAttack(war: War): Boolean =
        targetPlan(war, effectiveHeroAttack(war.me.playArea.hero, war))?.requiresFriendlySetup == true

    /** The only target that can be used for the required setup attack. */
    fun isRequiredFriendlySetupAttack(action: Action, war: War): Boolean {
        if (action !is AttackAction || action.creator?.cardType !== CardTypeEnum.MINION) return false
        val targetId = action.targetEntityId ?: return false
        val heroAttack = effectiveHeroAttack(war.me.playArea.hero, war)
        val plan = targetPlan(war, heroAttack) ?: return false
        return plan.requiresFriendlySetup && plan.target.entityId == targetId
    }

    private fun legalEnemyMinions(war: War): List<Card> {
        val taunts = CardUtil.getTauntCards(war.rival.playArea.cards, true)
        val legalTargets = if (taunts.isNotEmpty()) taunts else war.rival.playArea.cards
        return legalTargets.filter { target ->
            target.cardType === CardTypeEnum.MINION &&
                target.isAlive() &&
                target.canBeAttacked()
        }
    }

    private fun targetPlan(war: War, heroAttack: Int): TargetPlan? {
        val legalMinions = legalEnemyMinions(war)
        if (legalMinions.isEmpty()) return null

        val soloKillable = legalMinions.filter { canKill(it, heroAttack) }
        if (soloKillable.isNotEmpty()) {
            return TargetPlan(highestThreat(soloKillable), requiresFriendlySetup = false)
        }

        val comboKillable = legalMinions.filter { target ->
            canKill(target, heroAttack + availableFriendlyAttackDamageAgainst(target, war))
        }
        if (comboKillable.isNotEmpty()) {
            return TargetPlan(highestThreat(comboKillable), requiresFriendlySetup = true)
        }

        // A visible, attackable taunt is still compulsory even when the
        // combined damage cannot remove it. Non-taunt minions do not block a
        // nonlethal face attack under the new rule.
        return if (legalMinions.any { it.isTaunt }) {
            TargetPlan(highestThreat(legalMinions), requiresFriendlySetup = false)
        } else {
            null
        }
    }

    private fun highestThreat(cards: List<Card>): Card =
        cards.withIndex()
            .maxWithOrNull(
                compareBy<IndexedValue<Card>> { threatScore(it.value) }
                    .thenByDescending { -it.index },
            )!!
            .value

    /**
     * Prefer immediate board damage, then visible combat/value signals. The
     * score is intentionally deterministic and only uses parser-visible state.
     */
    private fun threatScore(card: Card): Double =
        maxOf(card.atc, 0) * 100.0 +
            (if (card.canAttack()) 35.0 else 0.0) +
            (if (card.isTaunt) 10_000.0 else 0.0) +
            (if (card.isMegaWindfury) 350.0 else if (card.isWindFury) 200.0 else 0.0) +
            (if (card.isAura) 150.0 else 0.0) +
            (if (card.isAdjacentBuff) 125.0 else 0.0) +
            (if (card.isTriggerVisual) 100.0 else 0.0) +
            DEFAULT_WAR_SCORE_CALCULATOR.calcPlayCardScore(card, false)

    private fun availableFriendlyAttackDamageAgainst(target: Card, war: War): Int =
        war.me.playArea.cards
            .asSequence()
            .filter { it.cardType === CardTypeEnum.MINION && it.isAlive() && it.canAttack() }
            .filter { attacker ->
                runCatching {
                    attacker.action.generateAttackActions(war, war.me)
                        .any { it.targetEntityId == target.entityId }
                }.getOrDefault(false)
            }
            .sumOf { it.atc.coerceAtLeast(0) }

    private fun effectiveHeroAttack(hero: Card?, war: War): Int {
        val printedAttack = hero?.atc?.coerceAtLeast(0) ?: 0
        val weaponAttack = war.me.playArea.weapon?.atc?.coerceAtLeast(0) ?: 0
        return printedAttack + weaponAttack
    }

    private fun canKill(target: Card, heroAttack: Int): Boolean =
        heroAttack >=
            (target.bloodLimit() - target.damage + if (target.isDivineShield) 1 else 0)
                .coerceAtLeast(0)
}
