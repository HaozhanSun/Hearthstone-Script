package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum

/**
 * Shared model boundary for 海上威胁 (SW_027).
 *
 * The generic parser already discovers the enemy-minion targets and applies
 * the printed base damage. This policy supplies only the conditional part
 * that the generic parser cannot infer: controlling a Pirate changes 2 to 5.
 * Target discovery remains action-owned, so no target is fabricated when the
 * live game exposes none.
 */
object PirateConditionalDamageSpellPolicy {
    const val CARD_ID = "SW_027"
    const val BASE_DAMAGE = 2
    const val PIRATE_DAMAGE = 5

    fun isCard(card: Card?): Boolean = card?.cardId == CARD_ID

    fun damageAtCast(war: War): Int =
        if (war.me.playArea.cards.any { isPirate(it) && it.isAlive() }) PIRATE_DAMAGE else BASE_DAMAGE

    fun isAction(action: Action): Boolean = action is PlayAction && isCard(action.creator)

    /** Find the visible enemy minion changed by the parsed target action. */
    fun changedTarget(before: War, after: War): Pair<Card, Card?>? {
        val afterByEntity = after.rival.playArea.cards.associateBy { it.entityId }
        return before.rival.playArea.cards.asSequence()
            .mapNotNull { old ->
                val current = afterByEntity[old.entityId]
                val changed = current == null || current.damage > old.damage ||
                    (old.isDivineShield && !current.isDivineShield)
                if (changed) old to current else null
            }
            .firstOrNull()
    }

    /** True only when this generated target action can kill its target. */
    fun canKill(action: Action, war: War): Boolean {
        if (!isAction(action)) return false
        val after = runCatching { war.clone().also { action.simulate.accept(it) } }.getOrNull() ?: return false
        val target = changedTarget(war, after) ?: return false
        // One damage spell is a single hit. A Divine Shield absorbs the full
        // hit, so breaking the shield is not evidence that SW_027 can kill.
        if (target.first.isDivineShield) return false
        val effectiveHealth = target.first.blood() + if (target.first.isDivineShield) 1 else 0
        return effectiveHealth <= damageAtCast(war)
    }

    /** Complete the conditional damage after the parser's base simulation. */
    fun applyConditionalDamage(before: War, after: War, action: Action): Int {
        if (!isAction(action)) return 0
        val target = changedTarget(before, after) ?: return 0
        // The conditional portion is part of the same hit and is absorbed by
        // an initial Divine Shield along with the parser's base damage.
        if (target.first.isDivineShield) return 0
        // A base two-damage hit may already remove a <=2-health target. In
        // that case no extra damage can be applied and the action is already
        // fully resolved.
        val alreadyApplied = target.second?.let {
            (it.damage - target.first.damage).coerceAtLeast(0)
        } ?: damageAtCast(before)
        val extra = (damageAtCast(before) - alreadyApplied).coerceAtLeast(0)
        if (extra > 0) target.second?.injured(extra)
        return extra
    }

    /** Soft prior for non-kill uses; kill uses enter TACTICAL_SPELL first. */
    fun softPrior(action: Action, war: War): Double {
        if (!isAction(action)) return 0.0
        val after = runCatching { war.clone().also { action.simulate.accept(it) } }.getOrNull()
            ?: return -12.0
        val target = changedTarget(war, after)?.first ?: return -24.0
        val damage = damageAtCast(war)
        val effectiveHealth = target.blood() + if (target.isDivineShield) 1 else 0
        val killBonus = if (effectiveHealth <= damage) 72.0 else 0.0
        val pirateBonus = if (damage == PIRATE_DAMAGE) 5.0 else 0.0
        // Do not spend the conditional five-damage shot on a disposable 1/1
        // while a visible high-attack threat is still on board.  The old
        // kill bonus was unconditional in practice: 1/1 received +72 and a
        // 6/9 threat received only +3 from attack, so MCTS greedily killed
        // the small minion and lost the removal window.  Preserve lethal
        // priority, but reserve the spell for a non-lethal threat when its
        // attack is materially dangerous.
        val highThreatReserve = if (effectiveHealth > damage && target.atc >= 5) 96.0 else 0.0
        val threatScore = target.atc.coerceAtLeast(0) * 1.5 +
            if (target.isTaunt) 8.0 else 0.0
        return 6.0 + pirateBonus + killBonus + highThreatReserve + threatScore
    }

    private fun isPirate(card: Card): Boolean =
        card.cardRace === CardRaceEnum.PIRATE || card.cardRace === CardRaceEnum.ALL
}
