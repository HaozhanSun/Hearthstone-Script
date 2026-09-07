package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum

/**
 * Shared, side-effect-free legality primitives used by the MCTS scan and the
 * application end-turn guard. Keeping these predicates in the card SDK
 * prevents small copies from drifting at module boundaries.
 */
object MctsActionAvailability {
    fun isCostPayable(cost: Int, usableMana: Int): Boolean = cost <= usableMana

    fun isHeroPowerPlayable(
        powerCost: Int,
        usableMana: Int,
        canPower: Boolean,
    ): Boolean = canPower && isCostPayable(powerCost, usableMana)

    fun isPermanentPlayBlockedByFullBoard(
        cardType: CardTypeEnum,
        boardFull: Boolean,
    ): Boolean = boardFull && cardType !== CardTypeEnum.HERO &&
        cardType !== CardTypeEnum.SPELL && cardType !== CardTypeEnum.WEAPON
}
