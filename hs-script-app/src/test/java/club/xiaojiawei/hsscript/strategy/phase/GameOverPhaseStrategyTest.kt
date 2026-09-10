package club.xiaojiawei.hsscript.strategy.phase

import club.xiaojiawei.hsscript.status.ScreenWatchdogKind
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

    @Test
    fun `watchdog unknown without a terminal marker returns to the normal phase listener`() {
        assertEquals(
            GameOverPhaseStrategy.ScreenWatchdogNextHandler.NORMAL_PHASE_LISTENER,
            GameOverPhaseStrategy.selectWatchdogHandoffForTest(ScreenWatchdogKind.UNKNOWN, terminalMarker = false),
        )
    }

    @Test
    fun `Chinese mulligan watchdog observation returns to the mulligan listener`() {
        assertEquals(
            GameOverPhaseStrategy.ScreenWatchdogNextHandler.NORMAL_PHASE_LISTENER,
            GameOverPhaseStrategy.selectWatchdogHandoffForTest(ScreenWatchdogKind.MULLIGAN, terminalMarker = false),
        )
    }

    @Test
    fun `terminal Power log marker owns settlement before visual recovery`() {
        assertEquals(
            GameOverPhaseStrategy.ScreenWatchdogNextHandler.GAME_OVER_HANDLER,
            GameOverPhaseStrategy.selectWatchdogHandoffForTest(ScreenWatchdogKind.UNKNOWN, terminalMarker = true),
        )
    }

    @Test
    fun `generic result gets one result page continuation path`() {
        assertEquals(
            GameOverPhaseStrategy.ScreenWatchdogNextHandler.RESULT_PAGE_CONTINUE,
            GameOverPhaseStrategy.selectWatchdogHandoffForTest(ScreenWatchdogKind.RESULT, terminalMarker = false),
        )
    }

    @Test
    fun `visual win remains a fallback when Power log marker is absent`() {
        assertEquals(
            GameOverPhaseStrategy.ScreenWatchdogNextHandler.VISUAL_TERMINAL_FALLBACK,
            GameOverPhaseStrategy.selectWatchdogHandoffForTest(ScreenWatchdogKind.WIN, terminalMarker = false),
        )
    }
}
