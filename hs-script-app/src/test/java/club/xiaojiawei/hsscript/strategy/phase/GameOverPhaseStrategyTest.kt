package club.xiaojiawei.hsscript.strategy.phase

import kotlin.test.Test
import kotlin.test.assertEquals

class GameOverPhaseStrategyTest {

    @Test
    fun `local surrender wins over unresolved terminal IDs`() {
        assertEquals(
            "conceded",
            classifyResultOutcome(
                isWin = false,
                wonId = "",
                lostId = "",
                concededId = "",
                ourId = "",
                localSurrenderRequested = true,
            ),
        )
    }

    @Test
    fun `blank IDs do not become a loss or win`() {
        assertEquals(
            "draw-or-unknown",
            classifyResultOutcome(
                isWin = false,
                wonId = "",
                lostId = "",
                concededId = "",
                ourId = "",
                localSurrenderRequested = false,
            ),
        )
    }

    @Test
    fun `explicit player terminal IDs retain their meaning`() {
        assertEquals(
            "loss",
            classifyResultOutcome(false, "", "laz#12793", "", "laz#12793", false),
        )
        assertEquals(
            "opponent-win",
            classifyResultOutcome(false, "Glide#31734", "", "", "laz#12793", false),
        )
    }
}
