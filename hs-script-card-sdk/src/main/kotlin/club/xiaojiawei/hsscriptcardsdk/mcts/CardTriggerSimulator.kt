package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.util.CardDBUtil
import club.xiaojiawei.hsscriptcardsdk.util.CardUtil

/**
 * Models passive effects that change the result of playing another card.
 *
 * This belongs in the simulator rather than in a deck strategy.  A strategy
 * should compare actions by the resulting state; it should not need a special
 * "play pirate before DRG_056" branch.  The text lookup also gives us a path
 * to support more hand-triggered summon cards without adding one decision-tree
 * rule per card.
 */
object CardTriggerSimulator {

    private const val PARACHUTE_BRIGAND_ID = "DRG_056"

    private data class HandSummonRule(
        val requiredRace: CardRaceEnum,
        val reason: String,
    )

    data class TriggeredSummon(
        val card: Card,
        val reason: String,
    )

    private val ruleCache = mutableMapOf<String, HandSummonRule?>()
    private val htmlRegex = Regex("<.*?>")
    private val whitespaceRegex = Regex("\\s+")

    /**
     * Returns hand cards that are currently modeled as waiting for a tribal
     * card to be played.  This is also used by diagnostics and tests; it does
     * not mutate the war.
     */
    fun pendingHandSummonTriggers(handCards: List<Card>): List<Card> =
        handCards.filter { card ->
            card.cardType === CardTypeEnum.MINION && handSummonRule(card.cardId) != null
        }

    /**
     * Returns whether playing [card] can release at least one modeled trigger
     * from [handCards].  It intentionally describes a game relationship, not
     * a deck-specific priority.
     */
    fun isTriggerSource(card: Card, handCards: List<Card>): Boolean {
        return handCards.asSequence()
            .filter { it.cardType === CardTypeEnum.MINION }
            .mapNotNull { handSummonRule(it.cardId) }
            .any { rule -> isRaceMatch(card, rule.requiredRace) }
    }

    /**
     * Applies passive summons caused by a simulated play.  The caller must
     * already have simulated the played action itself.  Summons are free and
     * enter exhausted, matching the normal from-hand summon behavior used by
     * the card simulator.
     */
    fun simulateAfterPlay(war: War, playedCard: Card): List<TriggeredSummon> {
        if (playedCard.cardRace === CardRaceEnum.UNKNOWN) return emptyList()

        val pending = war.me.handArea.cards.toList().mapNotNull { card ->
            handSummonRule(card.cardId)?.let { rule -> card to rule }
        }
        if (pending.isEmpty() || war.me.playArea.isFull) return emptyList()

        val result = mutableListOf<TriggeredSummon>()
        for ((handCard, rule) in pending) {
            if (war.me.playArea.isFull) break
            if (!isRaceMatch(playedCard, rule.requiredRace)) continue
            val summoned = war.me.handArea.removeByEntityId(handCard.entityId) ?: continue
            CardUtil.handleCardExhaustedWhenIntoPlayArea(summoned)
            if (!war.me.playArea.safeAdd(summoned)) {
                // The board can change while an effect chain is being applied;
                // preserve the card rather than silently dropping it.
                war.me.handArea.add(summoned)
                continue
            }
            result += TriggeredSummon(summoned, rule.reason)
        }
        if (result.isNotEmpty()) {
            log.debug {
                "MCTS_SIMULATED_HAND_TRIGGER source=${playedCard.cardId.ifBlank { playedCard.entityName }} " +
                    "summoned=${result.joinToString { it.card.cardId.ifBlank { it.card.entityName } }} " +
                    "reason=${result.joinToString { it.reason }}"
            }
        }
        return result
    }

    /** CardRaceEnum.ALL is the local representation of a minion with every tribe. */
    fun isPirate(card: Card): Boolean =
        card.cardRace === CardRaceEnum.PIRATE || card.cardRace === CardRaceEnum.ALL

    private fun isRaceMatch(card: Card, requiredRace: CardRaceEnum): Boolean =
        requiredRace === CardRaceEnum.PIRATE && isPirate(card)

    private fun handSummonRule(cardId: String): HandSummonRule? {
        if (cardId.isBlank()) return null
        synchronized(ruleCache) {
            if (ruleCache.containsKey(cardId)) return ruleCache[cardId]

            // Compatibility fallback keeps the known card behavior available
            // even when a user starts the app with an incomplete card DB.
            val rule = if (cardId == PARACHUTE_BRIGAND_ID) {
                HandSummonRule(
                    requiredRace = CardRaceEnum.PIRATE,
                    reason = "空降歹徒：使用海盗后从手牌免费召唤",
                )
            } else {
                parseHandSummonRule(
                    runCatching { CardDBUtil.queryCardById(cardId).firstOrNull()?.text }.getOrNull()
                )
            }
            ruleCache[cardId] = rule
            return rule
        }
    }

    private fun parseHandSummonRule(text: String?): HandSummonRule? {
        val normalized = text
            ?.replace(htmlRegex, "")
            ?.replace(whitespaceRegex, "")
            ?.lowercase()
            ?: return null
        val afterPirate = normalized.contains("使用一张海盗牌后") ||
            normalized.contains("打出一张海盗牌后") ||
            normalized.contains("afteryouplayapirate")
        val summonSelf = normalized.contains("从你的手牌中召唤本随从") ||
            normalized.contains("从手牌中召唤本随从") ||
            normalized.contains("summonthisminionfromyourhand")
        if (!afterPirate || !summonSelf) return null
        return HandSummonRule(
            requiredRace = CardRaceEnum.PIRATE,
            reason = "卡牌文本：使用海盗后从手牌免费召唤",
        )
    }
}
