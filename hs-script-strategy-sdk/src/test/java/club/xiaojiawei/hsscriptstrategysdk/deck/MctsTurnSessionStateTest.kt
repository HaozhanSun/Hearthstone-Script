package club.xiaojiawei.hsscriptstrategysdk.deck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MctsTurnSessionStateTest {
    @Test
    fun `same turn in a new game clears suppression and weapon state`() {
        val state = MctsTurnSessionState()
        val firstGame = key("game-a", "strategy-a")
        val secondGame = key("game-b", "strategy-a")

        assertTrue(state.begin(firstGame))
        state.nextCycle()
        state.markWeaponPlayed()
        state.suppressCreator("weapon-a")
        state.suppressCard("GDB_305")

        assertTrue(state.begin(secondGame))
        assertEquals(0, state.cycle)
        assertFalse(state.weaponPlayed)
        assertTrue(state.suppressedCreatorIds().isEmpty())
        assertTrue(state.suppressedCardIds().isEmpty())
    }

    @Test
    fun `strategy switch clears the live session even in the same game and turn`() {
        val state = MctsTurnSessionState()
        val firstStrategy = key("game-a", "strategy-a")
        val secondStrategy = key("game-a", "strategy-b")

        state.begin(firstStrategy)
        state.nextCycle()
        state.suppressCreator("location-a")
        state.suppressCard("GDB_305")

        assertTrue(state.begin(secondStrategy))
        assertEquals(0, state.cycle)
        assertTrue(state.suppressedCreatorIds().isEmpty())
        assertTrue(state.suppressedCardIds().isEmpty())
    }

    @Test
    fun `same session preserves state while a new turn starts clean`() {
        val state = MctsTurnSessionState()
        val turnOne = key("game-a", "strategy-a", turn = 1)
        val turnTwo = key("game-a", "strategy-a", turn = 2)

        state.begin(turnOne)
        state.nextCycle()
        state.markWeaponPlayed()
        state.suppressCreator("weapon-a")
        state.suppressCard("GDB_305")
        assertFalse(state.begin(turnOne))
        assertEquals(1, state.cycle)
        assertTrue(state.weaponPlayed)

        assertTrue(state.begin(turnTwo))
        assertEquals(0, state.cycle)
        assertFalse(state.weaponPlayed)
        assertTrue(state.suppressedCreatorIds().isEmpty())
        assertTrue(state.suppressedCardIds().isEmpty())
    }

    private fun key(gameId: String, strategyId: String, turn: Int = 1) = MctsTurnSessionKey(
        localGameId = gameId,
        rivalGameId = "rival-$gameId",
        firstPlayerGameId = gameId,
        startTime = 123L,
        strategyId = strategyId,
        turn = turn,
    )
}
