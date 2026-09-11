package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum

/** Shared combat rule for Hookfist-3000 (勾拳3000型). */
object PirateDamageAuraPolicy {
    const val HOOKFIST_CORE_ID = "CORE_NX2_028"
    const val HOOKFIST_ID = "NX2_028"

    fun isHookfist3000(card: Card): Boolean =
        card.cardId == HOOKFIST_CORE_ID || card.cardId == HOOKFIST_ID

    fun isFriendlyPirateDamageSource(card: Card, war: War): Boolean =
        card.area.player === war.me &&
            (card.cardRace === CardRaceEnum.PIRATE || card.cardRace === CardRaceEnum.ALL) &&
            war.currentPlayer === war.me && war.isMyTurn

    fun activeHookfistCount(war: War): Int {
        if (war.currentPlayer !== war.me || !war.isMyTurn) return 0
        return war.me.playArea.cards.count { isHookfist3000(it) && it.isAlive() }
    }

    fun outgoingDamage(attacker: Card, baseDamage: Int, war: War): Int =
        (baseDamage + if (isFriendlyPirateDamageSource(attacker, war)) activeHookfistCount(war) else 0)
            .coerceAtLeast(0)
}
