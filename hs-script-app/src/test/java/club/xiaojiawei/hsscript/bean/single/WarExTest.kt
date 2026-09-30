package club.xiaojiawei.hsscript.bean.single

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import javafx.beans.value.ChangeListener

class WarExTest {

    @BeforeTest
    fun setUp() {
        WarEx.resetStatistics()
        WarEx.reset(print = false)
    }

    @AfterTest
    fun tearDown() {
        WarEx.resetStatistics()
        WarEx.reset(print = false)
    }

    @Test
    fun `authoritative loss clears stale win even when player identity is unknown`() {
        WarEx.isWin = true

        WarEx.endWar(resultOverride = false)

        assertFalse(WarEx.isWin)
        assertEquals(0, WarEx.winCount)
        assertEquals(0, WarEx.winStreak)
    }

    @Test
    fun `authoritative win is retained`() {
        WarEx.isWin = false

        WarEx.endWar(resultOverride = true)

        assertTrue(WarEx.isWin)
        assertEquals(1, WarEx.winCount)
        assertEquals(1, WarEx.winStreak)
        assertEquals(1, WarEx.warCount)
        assertEquals(1, WarEx.playedCount)
        assertEquals(1, WarEx.playedWinCount)
    }

    @Test
    fun `proactive surrender is excluded from live played count and win rate`() {
        WarEx.surrenderRequested = true
        WarEx.isWin = true

        WarEx.endWar(resultOverride = false)

        assertEquals(1, WarEx.warCount)
        assertEquals(0, WarEx.playedCount)
        assertEquals(0, WarEx.playedWinCount)
    }

    @Test
    fun `all matches ordinal is stable before completion and advances after completion`() {
        assertEquals(1, WarEx.reserveCompletedGameNumber())
        // The screenshot/wait boundary may ask more than once; reservation is idempotent.
        assertEquals(1, WarEx.nextCompletedGameNumber())

        WarEx.endWar(resultOverride = false)

        assertEquals(1, WarEx.warCount)
        assertEquals(2, WarEx.nextCompletedGameNumber())
    }

    @Test
    fun `duplicate terminal result is ignored after the first commit`() {
        WarEx.endWar(resultOverride = false)
        WarEx.endWar(resultOverride = true)

        assertEquals(1, WarEx.warCount)
        assertEquals(1, WarEx.playedCount)
        assertEquals(0, WarEx.playedWinCount)
        assertEquals(1, WarEx.lastCompletedGameNumber)
    }

    @Test
    fun `reserved ordinal is the same one committed after screenshot boundary`() {
        val reservedForEvidence = WarEx.reserveCompletedGameNumber()
        // Simulates result screenshot capture occurring before the counter commit.
        assertEquals(reservedForEvidence, WarEx.nextCompletedGameNumber())

        WarEx.endWar(resultOverride = true)

        assertEquals(reservedForEvidence, WarEx.lastCompletedGameNumber)
        assertEquals(reservedForEvidence, WarEx.warCount)
    }

    @Test
    fun `counter listeners see the current ordinal for wins and surrenders`() {
        val observedOrdinals = mutableListOf<Int?>()
        val listener = ChangeListener<Number> { _, _, _ ->
            observedOrdinals.add(WarEx.lastCompletedGameNumber)
        }
        WarEx.warCountProperty.addListener(listener)
        try {
            WarEx.endWar(resultOverride = true)
            WarEx.reset(print = false)
            WarEx.surrenderRequested = true
            WarEx.endWar(resultOverride = false)

            assertEquals(listOf<Int?>(1, 2), observedOrdinals)
        } finally {
            WarEx.warCountProperty.removeListener(listener)
        }
    }

    @Test
    fun `reset clears total and played scopes independently`() {
        WarEx.endWar(resultOverride = true)
        WarEx.reset(print = false)
        WarEx.surrenderRequested = true
        WarEx.endWar(resultOverride = false)

        assertEquals(2, WarEx.warCount)
        assertEquals(1, WarEx.playedCount)
        assertEquals(1, WarEx.playedWinCount)

        WarEx.resetStatistics()

        assertEquals(0, WarEx.warCount)
        assertEquals(0, WarEx.playedCount)
        assertEquals(0, WarEx.playedWinCount)
        assertEquals(1, WarEx.nextCompletedGameNumber())
    }

    @Test
    fun `unresolved empty IDs cannot be classified as our win`() {
        assertFalse(WarEx.isOurWin("", ""))
        assertFalse(WarEx.isOurWin("", "laz#12793"))
        assertFalse(WarEx.isOurWin("Glide#31734", ""))
        assertTrue(WarEx.isOurWin("laz#12793", "laz#12793"))
    }
}
