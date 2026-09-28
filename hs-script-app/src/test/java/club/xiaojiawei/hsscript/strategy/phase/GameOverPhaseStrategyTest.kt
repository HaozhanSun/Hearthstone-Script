package club.xiaojiawei.hsscript.strategy.phase

import club.xiaojiawei.hsscript.status.E2ETrace
import club.xiaojiawei.hsscript.status.ScreenWatchdogKind
import kotlin.test.Test
import kotlin.test.assertEquals

class GameOverPhaseStrategyTest {

    @Test
    fun `power log terminal maps to the correct win override`() {
        assertEquals(true, terminalToWinOverride(E2ETrace.PowerLogTerminal.WON))
        assertEquals(false, terminalToWinOverride(E2ETrace.PowerLogTerminal.LOST))
        assertEquals(false, terminalToWinOverride(E2ETrace.PowerLogTerminal.CONCEDED))
        assertEquals(null, terminalToWinOverride(null))
    }

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
    fun `authoritative current player loss cannot be rendered as draw`() {
        assertEquals(
            "loss",
            classifyResultOutcome(
                isWin = false,
                wonId = "",
                lostId = "",
                concededId = "",
                ourId = "",
                localSurrenderRequested = false,
                authoritativeTerminal = E2ETrace.PowerLogTerminal.LOST,
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
    fun `authoritative loss overrides stale model win`() {
        assertEquals(
            "loss",
            classifyResultOutcome(
                isWin = true,
                wonId = "stale-player",
                lostId = "",
                concededId = "",
                ourId = "laz#12793",
                localSurrenderRequested = false,
                authoritativeTerminal = E2ETrace.PowerLogTerminal.LOST,
            ),
        )
    }

    @Test
    fun `authoritative concession overrides stale model win`() {
        assertEquals(
            "conceded",
            classifyResultOutcome(
                isWin = true,
                wonId = "stale-player",
                lostId = "",
                concededId = "",
                ourId = "laz#12793",
                localSurrenderRequested = false,
                authoritativeTerminal = E2ETrace.PowerLogTerminal.CONCEDED,
            ),
        )
    }

    @Test
    fun `generic result screen requires authoritative Power log terminal before resetting war`() {
        assertEquals(
            ScreenWatchdogTerminalResolution(false, null, "draw-or-unknown"),
            resolveScreenWatchdogTerminal(ScreenWatchdogKind.RESULT, null),
        )
        assertEquals(
            ScreenWatchdogTerminalResolution(true, false, "loss"),
            resolveScreenWatchdogTerminal(ScreenWatchdogKind.RESULT, E2ETrace.PowerLogTerminal.LOST),
        )
        assertEquals(
            ScreenWatchdogTerminalResolution(true, false, "conceded"),
            resolveScreenWatchdogTerminal(ScreenWatchdogKind.RESULT, E2ETrace.PowerLogTerminal.CONCEDED),
        )
    }
}
