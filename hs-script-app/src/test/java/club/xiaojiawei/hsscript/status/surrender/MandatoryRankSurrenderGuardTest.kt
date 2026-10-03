package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
import club.xiaojiawei.hsscript.status.ActionDispatchGate
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ResultPageDismissalPolicy
import club.xiaojiawei.hsscript.utils.PowerLogUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import club.xiaojiawei.hsscript.utils.GameEndTaskRegistry
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

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

        assertFalse(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL"))
        assertFalse(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_TERMINAL"))
        assertTrue(MandatoryRankSurrenderGuard.isPending(), "Power.log terminal markers do not prove the UI left the board")
        assertFalse(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, true))
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED"))
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
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_MAIN_MENU"))
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

        // Power.log can already say CONCEDED/FINAL_GAMEOVER/COMPLETE while
        // the screenshot is still the board. UNKNOWN must neither count as a
        // transition nor authorize a speculative result click.
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 1,
                maxAttempts = 5,
            ),
        )
        assertFalse(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL"))
        assertTrue(MandatoryRankSurrenderGuard.isPending())

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
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 1,
                maxAttempts = 5,
            ),
        )
        assertTrue(MandatoryRankSurrenderGuard.isPending(), "a dispatched click is not an accepted transition")
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 2,
                maxAttempts = 5,
            ),
        )
        assertTrue(MandatoryRankSurrenderGuard.isPending(), "board/UNKNOWN postcheck remains fail-closed")
        assertFalse(MandatoryRankSurrenderGuard.confirmCompleted("UNKNOWN"))
        assertTrue(MandatoryRankSurrenderGuard.isPending(), "an inconclusive screen postcheck must not unlock queue")
        assertFalse(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, true))
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED"))
        assertFalse(MandatoryRankSurrenderGuard.isPending())
        assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, false))

        // Consecutive denied-rank match: the result handler must survive the
        // normal GAMEPLAY -> TOURNAMENT/deck-selection transition, observe the
        // now-cleared result screen, and release only this new match's barrier.
        val repeatedTicket = MulliganRankDispatchBarrier.beginCurrentGame()
        assertTrue(MulliganRankDispatchBarrier.requireSurrender(repeatedTicket) != null)
        MandatoryRankSurrenderGuard.begin()
        val repeatedTerminal = CurrentGamePowerLogTerminalTracker().apply {
            observeLine("CREATE_GAME")
            observeLine("tag=PLAYSTATE value=CONCEDED")
            observeLine("tag=STEP value=FINAL_GAMEOVER")
            observeLine("tag=STATE value=COMPLETE")
        }
        assertTrue(repeatedTerminal.hasCompleteTerminalEvidence())
        val repeatedCleanup = MandatoryRankSurrenderGuard.authorizeTerminalCleanup("POWERLOG_TERMINAL")
        assertTrue(repeatedCleanup != null)
        assertFalse(
            ResultPageDismissalPolicy.shouldStopWorker(
                paused = false,
                gameplayMode = false,
                terminalCleanupCapabilityValid =
                    MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(repeatedCleanup),
            ),
            "a valid terminal cleanup must survive the tournament-mode transition",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 1,
                maxAttempts = 5,
            ),
            "the fresh deck-selection observation confirms the result page is gone",
        )
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED"))
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

    @Test
    fun `late surrender retry cancellation cannot cancel terminal cleanup across consecutive matches`() {
        val scheduler = ScheduledThreadPoolExecutor(1)
        val tasks = GameEndTaskRegistry()
        try {
            fun pendingTask() = scheduler.scheduleWithFixedDelay({}, 1, 1, TimeUnit.MINUTES)

            val firstTicket = MulliganRankDispatchBarrier.beginCurrentGame()
            assertTrue(MulliganRankDispatchBarrier.requireSurrender(firstTicket) != null)
            MandatoryRankSurrenderGuard.begin()
            val firstTerminal = CurrentGamePowerLogTerminalTracker().apply {
                observeLine("CREATE_GAME")
                observeLine("tag=PLAYSTATE value=CONCEDED")
                observeLine("tag=STEP value=FINAL_GAMEOVER")
                observeLine("tag=STATE value=COMPLETE")
            }
            assertTrue(firstTerminal.hasCompleteTerminalEvidence())
            val firstCleanupCapability =
                MandatoryRankSurrenderGuard.authorizeTerminalCleanup("POWERLOG_TERMINAL")
            assertTrue(firstCleanupCapability != null)

            val firstSurrenderRetry = pendingTask()
            val firstResultCleanup = pendingTask()
            tasks.add(firstSurrenderRetry)
            tasks.add(firstResultCleanup)

            // A SendInput/Robot acceptance only proves dispatch. The result
            // remains visible, so the barrier must stay closed after the click.
            var acceptedClicks = 0
            assertEquals(
                ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
                ResultPageDismissalPolicy.decide(
                    inWar = false,
                    resultPageVisible = true,
                    attempt = 1,
                    maxAttempts = 5,
                ),
            )
            acceptedClicks++ // SendInput accepted; that is not UI/postcheck confirmation.
            assertEquals(1, acceptedClicks)
            assertTrue(MandatoryRankSurrenderGuard.isPending())

            // A late surrender-retry callback sees the terminal marker and
            // stops only itself. It must not cancel the result worker before
            // that worker can observe the new deck-selection screen.
            tasks.cancel(firstSurrenderRetry)
            assertTrue(firstSurrenderRetry.isCancelled)
            assertFalse(firstResultCleanup.isCancelled)
            assertTrue(
                MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(firstCleanupCapability),
            )
            assertFalse(
                ResultPageDismissalPolicy.shouldStopWorker(
                    paused = false,
                    gameplayMode = false,
                    terminalCleanupCapabilityValid =
                        MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(firstCleanupCapability),
                ),
            )
            assertEquals(
                ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
                ResultPageDismissalPolicy.decide(
                    inWar = false,
                    resultPageVisible = false,
                    attempt = 2,
                    maxAttempts = 5,
                ),
            )
            assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED"))
            assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
            tasks.cancel(firstResultCleanup)

            // Consecutive match: stale capability from the first game is
            // invalid; the second game gets a fresh barrier and cleanup token.
            val secondTicket = MulliganRankDispatchBarrier.beginCurrentGame()
            assertTrue(MulliganRankDispatchBarrier.requireSurrender(secondTicket) != null)
            MandatoryRankSurrenderGuard.begin()
            assertFalse(
                MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(firstCleanupCapability),
            )
            val secondTerminal = CurrentGamePowerLogTerminalTracker().apply {
                observeLine("CREATE_GAME")
                observeLine("tag=PLAYSTATE value=CONCEDED")
                observeLine("tag=STEP value=FINAL_GAMEOVER")
                observeLine("tag=STATE value=COMPLETE")
            }
            assertTrue(secondTerminal.hasCompleteTerminalEvidence())
            val secondCleanupCapability =
                MandatoryRankSurrenderGuard.authorizeTerminalCleanup("POWERLOG_TERMINAL")
            assertTrue(secondCleanupCapability != null)

            val secondSurrenderRetry = pendingTask()
            val secondResultCleanup = pendingTask()
            tasks.add(secondSurrenderRetry)
            tasks.add(secondResultCleanup)
            tasks.cancel(secondSurrenderRetry)

            assertTrue(secondResultCleanup.isCancelled.not())
            assertTrue(
                MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(secondCleanupCapability),
            )
            assertTrue(MandatoryRankSurrenderGuard.isPending(), "accepted cleanup input is not completion")
            assertEquals(
                ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
                ResultPageDismissalPolicy.decide(
                    inWar = false,
                    resultPageVisible = false,
                    attempt = 2,
                    maxAttempts = 5,
                ),
            )
            assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED"))
            assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
            tasks.cancel(secondResultCleanup)
        } finally {
            tasks.cancelAll()
            scheduler.shutdownNow()
        }
    }
}
