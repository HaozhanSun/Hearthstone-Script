package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.ActionDispatchGate
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ResultPageDismissalPolicy
import club.xiaojiawei.hsscript.status.ScreenStateRecovery
import club.xiaojiawei.hsscript.status.TerminalCleanupQueueResumePolicy
import club.xiaojiawei.hsscript.status.UpstreamScreenStateRecovery
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingInputGuardPolicy
import club.xiaojiawei.hsscript.utils.PowerLogUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
        PowerLogListener.resetTerminalInputFenceForTest()
    }

    @Test
    fun `only complete current game local outcome is terminal overlay authority`() {
        val wasReplaying = PowerLogListener.replayingExistingLog
        val oldLocalGameId = WarEx.war.me.gameId
        try {
            PowerLogListener.replayingExistingLog = false
            WarEx.war.me.gameId = "self#1"
            PowerLogListener.observeCurrentGameTerminalEvidenceLine("D 21:00:00.000 CREATE_GAME")
            PowerLogListener.observeCurrentGameTerminalEvidenceLine(
                "Player EntityID=1 PlayerID=1 GameAccountId=[hi=12 lo=34]",
            )
            PowerLogListener.observeCurrentGameTerminalEvidenceLine(
                "Player EntityID=2 PlayerID=2 GameAccountId=[hi=56 lo=78]",
            )
            PowerLogListener.observeCurrentGameTerminalEvidenceLine("PlayerID=1, PlayerName=self#1")
            PowerLogListener.observeCurrentGameTerminalEvidenceLine("PlayerID=2, PlayerName=opponent#2")
            PowerLogListener.bindVerifiedLocalMulliganEntity("self#1")
            PowerLogListener.observeCurrentGameTerminalEvidenceLine(
                "TAG_CHANGE Entity=self#1 tag=PLAYSTATE value=WON",
            )
            PowerLogListener.observeCurrentGameTerminalEvidenceLine(
                "TAG_CHANGE Entity=opponent#2 tag=PLAYSTATE value=CONCEDED",
            )
            PowerLogListener.observeCurrentGameTerminalEvidenceLine(
                "TAG_CHANGE Entity=GameEntity tag=STEP value=FINAL_GAMEOVER",
            )
            assertFalse(PowerLogListener.hasVerifiedCurrentGameTerminalOutcome(),
                "FINAL_GAMEOVER without the complete marker must not authorize recovery")
            PowerLogListener.observeCurrentGameTerminalEvidenceLine(
                "TAG_CHANGE Entity=GameEntity tag=STATE value=COMPLETE",
            )
            assertTrue(PowerLogListener.hasVerifiedCurrentGameTerminalOutcome())
        } finally {
            WarEx.war.me.gameId = oldLocalGameId
            PowerLogListener.replayingExistingLog = wasReplaying
        }
    }

    @Test
    fun `uncertain surrender blocks queue until terminal evidence then allows recheck`() {
        MandatoryRankSurrenderGuard.begin("test-game:self")
        MandatoryRankSurrenderGuard.markRecoveryUncertain()

        assertTrue(MandatoryRankSurrenderGuard.isPending())
        assertTrue(MandatoryRankSurrenderGuard.isRecoveryUncertain())
        assertFalse(MandatoryRankSurrenderGuard.confirmCompleted("UNKNOWN"))
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, true))
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, MandatoryRankSurrenderGuard.isPending()))

        assertFalse(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL"))
        assertFalse(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_TERMINAL"))
        assertTrue(MandatoryRankSurrenderGuard.isPending(), "Power.log terminal markers do not prove the UI left the board")
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, true))
        assertFalse(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED"))
        val cleanup = MandatoryRankSurrenderGuard.authorizeTerminalCleanup(terminalEvidence("test-game:self"))
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED", cleanup))
        assertFalse(MandatoryRankSurrenderGuard.isPending())
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, MandatoryRankSurrenderGuard.isPending()))
    }

    @Test
    fun `missing process plus complete terminal proof clears stale result dispatch fence`() {
        MandatoryRankSurrenderGuard.begin("test-game:self")
        val capability = MandatoryRankSurrenderGuard.authorizeTerminalCleanup(terminalEvidence("test-game:self"))
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", capability))
        assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "startup.missing-game-recovery",
                paused = false,
                working = true,
                terminalCleanupPending = MandatoryRankSurrenderGuard.isTerminalCleanupPending(),
            ),
            "the incomplete result cleanup initially blocks starter dispatch",
        )

        assertFalse(
            MandatoryRankSurrenderGuard.reconcileMissingProcessAfterTerminalProof(
                completeCurrentGameTerminalProof = false,
                gameProcessAlive = false,
            ),
        )
        assertFalse(
            MandatoryRankSurrenderGuard.reconcileMissingProcessAfterTerminalProof(
                completeCurrentGameTerminalProof = true,
                gameProcessAlive = true,
            ),
        )
        assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())

        assertFalse(ActionDispatchGate.reconcileMissingGameTerminalCleanupFence(
            gameAlive = true,
            activeGame = false,
            activeMatchmaking = false,
            authoritativeTerminalProof = true,
        ))
        assertFalse(ActionDispatchGate.reconcileMissingGameTerminalCleanupFence(
            gameAlive = true,
            activeGame = false,
            activeMatchmaking = true,
            authoritativeTerminalProof = true,
        ), "a live process in matchmaking must retain its own protection")
        assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        assertTrue(
            ActionDispatchGate.reconcileMissingGameTerminalCleanupFence(
                gameAlive = false,
                activeGame = true,
                activeMatchmaking = true,
                authoritativeTerminalProof = false,
            ),
            "the previously accepted cleanup capability survives an unbindable Power.log and stale process-owned latches",
        )
        assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        assertFalse(MandatoryRankSurrenderGuard.isPending())
        assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "startup.missing-game-recovery",
                paused = false,
                working = true,
                terminalCleanupPending = MandatoryRankSurrenderGuard.isTerminalCleanupPending(),
            ),
            "complete same-game terminal proof plus missing process releases the startup action fence",
        )
    }

    @Test
    fun `fresh deck selection completes terminal cleanup after a nonconceded game`() {
        val evidence = CurrentGameSurrenderTerminalEvidence(
            gameIdentity = "game-20261026-1611:laz#12793",
            ownEntityId = "laz#12793",
            opponentEntityId = "Perro#11555",
            ownPlayState = "LOST",
            opponentPlayState = "WON",
            finalGameOver = true,
            complete = true,
        )
        MandatoryRankSurrenderGuard.begin(evidence.gameIdentity)
        val capability = MandatoryRankSurrenderGuard.authorizeTerminalCleanup(evidence)
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", capability))
        assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        assertFalse(ActionDispatchGate.allowForState(
            action = "matchmaking.start",
            paused = false,
            working = true,
            currentSessionReady = true,
            terminalCleanupPending = MandatoryRankSurrenderGuard.isTerminalCleanupPending(),
        ), "the terminal cleanup fence remains until a fresh destination is proven")
        assertFalse(MandatoryRankSurrenderGuard.confirmAtFreshDeckSelection(
            screenKind = "DECK_SELECTION",
            confidence = 100,
            visualEvidence = "deck-selection-title",
            freshObservation = false,
        ), "stale deck chooser evidence cannot clear terminal cleanup")
        assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        assertTrue(MandatoryRankSurrenderGuard.confirmAtFreshDeckSelection(
            screenKind = "DECK_SELECTION",
            confidence = 100,
            visualEvidence = "deck-selection-title",
            freshObservation = true,
        ))
        assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        assertTrue(ActionDispatchGate.allowForState(
            action = "matchmaking.start",
            paused = false,
            working = true,
            currentSessionReady = true,
            freshDeckSelectionStartReady = true,
            terminalCleanupPending = MandatoryRankSurrenderGuard.isTerminalCleanupPending(),
        ), "fresh deck selection releases the old game's cleanup fence before the next queue")
    }

    @Test
    fun `fresh deck selection plus current terminal capability releases cleanup and next game still needs rank ticket`() {
        val wasWorking = WorkTimeListener.working
        val wasPaused = PauseStatus.isPause
        val oldPauseOrigin = PauseStatus.pauseOrigin
        try {
            WorkTimeListener.working = true
            PauseStatus.setManualPause(false)

            val surrenderedGameTicket = MulliganRankDispatchBarrier.beginCurrentGame()
            assertTrue(MulliganRankDispatchBarrier.requireSurrender(surrenderedGameTicket) != null)
            MandatoryRankSurrenderGuard.begin("test-game:self")
            assertEquals(
                MandatoryRankSurrenderDeckSelectionRecovery.Result.BLOCKED,
                MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
                    screenKind = "DECK_SELECTION",
                    confidence = 100,
                    visualEvidence = "deck-selection-title-roi",
                    freshObservation = true,
                ),
                "deck selection without this surrender's terminal capability must remain fenced",
            )
            assertTrue(
                ActionDispatchGate.allowForState(
                    action = "matchmaking.start",
                    paused = false,
                    working = true,
                    mandatoryRankSurrenderPending = MandatoryRankSurrenderGuard.isPending(),
                    rankBarrierState = MulliganRankDispatchBarrier.currentState(),
                    currentSessionReady = true,
                    freshDeckSelectionStartReady = true,
                ),
                "rank state must not block matchmaking once its screen/session permit is satisfied",
            )
            assertTrue(
                MandatoryRankSurrenderGuard.authorizeTerminalCleanup(terminalEvidence("test-game:self")) != null,
            )

            // A result page is still a terminal screen, while only a fresh
            // high-confidence deck-selection observation proves its dismissal.
            assertEquals(true, ScreenStateRecovery.resultVisibilityForTest("RESULT", 100))
            assertEquals(false, ScreenStateRecovery.resultVisibilityForTest("DECK_SELECTION", 100))
            assertEquals(
                MandatoryRankSurrenderDeckSelectionRecovery.Result.BLOCKED,
                MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
                    screenKind = "DECK_SELECTION",
                    confidence = 100,
                    visualEvidence = "deck-selection-title-roi",
                    freshObservation = false,
                ),
                "stale or unverified screen evidence cannot release either guard",
            )
            assertEquals(
                null,
                ScreenStateRecovery.resultVisibilityForTest("UNKNOWN", 100),
                "unknown capture remains inconclusive",
            )
            assertEquals(
                MandatoryRankSurrenderDeckSelectionRecovery.Result.BLOCKED,
                MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
                    screenKind = "UNKNOWN",
                    confidence = 100,
                    visualEvidence = "none",
                    freshObservation = true,
                ),
            )
            assertFalse(MandatoryRankSurrenderGuard.confirmCompleted("UNKNOWN"))
            assertTrue(MandatoryRankSurrenderGuard.isPending())
            assertEquals(
                MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED,
                MulliganRankDispatchBarrier.currentState(),
            )
            assertTrue(ActionDispatchGate.allow("startup.handoff"), "rank state must not block launch")
            assertTrue(ActionDispatchGate.allowForState(
                action = "matchmaking.start",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = MandatoryRankSurrenderGuard.isPending(),
                rankBarrierState = MulliganRankDispatchBarrier.currentState(),
                currentSessionReady = true,
                freshDeckSelectionStartReady = true,
            ), "rank state must not block matchmaking once its screen/session permit is satisfied")
            assertFalse(ActionDispatchGate.allowForState(
                action = "strategy.card.play",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = MandatoryRankSurrenderGuard.isPending(),
                rankBarrierState = MulliganRankDispatchBarrier.currentState(),
                currentSessionReady = true,
            ))

            assertEquals(
                MandatoryRankSurrenderDeckSelectionRecovery.Result.COMPLETED,
                MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
                    screenKind = "DECK_SELECTION",
                    confidence = 100,
                    visualEvidence = "deck-selection-title-roi",
                    freshObservation = true,
                ),
            )
            assertFalse(MandatoryRankSurrenderGuard.isPending())
            assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
            assertTrue(ActionDispatchGate.allow("startup.handoff"))
            assertTrue(ActionDispatchGate.allowForState(
                action = "matchmaking.start",
                paused = false,
                working = true,
                currentSessionReady = true,
                freshDeckSelectionStartReady = true,
            ))

            // CREATE_GAME for the next match creates a new mulligan ticket;
            // cleanup of the prior surrender does not make gameplay eligible.
            val nextGameTicket = MulliganRankDispatchBarrier.beginCurrentGame()
            assertTrue(nextGameTicket > surrenderedGameTicket)
            assertEquals(MulliganRankDispatchBarrier.State.PENDING, MulliganRankDispatchBarrier.currentState())
            assertFalse(ActionDispatchGate.allowForState(
                action = "strategy.card.play",
                paused = false,
                working = true,
                rankBarrierState = MulliganRankDispatchBarrier.currentState(),
                currentSessionReady = true,
            ))
            assertFalse(MulliganRankDispatchBarrier.authorizeEligibleRank(nextGameTicket, 7))
            assertTrue(MulliganRankDispatchBarrier.authorizeEligibleRank(nextGameTicket, 5))
            assertTrue(ActionDispatchGate.allowForState(
                action = "strategy.card.play",
                paused = false,
                working = true,
                rankBarrierState = MulliganRankDispatchBarrier.currentState(),
                currentSessionReady = true,
            ))
        } finally {
            WorkTimeListener.working = wasWorking
            when {
                !wasPaused -> PauseStatus.setManualPause(false)
                oldPauseOrigin == PauseStatus.Origin.AUTOMATIC -> PauseStatus.setAutomaticPause(true)
                else -> PauseStatus.setManualPause(true)
            }
        }
    }

    @Test
    fun `main menu alone cannot complete surrender recovery or permit requeue`() {
        MandatoryRankSurrenderGuard.begin()
        MandatoryRankSurrenderGuard.markRecoveryUncertain()
        assertFalse(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_MAIN_MENU"))
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
        val stale = MandatoryRankSurrenderGuard.begin("test-game:self")
        val active = MandatoryRankSurrenderGuard.begin("test-game:self")

        assertTrue(MandatoryRankSurrenderGuard.isRecoveryCapabilityValid(active))
        assertTrue(MandatoryRankSurrenderGuard.isRecoveryCapabilityValid(stale), "duplicate begin must preserve the original live token")
        val cleanup = MandatoryRankSurrenderGuard.authorizeTerminalCleanup(terminalEvidence("test-game:self"))
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_MAIN_MENU", cleanup))
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
        replayedLog.observeLine("CREATE_GAME", currentSessionEvidence = false)
        replayedLog.observeLine("tag=PLAYSTATE value=CONCEDED", currentSessionEvidence = false)
        replayedLog.observeLine("tag=STEP value=FINAL_GAMEOVER", currentSessionEvidence = false)
        replayedLog.observeLine(rawCompleteState, currentSessionEvidence = false)
        assertFalse(replayedLog.hasCompleteTerminalEvidence(), "existing-log replay must not authorize cleanup")
        // Nor may a stale tail after replay count until a fresh live CREATE_GAME
        // establishes the active game's boundary.
        replayedLog.observeLine(rawCompleteState)
        assertFalse(replayedLog.hasCompleteTerminalEvidence())
        replayedLog.observeLine("CREATE_GAME")
        replayedLog.observeLine("TAG_CHANGE Entity=me tag=PLAYSTATE value=CONCEDED")
        replayedLog.observeLine("tag=STEP value=FINAL_GAMEOVER")
        replayedLog.observeLine(rawCompleteState)
        assertTrue(replayedLog.hasCompleteTerminalEvidence())

        val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
        assertTrue(MulliganRankDispatchBarrier.requireSurrender(ticket) != null)
        val gameIdentity = terminalTracker.currentGameIdentity("me")
        MandatoryRankSurrenderGuard.begin(gameIdentity)

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
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, true))
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
        val cleanup = MandatoryRankSurrenderGuard.authorizeTerminalCleanup(
            terminalTracker.currentGameSurrenderEvidence("me", "opponent")?.copy(
                ownPlayState = "CONCEDED",
                opponentPlayState = "WON",
            ),
        )
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
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, true))
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
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, true))
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED"))
        assertFalse(MandatoryRankSurrenderGuard.isPending())
        assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, false))

        // Consecutive denied-rank match: the result handler must survive the
        // normal GAMEPLAY -> TOURNAMENT/deck-selection transition, observe the
        // now-cleared result screen, and release only this new match's barrier.
        val repeatedTicket = MulliganRankDispatchBarrier.beginCurrentGame()
        assertTrue(MulliganRankDispatchBarrier.requireSurrender(repeatedTicket) != null)
        MandatoryRankSurrenderGuard.begin("repeated:self")
        val repeatedTerminal = CurrentGamePowerLogTerminalTracker().apply {
            observeLine("CREATE_GAME")
            observeLine("TAG_CHANGE Entity=me tag=PLAYSTATE value=CONCEDED")
            observeLine("tag=STEP value=FINAL_GAMEOVER")
            observeLine("tag=STATE value=COMPLETE")
        }
        assertTrue(repeatedTerminal.hasCompleteTerminalEvidence())
        val repeatedCleanup = MandatoryRankSurrenderGuard.authorizeTerminalCleanup(terminalEvidence("repeated:self"))
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
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED", repeatedCleanup))
        assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, false))

        // A later CREATE_GAME clears old terminal evidence; it cannot release a
        // subsequent match's unresolved mandatory-surrender barrier.
        terminalTracker.observeLine("CREATE_GAME")
        assertFalse(terminalTracker.hasCompleteTerminalEvidence())
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, true))
        terminalTracker.observeLine("TAG_CHANGE Entity=me tag=PLAYSTATE value=WON")
        terminalTracker.observeLine("tag=STEP value=FINAL_GAMEOVER")
        terminalTracker.observeLine("CREATE_GAME")
        assertFalse(terminalTracker.hasCompleteTerminalEvidence(), "a previous game's terminal tail is stale")
        terminalTracker.observeLine("TAG_CHANGE Entity=me tag=PLAYSTATE value=CONCEDED")
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
            MandatoryRankSurrenderGuard.begin("first:self")
            val firstTerminal = CurrentGamePowerLogTerminalTracker().apply {
                observeLine("CREATE_GAME")
                observeLine("TAG_CHANGE Entity=me tag=PLAYSTATE value=CONCEDED")
                observeLine("tag=STEP value=FINAL_GAMEOVER")
                observeLine("tag=STATE value=COMPLETE")
            }
            assertTrue(firstTerminal.hasCompleteTerminalEvidence())
            val firstCleanupCapability =
                MandatoryRankSurrenderGuard.authorizeTerminalCleanup(terminalEvidence("first:self"))
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
            MandatoryRankSurrenderGuard.begin("second:self")
            assertFalse(
                MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(firstCleanupCapability),
            )
            val secondTerminal = CurrentGamePowerLogTerminalTracker().apply {
                observeLine("CREATE_GAME")
                observeLine("TAG_CHANGE Entity=me tag=PLAYSTATE value=CONCEDED")
                observeLine("tag=STEP value=FINAL_GAMEOVER")
                observeLine("tag=STATE value=COMPLETE")
            }
            assertTrue(secondTerminal.hasCompleteTerminalEvidence())
            val secondCleanupCapability =
                MandatoryRankSurrenderGuard.authorizeTerminalCleanup(terminalEvidence("second:self"))
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

    @Test
    fun `authoritative terminal deck selection reconciles cleanup before strategy queue input`() {
        val wasWorking = WorkTimeListener.working
        val wasPaused = PauseStatus.isPause
        val oldPauseOrigin = PauseStatus.pauseOrigin
        try {
            WorkTimeListener.working = true
            PauseStatus.setManualPause(false)

            val gameIdentity = "game-20261022-1638:laz#12793"
            val gameTicket = MulliganRankDispatchBarrier.beginCurrentGame()
            assertTrue(MulliganRankDispatchBarrier.requireSurrender(gameTicket) != null)
            MandatoryRankSurrenderGuard.begin(gameIdentity)
            val acceptedTerminalEvidence = CurrentGameSurrenderTerminalEvidence(
                gameIdentity = gameIdentity,
                ownEntityId = "laz#12793",
                opponentEntityId = "FwHLRB#3332",
                ownPlayState = "CONCEDED",
                opponentPlayState = "WON",
                finalGameOver = true,
                complete = true,
            )
            val terminalCapability =
                MandatoryRankSurrenderGuard.authorizeTerminalCleanup(acceptedTerminalEvidence)
            assertTrue(terminalCapability != null)
            assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", terminalCapability))
            assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
            assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
            assertFalse(ActionDispatchGate.allow("matchmaking.start"))

            // Stale/low-confidence screen observations and an old terminal
            // capability cannot release cleanup; retries remain rejected.
            assertEquals(
                MandatoryRankSurrenderDeckSelectionRecovery.Result.BLOCKED,
                MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
                    "DECK_SELECTION", 100, "deck-selection-title", freshObservation = false,
                ),
            )
            assertEquals(
                MandatoryRankSurrenderDeckSelectionRecovery.Result.BLOCKED,
                MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
                    "DECK_SELECTION", 84, "deck-selection-title", freshObservation = true,
                ),
            )
            assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
            assertFalse(ActionDispatchGate.allow("matchmaking.start"))

            // This matches the screenshot-backed screen-recovery observation:
            // a fresh high-confidence deck-selection title after GAMEOVER.
            assertEquals(
                MandatoryRankSurrenderDeckSelectionRecovery.Result.COMPLETED,
                UpstreamScreenStateRecovery.completeMandatoryRankSurrenderAtDeckSelection(
                    screenKind = "DECK_SELECTION",
                    confidence = 100,
                    visualEvidence = "deck-selection-title",
                    freshObservation = true,
                ),
            )
            assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
            assertTrue(ActionDispatchGate.allowForState(
                action = "matchmaking.start",
                paused = false,
                working = true,
                currentSessionReady = true,
                freshDeckSelectionStartReady = true,
            ))
            repeat(10) {
                assertTrue(ActionDispatchGate.allowForState(
                    action = "matchmaking.start",
                    paused = false,
                    working = true,
                    currentSessionReady = true,
                    freshDeckSelectionStartReady = true,
                ), "no stale cleanup block or repeated blocked-action spam")
            }

            // A consecutive next match starts a new terminal cycle with a
            // fresh game identity, while the first match's proof is stale.
            val nextTicket = MulliganRankDispatchBarrier.beginCurrentGame()
            assertTrue(MulliganRankDispatchBarrier.requireSurrender(nextTicket) != null)
            val nextGameIdentity = "game-20261022-1641:laz#12793"
            MandatoryRankSurrenderGuard.begin(nextGameIdentity)
            assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(terminalCapability))
            assertNull(MandatoryRankSurrenderGuard.authorizeTerminalCleanup(acceptedTerminalEvidence))
            val secondTerminalEvidence = CurrentGameSurrenderTerminalEvidence(
                gameIdentity = nextGameIdentity,
                ownEntityId = "laz#12793",
                opponentEntityId = "new-opponent#1",
                ownPlayState = "CONCEDED",
                opponentPlayState = "WON",
                finalGameOver = true,
                complete = true,
            )
            val secondTerminalCapability =
                MandatoryRankSurrenderGuard.authorizeTerminalCleanup(secondTerminalEvidence)
            assertTrue(secondTerminalCapability != null)
            assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", secondTerminalCapability))
            assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
            assertFalse(ActionDispatchGate.allow("matchmaking.start"))
            assertFalse(
                TerminalCleanupQueueResumePolicy.shouldResume(
                    destinationScreen = "DECK_SELECTION",
                    destinationConfirmed = true,
                    working = true,
                    paused = false,
                    inWar = false,
                    mandatoryRankSurrenderPending = MandatoryRankSurrenderGuard.isPending(),
                    terminalCleanupPending = MandatoryRankSurrenderGuard.isTerminalCleanupPending(),
                    deckStrategyReady = true,
                ),
                "the second cycle cannot queue while its terminal cleanup fence is active",
            )
            assertEquals(
                MandatoryRankSurrenderDeckSelectionRecovery.Result.BLOCKED,
                MandatoryRankSurrenderDeckSelectionRecovery.completeIfRequired(
                    "DECK_SELECTION", 100, "deck-selection-title", freshObservation = false,
                ),
                "stale second-cycle deck evidence is rejected",
            )
            assertEquals(
                MandatoryRankSurrenderDeckSelectionRecovery.Result.COMPLETED,
                UpstreamScreenStateRecovery.completeMandatoryRankSurrenderAtDeckSelection(
                    screenKind = "DECK_SELECTION",
                    confidence = 100,
                    visualEvidence = "deck-selection-title",
                    freshObservation = true,
                ),
            )
            assertTrue(
                TerminalCleanupQueueResumePolicy.shouldResume(
                    destinationScreen = "DECK_SELECTION",
                    destinationConfirmed = true,
                    working = true,
                    paused = false,
                    inWar = false,
                    mandatoryRankSurrenderPending = MandatoryRankSurrenderGuard.isPending(),
                    terminalCleanupPending = MandatoryRankSurrenderGuard.isTerminalCleanupPending(),
                    deckStrategyReady = true,
                ),
                "a fresh second-cycle deck screen schedules the normal queue flow again",
            )
            assertTrue(ActionDispatchGate.allowForState(
                action = "matchmaking.start",
                paused = false,
                working = true,
                currentSessionReady = true,
                freshDeckSelectionStartReady = true,
            ))
            repeat(10) {
                assertTrue(ActionDispatchGate.allowForState(
                    action = "matchmaking.start",
                    paused = false,
                    working = true,
                    currentSessionReady = true,
                    freshDeckSelectionStartReady = true,
                ), "the second cleanup cycle does not leave stale blocked dispatch")
            }
        } finally {
            WorkTimeListener.working = wasWorking
            when {
                !wasPaused -> PauseStatus.setManualPause(false)
                oldPauseOrigin == PauseStatus.Origin.AUTOMATIC -> PauseStatus.setAutomaticPause(true)
                else -> PauseStatus.setManualPause(true)
            }
        }
    }

    @Test
    fun `fresh deck selection releases terminal input fence and preserves post-mulligan rank preflight`() {
        PowerLogListener.observeCurrentGameTerminalEvidenceLine("D 08:47:05.772 CREATE_GAME")
        PowerLogListener.observeCurrentGameTerminalEvidenceLine(
            "TAG_CHANGE Entity=laz#12793 tag=PLAYSTATE value=CONCEDED",
        )
        PowerLogListener.observeCurrentGameTerminalEvidenceLine(
            "TAG_CHANGE Entity=GameEntity tag=STEP value=FINAL_GAMEOVER",
        )
        PowerLogListener.observeCurrentGameTerminalEvidenceLine(
            "TAG_CHANGE Entity=GameEntity tag=STATE value=COMPLETE",
        )
        assertTrue(PowerLogListener.hasCurrentGameCompleteTerminalPowerLogEvidence())
        assertTrue(PowerLogListener.hasCurrentGameTerminalInputFenceForTest(channel = "beta"))

        val gameIdentity = "terminal-fence-test:laz#12793"
        val gameTicket = MulliganRankDispatchBarrier.beginCurrentGame()
        assertTrue(MulliganRankDispatchBarrier.requireSurrender(gameTicket) != null)
        MandatoryRankSurrenderGuard.begin(gameIdentity)
        val terminalEvidence = CurrentGameSurrenderTerminalEvidence(
            gameIdentity = gameIdentity,
            ownEntityId = "laz#12793",
            opponentEntityId = "FwHLRB#3332",
            ownPlayState = "CONCEDED",
            opponentPlayState = "WON",
            finalGameOver = true,
            complete = true,
        )
        val capability = MandatoryRankSurrenderGuard.authorizeTerminalCleanup(terminalEvidence)
        assertTrue(capability != null)
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", capability))

        assertFalse(
            PowerLogListener.confirmCurrentGameTerminalDestinationForTest(
                screenKind = "DECK_SELECTION",
                confidence = 100,
                freshCaptureAuthorized = false,
                channel = "beta",
            ),
            "stale screen evidence must not release the terminal input fence",
        )
        assertFalse(
            PowerLogListener.confirmCurrentGameTerminalDestinationForTest(
                screenKind = "UNKNOWN",
                confidence = 100,
                freshCaptureAuthorized = true,
                channel = "beta",
            ),
            "unknown screens must not release the terminal input fence",
        )
        assertTrue(
            PowerLogListener.confirmCurrentGameTerminalDestinationForTest(
                screenKind = "DECK_SELECTION",
                confidence = 100,
                freshCaptureAuthorized = true,
                channel = "release-candidate",
            ),
            "non-Beta channels preserve their prior no-op behavior",
        )
        assertTrue(PowerLogListener.hasCurrentGameTerminalInputFenceForTest(channel = "beta"))
        assertEquals(
            MatchmakingInputGuardPolicy.Decision.ABORT_FOR_TERMINAL_CLEANUP,
            MatchmakingInputGuardPolicy.decide(
                observedScreen = "UNKNOWN",
                recentActiveSearchEvidence = false,
                terminalCleanupPending = PowerLogListener.hasCurrentGameTerminalInputFenceForTest(channel = "beta"),
            ),
        )

        assertTrue(
            MandatoryRankSurrenderGuard.confirmAtFreshDeckSelection(
                screenKind = "DECK_SELECTION",
                confidence = 100,
                visualEvidence = "deck-selection-title",
                freshObservation = true,
            ),
        )
        assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        assertTrue(
            PowerLogListener.confirmCurrentGameTerminalDestinationForTest(
                screenKind = "DECK_SELECTION",
                confidence = 100,
                freshCaptureAuthorized = true,
                channel = "beta",
            ),
        )
        assertFalse(PowerLogListener.hasCurrentGameTerminalInputFenceForTest(channel = "beta"))
        assertTrue(PowerLogListener.hasCurrentGameCompleteTerminalPowerLogEvidence())
        assertEquals(
            MatchmakingInputGuardPolicy.Decision.ALLOW_INPUT,
            MatchmakingInputGuardPolicy.decide(
                observedScreen = "DECK_SELECTION",
                recentActiveSearchEvidence = false,
                terminalCleanupPending = PowerLogListener.hasCurrentGameTerminalInputFenceForTest(channel = "beta"),
            ),
        )

        MulliganRankDispatchBarrier.beginCurrentGame()
        assertEquals(MulliganRankDispatchBarrier.State.PENDING, MulliganRankDispatchBarrier.currentState())
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "matchmaking.start",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = false,
                terminalCleanupPending = false,
                rankBarrierState = MulliganRankDispatchBarrier.currentState(),
                currentSessionReady = true,
                freshDeckSelectionStartReady = true,
            ),
            "rank preflight remains post-mulligan and does not re-block the queue start",
        )
    }

    private fun terminalEvidence(gameIdentity: String) = CurrentGameSurrenderTerminalEvidence(
        gameIdentity = gameIdentity,
        ownEntityId = "self",
        opponentEntityId = "opponent",
        ownPlayState = "CONCEDED",
        opponentPlayState = "WON",
        finalGameOver = true,
        complete = true,
    )
}
