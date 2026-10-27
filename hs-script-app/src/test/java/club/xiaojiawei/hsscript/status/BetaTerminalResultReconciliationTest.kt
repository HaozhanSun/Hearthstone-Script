package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.status.surrender.CurrentGamePowerLogTerminalTracker
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingInputGuardPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BetaTerminalResultReconciliationTest {

    @Test
    fun `queue create game final gameover and unknown capture reconcile from current terminal evidence`() {
        val tracker = CurrentGamePowerLogTerminalTracker()
        tracker.observeLine("D 06:00:00.000 PowerTaskList.DebugPrintPower() - CREATE_GAME")
        tracker.observeLine("Player EntityID=1 PlayerID=1 GameAccountId=[hi=12 lo=34]")
        tracker.observeLine("Player EntityID=2 PlayerID=2 GameAccountId=[hi=56 lo=78]")
        tracker.observeLine("PlayerID=1, PlayerName=laz#12793")
        tracker.observeLine("PlayerID=2, PlayerName=Rumble#1234")
        tracker.bindVerifiedLocalEntity("laz#12793")
        tracker.observeLine("D 06:09:32.000 TAG_CHANGE Entity=laz#12793 tag=PLAYSTATE value=WON")
        tracker.observeLine("D 06:09:32.100 TAG_CHANGE Entity=Rumble#1234 tag=PLAYSTATE value=CONCEDED")
        tracker.observeLine("D 06:09:32.200 TAG_CHANGE Entity=GameEntity tag=STEP value=FINAL_GAMEOVER")
        tracker.observeLine("D 06:09:32.300 TAG_CHANGE Entity=GameEntity tag=STATE value=COMPLETE")

        assertTrue(tracker.hasCompleteTerminalEvidence())
        val evidence = tracker.currentGameSurrenderEvidence("laz#12793", "Rumble#1234")
        assertEquals("WON", evidence?.ownPlayState)
        assertEquals("CONCEDED", evidence?.opponentPlayState)
        assertEquals(
            MatchmakingInputGuardPolicy.Decision.ABORT_FOR_TERMINAL_CLEANUP,
            MatchmakingInputGuardPolicy.decide(
                observedScreen = "UNKNOWN",
                recentActiveSearchEvidence = true,
                terminalCleanupPending = true,
            ),
        )
        assertTrue(
            BetaTerminalResultReconciliationPolicy.shouldReconcile(
                channel = "beta",
                phase = "FILL_DECK",
                activeGame = false,
                completeCurrentGameTerminal = tracker.hasCompleteTerminalEvidence(),
                resultAlreadyHandled = false,
            ),
        )
    }

    @Test
    fun `a fresh matchmaking screen remains protected even when terminal cleanup is pending`() {
        assertEquals(
            MatchmakingInputGuardPolicy.Decision.BLOCK_ACTIVE_SEARCH,
            MatchmakingInputGuardPolicy.decide(
                observedScreen = "MATCHMAKING",
                recentActiveSearchEvidence = false,
                terminalCleanupPending = true,
            ),
        )
    }

    @Test
    fun `terminal reconciliation stays Beta only and requires current terminal evidence`() {
        assertFalse(
            BetaTerminalResultReconciliationPolicy.shouldReconcile(
                channel = "rc",
                phase = "FILL_DECK",
                activeGame = false,
                completeCurrentGameTerminal = true,
                resultAlreadyHandled = false,
            ),
        )
        assertFalse(
            BetaTerminalResultReconciliationPolicy.shouldReconcile(
                channel = "release-candidate",
                phase = "FILL_DECK",
                activeGame = false,
                completeCurrentGameTerminal = true,
                resultAlreadyHandled = false,
            ),
        )
        assertFalse(
            BetaTerminalResultReconciliationPolicy.shouldReconcile(
                channel = "beta",
                phase = "FILL_DECK",
                activeGame = false,
                completeCurrentGameTerminal = false,
                resultAlreadyHandled = false,
            ),
        )
        assertFalse(
            BetaTerminalResultReconciliationPolicy.shouldReconcile(
                channel = "beta",
                phase = "FILL_DECK",
                activeGame = false,
                completeCurrentGameTerminal = true,
                resultAlreadyHandled = true,
            ),
        )
    }
}
