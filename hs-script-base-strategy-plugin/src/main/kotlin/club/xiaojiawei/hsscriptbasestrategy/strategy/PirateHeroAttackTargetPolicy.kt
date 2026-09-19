package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.AttackAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
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
 * setup attacks before the hero attack. An unkillable Taunt blocks both face
 * and hero collision; it is not permission to throw the hero into it. Unknown
 * target metadata is rejected rather than bypassing the rule.
 */
object PirateHeroAttackTargetPolicy {
    const val NU_LING_NAGA = "BT_355"

    /** Live DBF id emitted for the Demon Hunter +1 attack hero power. */
    const val DEMON_HUNTER_HERO_POWER = "HERO_10cbp"

    private data class TargetPlan(
        val target: Card?,
        val requiresFriendlySetup: Boolean,
        val blockedByTaunt: Boolean = false,
    )

    /** True while the death-trigger Naga is alive on our board. */
    fun hasNuLingNaga(war: War): Boolean =
        war.me.playArea.cards.any { isNuLingNaga(it) && it.isAlive() }

    fun isNuLingNaga(card: Card): Boolean =
        card.cardId == NU_LING_NAGA || card.cardId == "CORE_$NU_LING_NAGA"

    /**
     * Down-rank the death-trigger Naga while none of our minions can attack.
     * The card is still left legal as a fallback; this is a planning signal,
     * not a hard card filter.
     */
    fun nuLingNagaPlayPrior(action: Action, war: War): Double {
        if (action !is PlayAction || action.creator?.let(::isNuLingNaga) != true) return 0.0
        return if (hasAttackableFriendlyMinion(war)) 0.0 else -35.0
    }

    /**
     * Softly prefer other friendly minions to trade into enemy minions while
     * Nu Ling Naga is alive.  The Naga's own attack is excluded deliberately:
     * the rule is for preserving the death-trigger body while other minions
     * create the favorable death trigger.  A lethal face attack is left
     * untouched because the shared first-pass lethal gate outranks this
     * preference.
     */
    fun nuLingNagaAttackPrior(action: Action, war: War): Double {
        if (!hasNuLingNaga(war)) return 0.0
        if (action !is AttackAction || action.creator?.cardType !== CardTypeEnum.MINION) return 0.0
        if (action.creator?.let(::isNuLingNaga) == true) return 0.0

        val targetId = action.targetEntityId ?: return 0.0
        val rivalHeroId = war.rival.playArea.hero?.entityId
        val targetIsHero = action.targetIsHero || targetId == rivalHeroId
        if (targetIsHero) {
            if (PirateLethalAttackPolicy.isLethalFaceAction(action, war)) return 0.0
            return if (hasAttackableEnemyMinion(war)) -60.0 else 0.0
        }

        val target = war.rival.playArea.cards.firstOrNull { it.entityId == targetId }
        return if (target?.cardType === CardTypeEnum.MINION && target.isAlive() && target.canBeAttacked()) {
            60.0
        } else {
            0.0
        }
    }

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

    /** True when a generated hero attack is blocked by an unkillable Taunt. */
    fun isHeroAttackBlockedByUnkillableTaunt(action: Action, war: War): Boolean {
        if (action !is AttackAction || action.creator?.cardType !== CardTypeEnum.HERO) return false
        return targetPlan(war, effectiveHeroAttack(action.creator, war))?.blockedByTaunt == true
    }

    /**
     * Do not spend Demon Hunter's +1 Attack power merely to make an attack
     * that still cannot remove the Taunt. If the powered attack can remove it
     * (alone or after currently legal friendly setup damage), leave the power
     * available to normal MCTS ordering.
     */
    fun shouldBlockHeroPowerAgainstUnkillableTaunt(
        action: Action,
        war: War,
        heroPowerAttackBonus: Int = 1,
    ): Boolean {
        if (action !is PowerAction || action.creator?.cardType !== CardTypeEnum.HERO_POWER) return false
        if (!isDemonHunterHeroPower(action.creator)) return false

        val taunts = CardUtil.getTauntCards(war.rival.playArea.cards, true)
            .filter { it.cardType === CardTypeEnum.MINION && it.isAlive() && it.canBeAttacked() }
        if (taunts.isEmpty()) return false

        val heroAttack = effectiveHeroAttack(war.me.playArea.hero, war)
        val friendlyComboDamage = { target: Card -> availableFriendlyAttackDamageAgainst(target, war) }
        if (taunts.any { canKill(it, heroAttack) || canKill(it, heroAttack + friendlyComboDamage(it)) }) {
            return false
        }

        val poweredAttack = heroAttack + heroPowerAttackBonus
        return taunts.none {
            canKill(it, poweredAttack) || canKill(it, poweredAttack + friendlyComboDamage(it))
        }
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
        return plan.requiresFriendlySetup && plan.target?.entityId == targetId
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

    private fun hasAttackableEnemyMinion(war: War): Boolean =
        war.rival.playArea.cards.any {
            it.cardType === CardTypeEnum.MINION && it.isAlive() && it.canBeAttacked()
        }

    private fun hasAttackableFriendlyMinion(war: War): Boolean =
        war.me.playArea.cards.any { minion ->
            minion.cardType === CardTypeEnum.MINION &&
                minion.isAlive() &&
                minion.canAttack() &&
                runCatching {
                    minion.action.generateAttackActions(war, war.me).isNotEmpty()
                }.getOrDefault(false)
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

        // A visible, attackable Taunt is compulsory only when it can actually
        // be removed. An unkillable Taunt blocks both the collision and face;
        // the caller can then rescan other actions and legally end the turn.
        return if (legalMinions.any { it.isTaunt }) {
            TargetPlan(target = null, requiresFriendlySetup = false, blockedByTaunt = true)
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
            // When attack is tied, prefer removing the healthier minion.  A
            // ready 2/1 must not outrank an exhausted 2/3 merely because it
            // contributes the canAttack() bonus below.
            card.blood().coerceAtLeast(0) * 40.0 +
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

    /**
     * Return the current attack value that a live hero attack will deal.
     *
     * Power.log publishes the equipped weapon separately, but after the
     * weapon has merged into the hero entity, [Card.atc] already contains the
     * hero's current attack total. Adding both fields double-counts the
     * weapon and can make a 3-attack hero appear to have 6 attack. During the
     * short merge window the hero field can still be zero, so use the weapon
     * as a conservative fallback only in that state.
     */
    fun effectiveHeroAttack(hero: Card?, war: War): Int {
        val heroAttack = hero?.atc?.coerceAtLeast(0) ?: 0
        if (heroAttack > 0) return heroAttack
        return war.me.playArea.weapon?.atc?.coerceAtLeast(0) ?: 0
    }

    private fun canKill(target: Card, heroAttack: Int): Boolean =
        heroAttack >=
            (target.bloodLimit() - target.damage + if (target.isDivineShield) 1 else 0)
                .coerceAtLeast(0)

    private fun isDemonHunterHeroPower(card: Card?): Boolean =
        card?.cardId == DEMON_HUNTER_HERO_POWER || card?.cardId == "CORE_$DEMON_HUNTER_HERO_POWER"
}
