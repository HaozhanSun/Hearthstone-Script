package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum

/**
 * Timing rules for cards whose value increases when earlier actions are
 * completed.  This is deliberately shared by the MCTS action generator and
 * the live turn-end safety pass so the two execution paths cannot disagree.
 *
 * The IDs are more reliable than attack/health because Hearthstone applies
 * buffs, variants, and temporary enchantments to the live Card object.
 */
object CardTimingPolicy {

    const val PATCHES_THE_PIRATE_ID = "CFM_637"
    const val RAGEWING_ID = "YOD_032"
    const val ZILLIAX_DELUXE_3000_ID_PREFIX = "TOY_330"

    private val ragewingNames = setOf(
        "狂暴邪翼蝠",
        "狂暴血义斧",
        "狂暴血翼斧",
    )

    fun isPatchesThePirate(card: Card): Boolean =
        card.cardId == PATCHES_THE_PIRATE_ID ||
            card.cardId.contains(PATCHES_THE_PIRATE_ID) ||
            card.entityName == "海盗帕奇斯"

    /**
     * The current card database identifies the described 4-mana 3/3 card as
     * YOD_032 / 狂暴邪翼蝠.  Name aliases retain compatibility with the
     * alternate name used by the user's deck/listing.
     */
    fun isOpponentDamageReductionCard(card: Card): Boolean =
        card.cardId == RAGEWING_ID ||
            card.cardId.contains(RAGEWING_ID) ||
            ragewingNames.contains(card.entityName)

    fun isZilliaxDeluxe3000(card: Card): Boolean =
        card.cardId.startsWith(ZILLIAX_DELUXE_3000_ID_PREFIX) ||
            card.entityName.contains("奇莉亚斯豪华版3000型") ||
            card.entityName.contains("奇利亚斯豪华版3000型") ||
            card.entityName.contains("奇莉亚斯豪华版三千型") ||
            card.entityName.contains("奇利亚斯豪华版三千型")

    fun isEndOfTurnCostReductionCard(card: Card): Boolean =
        isOpponentDamageReductionCard(card) || isZilliaxDeluxe3000(card)

    /**
     * Keep a timing card out of the action set while another useful action is
     * available.  Attacks count as useful actions, which makes the MCTS path
     * attack first and only then consider the damage-reduction card.
     */
    fun shouldDefer(card: Card, war: War): Boolean {
        if (!isEndOfTurnCostReductionCard(card)) return false

        val me = war.me
        val hasPlayableOtherHandCard = me.handArea.cards.any { other ->
            other.entityId != card.entityId &&
                !other.isUncertain &&
                !isEndOfTurnCostReductionCard(other) &&
                isHandCardPlayable(other, me.usableResource, me.playArea.isFull, war)
        }
        if (hasPlayableOtherHandCard) return true

        val hasAttack = me.playArea.cards.any { it.canAttack() } || me.playArea.hero?.canAttack() == true
        if (hasAttack) return true

        val power = me.playArea.power
        return power != null &&
            power.canPower() &&
            !power.isExhausted &&
            me.usableResource >= power.cost
    }

    /**
     * Apply the two explicitly described dynamic reductions to a simulated
     * MCTS node.  The live parser remains authoritative for the root cost;
     * this only applies reductions caused by actions simulated between nodes.
     */
    fun applySimulatedReductions(before: War, after: War) {
        val opponentHeroDamageBefore = before.rival.playArea.hero?.damage ?: 0
        val opponentHeroDamageAfter = after.rival.playArea.hero?.damage ?: 0
        val opponentHeroDamageDelta = (opponentHeroDamageAfter - opponentHeroDamageBefore).coerceAtLeast(0)
        if (opponentHeroDamageDelta > 0) {
            after.me.handArea.cards
                .filter(::isOpponentDamageReductionCard)
                .forEach { card ->
                    card.cost = (card.cost - opponentHeroDamageDelta).coerceAtLeast(0)
                }
        }

        val friendlyMinionsBefore = before.me.playArea.cards.count { it.cardType === CardTypeEnum.MINION }
        val friendlyMinionsAfter = after.me.playArea.cards.count { it.cardType === CardTypeEnum.MINION }
        val newlySummonedMinions = (friendlyMinionsAfter - friendlyMinionsBefore).coerceAtLeast(0)
        if (newlySummonedMinions > 0) {
            after.me.handArea.cards
                .filter(::isZilliaxDeluxe3000)
                .forEach { card ->
                    card.cost = (card.cost - newlySummonedMinions).coerceAtLeast(0)
                }
        }
    }

    private fun isHandCardPlayable(card: Card, usableMana: Int, boardFull: Boolean, war: War): Boolean {
        if (card.cost > usableMana) return false
        if (card.cardType === CardTypeEnum.MINION && boardFull) return false
        // Match the upstream MCTS action contract: a card is only a useful
        // competing action when its CardAction actually produced an action.
        // A collectible card with an empty parser result is not a reason to
        // defer a playable timing card; the turn-end guard may still attempt
        // the live direct-play fallback after MCTS has exhausted parsed moves.
        return card.action.generatePlayActions(war, war.me).isNotEmpty()
    }
}
