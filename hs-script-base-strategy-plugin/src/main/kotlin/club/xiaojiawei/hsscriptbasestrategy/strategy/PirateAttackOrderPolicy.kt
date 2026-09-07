package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.PowerAction
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.util.CardUtil

/**
 * Shared ordering exception for the Pirate Demon Hunter and Pirate Warrior
 * models. A live Adrenaline Fiend makes every friendly Pirate attack a
 * resource-generating step, so the minion-attack-first fence remains
 * necessary while one is on our board. Without one, the hero's attack or
 * hero power may be used before minion attacks to remove a threat safely.
 */
object PirateAttackOrderPolicy {
    const val ADRENALINE_FIEND = "VAC_927"

    fun hasAdrenalineFiend(war: War): Boolean =
        war.me.playArea.cards.any { isAdrenalineFiend(it) && it.isAlive() }

    fun isAdrenalineFiend(card: Card): Boolean =
        card.cardId == ADRENALINE_FIEND

    /** A visible Taunt permits the hero to open combat after hand plays. */
    fun hasAttackableEnemyTaunt(war: War): Boolean =
        CardUtil.getTauntCards(war.rival.playArea.cards, true).any {
            it.cardType === CardTypeEnum.MINION && it.isAlive() && it.canBeAttacked()
        }

    /** Whether the current state can expose a legal hero attack. */
    fun hasHeroAttackAction(war: War): Boolean {
        val hero = war.me.playArea.hero ?: return false
        val weaponBacked = (war.me.playArea.weapon?.atc ?: 0) > 0 &&
            hero.canAttack(ignoreAtc = true)
        if (!hero.canAttack() && !weaponBacked) return false
        return runCatching {
            hero.action.generateAttackActions(war, war.me).isNotEmpty()
        }.getOrDefault(false)
    }

    /** Whether a usable hero-power action is currently generated. */
    fun hasUsableHeroPowerAction(war: War): Boolean {
        val power = war.me.playArea.power ?: return false
        if (war.me.usableResource < power.cost || !power.canPower()) return false
        return runCatching {
            power.action.generatePowerActions(war, war.me).isNotEmpty()
        }.getOrDefault(false)
    }

    fun isHeroPowerAction(action: Any): Boolean =
        action is PowerAction && action.creator?.cardType === CardTypeEnum.HERO_POWER
}
