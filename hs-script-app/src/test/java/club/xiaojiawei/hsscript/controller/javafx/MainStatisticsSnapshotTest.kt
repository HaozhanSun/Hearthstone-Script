package club.xiaojiawei.hsscript.controller.javafx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainStatisticsSnapshotTest {

    @Test
    fun `initial values are shared and do not produce NaN`() {
        assertEquals(
            MainStatisticsSnapshot("0", "?", "0", "0"),
            MainStatisticsSnapshot.from(0, 0, 0, 0),
        )
    }

    @Test
    fun `played game values format the same values for both panes`() {
        assertEquals(
            MainStatisticsSnapshot("30", "70.0%", "7h12m", "2868"),
            MainStatisticsSnapshot.from(30, 21, 432, 2868),
        )
        assertEquals(
            MainStatisticsSnapshot("31", "71.0%", "7h30m", "3000"),
            MainStatisticsSnapshot.from(31, 22, 450, 3000),
        )
    }

    @Test
    fun `long durations remain compact in the narrow pane`() {
        assertEquals("2d3h", MainStatisticsSnapshot.from(1, 1, 3060, 10).gameTime)
        assertEquals("59m", MainStatisticsSnapshot.from(1, 1, 59, 10).gameTime)
    }
}
