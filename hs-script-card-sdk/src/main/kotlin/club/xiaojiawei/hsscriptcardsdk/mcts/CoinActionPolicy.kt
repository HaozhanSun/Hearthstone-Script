package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum

/**
 * Shared Coin gating used by both the MCTS root and the live end-turn scan.
 * Coin is meaningful only when it immediately unlocks an otherwise
 * unaffordable non-Coin card.  In particular, Coin -> hero power alone is
 * not a reason to keep the turn open.
 */
object CoinActionPolicy {
    fun hasImmediatePayoff(war: War): Boolean {
        val me = war.me
        val currentMana = me.usableResource
        val coinMana = currentMana + 1
        val boardFull = me.playArea.isFull
        return me.handArea.cards.any { card ->
            !card.isUncertain &&
                !card.isCoinCard &&
                !CardTimingPolicy.shouldDefer(card, war) &&
                card.cost > currentMana &&
                card.cost <= coinMana &&
                (!boardFull || card.cardType === CardTypeEnum.HERO ||
                    card.cardType === CardTypeEnum.SPELL ||
                    card.cardType === CardTypeEnum.WEAPON)
        }
    }
}
