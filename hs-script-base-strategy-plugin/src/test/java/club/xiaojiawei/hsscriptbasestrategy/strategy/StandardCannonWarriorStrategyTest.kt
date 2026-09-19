package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.War
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StandardCannonWarriorStrategyTest {
    @Test
    fun `standard cannon warrior is selectable only in standard`() {
        val strategy = HsStandardCannonWarriorMctsDeckStrategy()

        assertEquals("Standard Cannon Warrior V1.0", strategy.name())
        assertEquals(arrayOf(RunModeEnum.STANDARD).toList(), strategy.runModes.toList())
        assertTrue(strategy.id().contains("standard-cannon-warrior-v1-0"))
        assertNotEquals(strategy.id(), HsPirateWarriorMctsDeckStrategy().id())
    }

    @Test
    fun `standard strategy uses its own confirmed card model and turn plan`() {
        val strategy = HsStandardCannonWarriorMctsDeckStrategy()
        val arg = strategy.executeMCTSOutCard(testWar()).single()

        assertTrue(arg.experimentalSearch)
        assertTrue(arg.experimentalActionBudgetMillis > 0)
        assertTrue(arg.decisionModel === StandardCannonWarriorMctsModel)
    }

    @Test
    fun `standard strategy service entry is present`() {
        val entries = javaClass.classLoader
            .getResourceAsStream("META-INF/services/club.xiaojiawei.hsscriptstrategysdk.DeckStrategy")
            ?.bufferedReader()
            ?.readLines()
            .orEmpty()

        assertTrue(
            entries.contains(
                "club.xiaojiawei.hsscriptbasestrategy.strategy.HsStandardCannonWarriorMctsDeckStrategy",
            ),
        )
    }

    @Test
    fun `the 18-card inventory is complete and every row is auditable`() {
        val inventory = StandardCannonWarriorCardInventory.cards

        assertEquals(18, inventory.size)
        assertEquals(18, inventory.map { it.displayName }.distinct().size)
        assertEquals(StandardCannonWarriorCardInventory.screenshotCards,
            inventory.map { it.displayName to it.currentCardId })
        assertEquals(11, inventory.count { it.currentCardId != StandardCannonWarriorCardInventory.UNKNOWN_CARD_ID })
        assertEquals(7, inventory.count { it.currentCardId == StandardCannonWarriorCardInventory.UNKNOWN_CARD_ID })
        assertEquals(11, inventory.filter { it.currentCardId != StandardCannonWarriorCardInventory.UNKNOWN_CARD_ID }
            .map { it.currentCardId }.distinct().size)
        assertTrue(inventory.all { it.role.isNotBlank() && it.priority.isNotBlank() })
        assertTrue(inventory.all { it.prerequisite.isNotBlank() && it.safeFallback.isNotBlank() })
        assertTrue(
            inventory.filter { it.currentCardId != StandardCannonWarriorCardInventory.UNKNOWN_CARD_ID }
                .all { !it.offlineAssertion.isNullOrBlank() },
        )
        assertTrue(
            inventory.filter { it.currentCardId == StandardCannonWarriorCardInventory.UNKNOWN_CARD_ID }
                .all {
                    it.offlineAssertion == null &&
                        it.idEvidence.contains("no unique row") &&
                        it.safeFallback.lowercase().contains("parser")
                },
        )
    }

    private fun testWar(): War {
        val war = War()
        val me = Player(playerId = "me", war = war)
        val rival = Player(playerId = "rival", war = war)
        war.me = me
        war.rival = rival
        war.player1 = me
        war.player2 = rival
        war.currentPlayer = me
        war.isMyTurn = true
        me.turn = 2
        me.resources = 4
        return war
    }
}


