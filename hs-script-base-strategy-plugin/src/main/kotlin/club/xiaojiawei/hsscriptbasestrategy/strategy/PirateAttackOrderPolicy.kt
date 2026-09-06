package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War

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
}
