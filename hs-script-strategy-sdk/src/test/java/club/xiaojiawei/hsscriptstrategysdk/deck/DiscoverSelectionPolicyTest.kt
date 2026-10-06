package club.xiaojiawei.hsscriptstrategysdk.deck

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.TestCardAction
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import java.util.HashSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MctsDiscoverSelectionPolicyTest {
    @Test
    fun `hand size three selects highest cost with stable tie break`() {
        val cards = listOf(
            card("Z_CARD", "z", 5),
            card("B_CARD", "b", 7),
            card("A_CARD", "a", 7),
        )

        val decision = DiscoverSelectionPolicy.select(cards, 3, emptySet(), "no-deck") { 0 }

        assertEquals(2, decision.index)
        assertEquals("hand<=3-highest-cost", decision.reason)
    }

    @Test
    fun `hand size four restricts scorer to selected deck matches`() {
        val cards = listOf(card("OUTSIDE", "outside", 8), card("DECK_A", "a", 2), card("DECK_B", "b", 4))

        val decision = DiscoverSelectionPolicy.select(cards, 4, setOf("DECK_A", "DECK_B"), "ready") { subset ->
            subset.indexOfFirst { it.cardId == "DECK_B" }
        }

        assertEquals(2, decision.index)
        assertEquals(listOf(1, 2), decision.deckMatchIndices)
        assertEquals("hand>=4-deck-match-existing-scorer", decision.reason)
    }

    @Test
    fun `multiple deck matches use scorer while no match preserves scorer fallback`() {
        val cards = listOf(card("OUTSIDE", "outside", 9), card("MATCH_B", "b", 3), card("MATCH_A", "a", 6))
        val multiple = DiscoverSelectionPolicy.select(cards, 5, setOf("MATCH_A", "MATCH_B"), "ready") { subset ->
            subset.indexOfFirst { it.cardId == "MATCH_B" }
        }
        val noMatch = DiscoverSelectionPolicy.select(cards, 5, setOf("OTHER"), "ready") { subset ->
            subset.indexOfFirst { it.cardId == "OUTSIDE" }
        }

        assertEquals(1, multiple.index)
        assertEquals(0, noMatch.index)
        assertTrue(noMatch.reason.contains("no-deck-match-ready-existing-scorer"))
    }

    @Test
    fun `invalid scorer result falls back deterministically`() {
        val cards = listOf(card("Z", "z", 1), card("A", "a", 1))

        val decision = DiscoverSelectionPolicy.select(cards, 6, emptySet(), "unavailable") { -1 }

        assertEquals(1, decision.index)
        assertTrue(decision.reason.contains("strategy-scorer-invalid-deterministic-fallback"))
    }

    @Test
    fun `next turn mana uses crystal count rather than remaining mana and caps at maximum`() {
        val player = Player(playerId = "me").apply {
            resources = 6
            usedResources = 5
            maxResources = 10
        }
        assertEquals(1, player.usableResource)
        assertEquals(7, DiscoverSelectionPolicy.nextTurnAvailableMana(player.resources, player.maxResources))
        assertEquals(10, DiscoverSelectionPolicy.nextTurnAvailableMana(10, 10))
        assertEquals(0, DiscoverSelectionPolicy.nextTurnAvailableMana(-2, -1))
        assertEquals(4, DiscoverSelectionPolicy.nextTurnAvailableMana(5, 10, knownNextTurnLockedCrystals = 2))
    }

    @Test
    fun `generic policy wraps a non-MCTS deck strategy chooser`() {
        val strategy = NonMctsChooser()
        val cards = listOf(card("OUTSIDE", "outside", 9), card("SELECTED_DECK", "selected", 2))

        val decision = DiscoverSelectionPolicy.select(
            strategy = strategy,
            cards = cards,
            handSize = 4,
            selectedDeckCardIds = setOf("SELECTED_DECK"),
            deckSnapshotStatus = "ready",
        )

        assertEquals(1, decision.index)
        assertEquals("SELECTED_DECK", cards[decision.index].cardId)
        assertTrue(decision.reason.contains("deck-match-existing-scorer"))
    }

    private fun card(cardId: String, name: String, cost: Int): Card = Card(TestCardAction()).apply {
        this.cardId = cardId
        this.entityName = name
        this.cost = cost
        entityId = "$cardId-entity"
        action.belongCard = this
    }

    private class NonMctsChooser : DeckStrategy() {
        override fun name(): String = "non-MCTS-test"
        override fun getRunMode(): Array<RunModeEnum> = arrayOf(RunModeEnum.CASUAL)
        override fun deckCode(): String = ""
        override fun id(): String = "non-mcts-test"
        override fun executeChangeCard(cards: HashSet<Card>) = Unit
        override fun executeOutCard() = Unit
        override fun executeDiscoverChooseCard(vararg cards: Card): Int =
            cards.indices.maxByOrNull { cards[it].cost } ?: 0
    }
}
