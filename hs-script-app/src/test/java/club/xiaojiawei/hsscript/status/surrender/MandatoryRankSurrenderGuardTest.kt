package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ActionDispatchGate
import club.xiaojiawei.hsscript.utils.PowerLogUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MandatoryRankSurrenderGuardTest {
    @AfterEach
    fun cleanup() {
        MandatoryRankSurrenderGuard.resetForTest()
        MulliganRankDispatchBarrier.resetForTest()
    }

    @Test
    fun `uncertain surrender blocks queue until terminal evidence then allows recheck`() {
        MandatoryRankSurrenderGuard.begin()
        MandatoryRankSurrenderGuard.markRecoveryUncertain()

        assertTrue(MandatoryRankSurrenderGuard.isPending())
        assertTrue(MandatoryRankSurrenderGuard.isRecoveryUncertain())
        assertFalse(MandatoryRankSurrenderGuard.confirmCompleted("UNKNOWN"))
        assertFalse(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, true))
        assertFalse(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, MandatoryRankSurrenderGuard.isPending()))

        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL"))
        assertFalse(MandatoryRankSurrenderGuard.isPending())
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, MandatoryRankSurrenderGuard.isPending()))
    }

    @Test
    fun `confirmed main menu screen completes surrender recovery and permits requeue`() {
        MandatoryRankSurrenderGuard.begin()
        MandatoryRankSurrenderGuard.markRecoveryUncertain()
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_MAIN_MENU"))
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, MandatoryRankSurrenderGuard.isPending()))
    }

    @Test
    fun `uncertain mandatory surrender retains retry state without auto-pausing`() {
        PauseStatus.setManualPause(false)
        try {
            MandatoryRankSurrenderGuard.begin()
            MandatoryRankSurrenderGuard.markRecoveryUncertain()

            assertTrue(MandatoryRankSurrenderGuard.isPending())
            assertTrue(MandatoryRankSurrenderGuard.isRecoveryUncertain())
            assertFalse(PauseStatus.isPause)
            assertTrue(
                MandatoryRankSurrenderRecoveryPolicy.shouldWaitForMoreEvidence(
                    mandatoryRank = true,
                    screenConfirmed = false,
                ),
            )
        } finally {
            PauseStatus.setManualPause(true)
        }
    }

    @Test
    fun `only the active surrender capability authorizes gated recovery input`() {
        val active = MandatoryRankSurrenderGuard.begin()
        val forged = MandatoryRankSurrenderGuard.RecoveryCapability()

        assertTrue(MandatoryRankSurrenderGuard.isRecoveryCapabilityValid(active))
        assertFalse(MandatoryRankSurrenderGuard.isRecoveryCapabilityValid(forged))
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL"))
        assertFalse(MandatoryRankSurrenderGuard.isRecoveryCapabilityValid(active))
    }

    @Test
    fun `current game terminal evidence enables only postchecked cleanup then releases that match barrier`() {
        val terminalTracker = CurrentGamePowerLogTerminalTracker()
        terminalTracker.observeLine("CREATE_GAME")
        terminalTracker.observeLine("GameState.DebugPrintPower() TAG_CHANGE Entity=me tag=PLAYSTATE value=CONCEDED")
        terminalTracker.observeLine("GameState.DebugPrintPower() TAG_CHANGE Entity=GameEntity tag=STEP value=FINAL_GAMEOVER")
        assertFalse(terminalTracker.hasCompleteTerminalEvidence(), "STATE=COMPLETE is required")
        // This raw GameState line is terminal evidence even when the ordinary
        // phase parser's relevance filter would omit it.
        val rawCompleteState =
            "GameState.DebugPrintPower() TAG_CHANGE Entity=GameEntity tag=STATE value=COMPLETE"
        assertFalse(PowerLogUtil.isRelevance(rawCompleteState), "STATE=COMPLETE is filtered from model parsing")
        terminalTracker.observeLine(rawCompleteState)
        assertTrue(terminalTracker.hasCompleteTerminalEvidence())

        val opponentOnlyWin = CurrentGamePowerLogTerminalTracker()
        opponentOnlyWin.observeLine("CREATE_GAME")
        opponentOnlyWin.observeLine("TAG_CHANGE Entity=Opponent#1 tag=PLAYSTATE value=WON")
        opponentOnlyWin.observeLine("GameState.DebugPrintPower() TAG_CHANGE Entity=GameEntity tag=STEP value=FINAL_GAMEOVER")
        opponentOnlyWin.observeLine("GameState.DebugPrintPower() TAG_CHANGE Entity=GameEntity tag=STATE value=COMPLETE")
        assertTrue(opponentOnlyWin.hasCompleteTerminalEvidence(), "opponent WON still proves the match is terminal")

        val replayedLog = CurrentGamePowerLogTerminalTracker()
        replayedLog.observeLine("CREATE_GAME", liveAttachedSession = false)
        replayedLog.observeLine("tag=PLAYSTATE value=CONCEDED", liveAttachedSession = false)
        replayedLog.observeLine("tag=STEP value=FINAL_GAMEOVER", liveAttachedSession = false)
        replayedLog.observeLine(rawCompleteState, liveAttachedSession = false)
        assertFalse(replayedLog.hasCompleteTerminalEvidence(), "existing-log replay must not authorize cleanup")
        // Nor may a stale tail after replay count until a fresh live CREATE_GAME
        // establishes the active game's boundary.
        replayedLog.observeLine(rawCompleteState)
        assertFalse(replayedLog.hasCompleteTerminalEvidence())
        replayedLog.observeLine("CREATE_GAME")
        replayedLog.observeLine("tag=PLAYSTATE value=CONCEDED")
        replayedLog.observeLine("tag=STEP value=FINAL_GAMEOVER")
        replayedLog.observeLine(rawCompleteState)
        assertTrue(replayedLog.hasCompleteTerminalEvidence())

        val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
        assertTrue(MulliganRankDispatchBarrier.requireSurrender(ticket) != null)
        MandatoryRankSurrenderGuard.begin()

        assertTrue(MandatoryRankSurrenderGuard.isPending())
        assertFalse(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, true))
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "strategy.card.play",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
                rankBarrierState = MulliganRankDispatchBarrier.currentState(),
            ),
        )

        // A current-match Power.log marker authorizes only the result cleanup;
        // requeue remains blocked until a fresh postcheck confirms the result is gone.
        val cleanup = MandatoryRankSurrenderGuard.authorizeTerminalCleanup("POWERLOG_TERMINAL")
        assertTrue(cleanup != null)
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "terminal-result.dismiss",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
                terminalCleanupCapabilityValid =
                    MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(cleanup),
                rankBarrierState = MulliganRankDispatchBarrier.currentState(),
            ),
        )
        assertFalse(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, true))
        assertFalse(MandatoryRankSurrenderGuard.confirmCompleted("UNKNOWN"))
        assertTrue(MandatoryRankSurrenderGuard.isPending(), "an inconclusive screen postcheck must not unlock queue")
        assertFalse(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, true))
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED"))
        assertFalse(MandatoryRankSurrenderGuard.isPending())
        assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, false))

        // A later CREATE_GAME clears old terminal evidence; it cannot release a
        // subsequent match's unresolved mandatory-surrender barrier.
        terminalTracker.observeLine("CREATE_GAME")
        assertFalse(terminalTracker.hasCompleteTerminalEvidence())
        assertFalse(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, true))
        terminalTracker.observeLine("tag=PLAYSTATE value=WON")
        terminalTracker.observeLine("tag=STEP value=FINAL_GAMEOVER")
        terminalTracker.observeLine("CREATE_GAME")
        assertFalse(terminalTracker.hasCompleteTerminalEvidence(), "a previous game's terminal tail is stale")
        terminalTracker.observeLine("tag=PLAYSTATE value=CONCEDED")
        assertFalse(terminalTracker.hasCompleteTerminalEvidence(), "a terminal tag without final state is incomplete")
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "terminal-result.dismiss",
                paused = true,
                working = true,
                mandatoryRankSurrenderPending = true,
                terminalCleanupCapabilityValid = true,
                rankBarrierState = MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED,
            ),
            "F2 pause must remain higher priority than terminal cleanup",
        )
    }
}
