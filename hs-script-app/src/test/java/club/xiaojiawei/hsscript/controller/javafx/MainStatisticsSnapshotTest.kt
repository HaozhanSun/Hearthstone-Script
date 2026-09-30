package club.xiaojiawei.hsscript.controller.javafx

import club.xiaojiawei.hsscript.bean.single.WarEx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainStatisticsSnapshotTest {

    @Test
    fun `initial values are shared and do not produce NaN`() {
        assertEquals(
            MainStatisticsSnapshot("0", "0", "?", "0", "0"),
            MainStatisticsSnapshot.from(0, 0, 0, 0, 0),
        )
    }

    @Test
    fun `played game values format the same values for both panes`() {
        assertEquals(
            MainStatisticsSnapshot("32", "30", "70.0%", "7h12m", "2868"),
            MainStatisticsSnapshot.from(32, 30, 21, 432, 2868),
        )
        assertEquals(
            MainStatisticsSnapshot("33", "31", "71.0%", "7h30m", "3000"),
            MainStatisticsSnapshot.from(33, 31, 22, 450, 3000),
        )
    }

    @Test
    fun `long durations remain compact in the narrow pane`() {
        assertEquals("2d3h", MainStatisticsSnapshot.from(1, 1, 1, 3060, 10).gameTime)
        assertEquals("59m", MainStatisticsSnapshot.from(1, 1, 1, 59, 10).gameTime)
    }

    @Test
    fun `pending terminal snapshot uses the same reserved ordinal as result evidence`() {
        WarEx.resetStatistics()
        WarEx.reset(print = false)
        try {
            WarEx.endWar(resultOverride = false)
            val reserved = WarEx.reserveCompletedGameNumber()

            val pendingSnapshot = MainStatisticsSnapshot.from(
                totalGameCount = WarEx.effectiveGameCount,
                playedGameCount = WarEx.playedCount,
                playedWinCount = WarEx.playedWinCount,
                hangingTimeMinutes = WarEx.hangingTime,
                experience = WarEx.hangingEXP,
            )

            assertEquals(2, reserved)
            assertEquals("2", pendingSnapshot.totalGameCount)
            assertEquals(reserved, WarEx.effectiveGameCount)
        } finally {
            WarEx.resetStatistics()
            WarEx.reset(print = false)
        }
    }
}
