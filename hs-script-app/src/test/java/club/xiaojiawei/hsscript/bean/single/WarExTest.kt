package club.xiaojiawei.hsscript.bean.single

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        assertEquals(1, WarEx.nextCompletedGameNumber())

        WarEx.endWar(resultOverride = false)

        assertEquals(1, WarEx.warCount)
        assertEquals(2, WarEx.nextCompletedGameNumber())
    }

    @Test
    fun `reset clears total and played scopes independently`() {
        WarEx.endWar(resultOverride = true)
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
