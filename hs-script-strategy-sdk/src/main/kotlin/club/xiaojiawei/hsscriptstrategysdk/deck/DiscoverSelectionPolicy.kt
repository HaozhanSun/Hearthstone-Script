package club.xiaojiawei.hsscriptstrategysdk.deck

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy

/** An opt-in deck rule can narrow the offered set before generic preferences run. */
data class MctsDiscoverCandidateOverride(
    val candidateIndices: List<Int>,
    val reason: String,
)

data class DiscoverSelectionDecision(
    val index: Int,
    val reason: String,
    val candidateIndices: List<Int>,
    val deckMatchIndices: List<Int>,
)

/** Shared, deterministic Discover ranking used by every active deck strategy. */
object DiscoverSelectionPolicy {
    /**
     * Project from the current turn's starting crystal count, not its unused
     * remainder. Player.overloadLocked is documented as crystals locked at
     * the start of the current turn, so it is not subtracted again here. The
     * model does not reliably expose overload debt created during this turn;
     * callers may pass an explicitly known next-turn lock estimate.
     */
    fun nextTurnAvailableMana(
        currentTurnCrystals: Int,
        maxCrystals: Int,
        knownNextTurnLockedCrystals: Int = 0,
    ): Int =
        ((currentTurnCrystals.coerceAtLeast(0) + 1)
            .coerceAtMost(maxCrystals.coerceAtLeast(0)) - knownNextTurnLockedCrystals.coerceAtLeast(0))
            .coerceAtLeast(0)

    fun select(
        strategy: DeckStrategy,
        cards: List<Card>,
        handSize: Int,
        selectedDeckCardIds: Set<String>,
        deckSnapshotStatus: String,
        override: MctsDiscoverCandidateOverride? = null,
    ): DiscoverSelectionDecision = select(
        cards = cards,
        handSize = handSize,
        selectedDeckCardIds = selectedDeckCardIds,
        deckSnapshotStatus = deckSnapshotStatus,
        override = override,
    ) { candidates ->
        strategy.executeDiscoverChooseCard(*candidates.toTypedArray())
    }

    /**
     * With a small hand, keep the option with the most remaining mana value.
     * With a large hand, constrain to cards in the selected deck when possible,
     * then use the strategy's existing scorer. The scorer receives a stable
     * card ordering so score ties do not depend on Discover animation order.
     */
    fun select(
        cards: List<Card>,
        handSize: Int,
        selectedDeckCardIds: Set<String>,
        deckSnapshotStatus: String,
        override: MctsDiscoverCandidateOverride? = null,
        scorer: (List<Card>) -> Int,
    ): DiscoverSelectionDecision {
        if (cards.isEmpty()) return DiscoverSelectionDecision(0, "empty-offer", emptyList(), emptyList())

        val validOverrideIndices = override?.candidateIndices
            ?.filter { it in cards.indices }
            ?.distinct()
            .orEmpty()
        val scopedIndices = validOverrideIndices.takeIf { it.isNotEmpty() } ?: cards.indices.toList()
        val elementalOverrideActive = validOverrideIndices.isNotEmpty()
        val deckMatches = scopedIndices.filter { canonicalCardId(cards[it].cardId) in selectedDeckCardIds }

        if (handSize <= SMALL_HAND_MAX_SIZE) {
            val selected = scopedIndices.minWithOrNull(
                compareByDescending<Int> { cards[it].cost }
                    .thenBy { cards[it].cardId }
                    .thenBy { cards[it].entityId }
                    .thenBy { it },
            ) ?: 0
            return DiscoverSelectionDecision(
                index = selected,
                reason = buildString {
                    if (elementalOverrideActive) append("${override?.reason};")
                    append("hand<=3-highest-cost")
                },
                candidateIndices = scopedIndices,
                deckMatchIndices = deckMatches,
            )
        }

        val preferredIndices = deckMatches.takeIf { it.isNotEmpty() } ?: scopedIndices
        val orderedPreferred = preferredIndices.sortedWith(
            compareBy<Int> { cards[it].cardId }
                .thenBy { cards[it].entityId }
                .thenBy { it },
        )
        val scorerResult = runCatching { scorer(orderedPreferred.map(cards::get)) }
        val selectedLocal = scorerResult.getOrNull() ?: -1
        val validScorerIndex = selectedLocal in orderedPreferred.indices
        val selected = orderedPreferred.getOrNull(selectedLocal) ?: orderedPreferred.firstOrNull() ?: 0
        val genericReason = if (!scorerResult.isSuccess || !validScorerIndex) {
            "strategy-scorer-invalid-deterministic-fallback"
        } else if (deckMatches.isNotEmpty()) {
            "hand>=4-deck-match-existing-scorer"
        } else {
            "hand>=4-no-deck-match-$deckSnapshotStatus-existing-scorer"
        }
        return DiscoverSelectionDecision(
            index = selected,
            reason = listOfNotNull(override?.reason.takeIf { elementalOverrideActive }, genericReason).joinToString(";"),
            candidateIndices = orderedPreferred,
            deckMatchIndices = deckMatches,
        )
    }

    fun canonicalCardId(cardId: String): String = cardId.removePrefix("CORE_")

    private const val SMALL_HAND_MAX_SIZE = 3
}
