package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.GameStartupModeEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.starter.GameStartupModeSequencePolicy
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
import club.xiaojiawei.hsscript.status.surrender.CurrentGameSurrenderTerminalEvidence
import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import club.xiaojiawei.hsscript.starter.GameStartupHandoffPolicy
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActionDispatchGateTest {

    @Test
    fun `missing process releases accepted terminal cleanup but retains live or unproven state`() {
        MandatoryRankSurrenderGuard.resetForTest()
        try {
            MandatoryRankSurrenderGuard.begin("test-game:self")
            assertFalse(
                ActionDispatchGate.reconcileMissingGameTerminalCleanupFence(false, false, false, false),
                "an unproven game cannot release the rank barrier",
            )
            val capability = MandatoryRankSurrenderGuard.authorizeTerminalCleanup(testTerminalEvidence())
            assertTrue(capability != null)
            assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", capability))
            assertFalse(
                ActionDispatchGate.reconcileMissingGameTerminalCleanupFence(true, true, false, false),
                "live game remains fenced",
            )
            assertTrue(ActionDispatchGate.reconcileMissingGameTerminalCleanupFence(false, false, false, true))
            assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        } finally {
            MandatoryRankSurrenderGuard.resetForTest()
            MulliganRankDispatchBarrier.resetForTest()
        }
    }

    @Test
    fun `startup preflight releases a missing process fence only after accepted terminal proof`() {
        MandatoryRankSurrenderGuard.resetForTest()
        try {
            MandatoryRankSurrenderGuard.begin("test-game:self")
            assertFalse(ActionDispatchGate.reconcileStartupMissingGameTerminalCleanupFence(false, false))
            assertTrue(MandatoryRankSurrenderGuard.isPending())

            val capability = MandatoryRankSurrenderGuard.authorizeTerminalCleanup(testTerminalEvidence())
            assertTrue(capability != null)
            assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", capability))
            assertFalse(ActionDispatchGate.reconcileStartupMissingGameTerminalCleanupFence(true, true))
            assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
            assertTrue(ActionDispatchGate.reconcileStartupMissingGameTerminalCleanupFence(false, true))
            assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
        } finally {
            MandatoryRankSurrenderGuard.resetForTest()
            MulliganRankDispatchBarrier.resetForTest()
        }
    }

    @Test
    fun `startup handoff dispatch is blocked by manual or automatic pause`() {
        assertFalse(ActionDispatchGate.allowForState("startup.handoff", paused = true, working = true))
        assertFalse(ActionDispatchGate.allowForState("startup.handoff", paused = true, working = false))
    }

    @Test
    fun `startup handoff dispatch is blocked when work is not active`() {
        assertFalse(ActionDispatchGate.allowForState("startup.handoff", paused = false, working = false))
    }

    @Test
    fun `explicit pause gates every configured platform startup mode`() {
        GameStartupModeEnum.values().forEach { mode ->
            val selected = GameStartupModeSequencePolicy.select(
                configuredModes = listOf(mode),
                attemptIndex = 0,
                launcherWindowAvailable = true,
            )
            assertEquals(mode, selected.mode)
            assertFalse(
                ActionDispatchGate.allowForState("startup.handoff.${selected.mode.name}", paused = true, working = true),
            )
        }
    }

    @Test
    fun `startup handoff dispatch is allowed only while unpaused and working`() {
        assertTrue(ActionDispatchGate.allowForState("startup.handoff", paused = false, working = true))
    }

    @Test
    fun `login hub tournament mulligan and strategy input wait for fresh online startup proof`() {
        val modeActions = listOf(
            "mouse.left",                 // LoginModeStrategy confirm
            "mouse.left",                 // HubModeStrategy mode selection
            "matchmaking.start",          // TournamentModeStrategy queue/start path
            "matchmaking.select-deck",
            "mulligan.robot",
            "strategy.card.play",
            "mouse.move",
        )
        modeActions.forEach { action ->
            assertFalse(
                ActionDispatchGate.allowForState(
                    action = action,
                    paused = false,
                    working = true,
                    currentSessionReady = false,
                    freshOnlineMenuReady = false,
                ),
                "$action must remain blocked while the current session is empty and no online menu was verified",
            )
        }
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "recovery.reconnect",
                paused = false,
                working = true,
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                preSessionReconnectPermitValid = false,
            ),
        )
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "recovery.reconnect",
                paused = false,
                working = true,
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                preSessionReconnectPermitValid = true,
            ),
        )

        modeActions.forEach { action ->
            assertTrue(
                ActionDispatchGate.allowForState(
                    action = action,
                    paused = false,
                    working = true,
                    currentSessionReady = false,
                    freshOnlineMenuReady = true,
                ),
                "$action resumes after a fresh, clean online-menu observation",
            )
        }
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "strategy.card.play",
                paused = false,
                working = true,
                currentSessionReady = true,
            ),
            "a ready current-session log restores ordinary policy, including fresh rank preflight",
        )
    }

    @Test
    fun `rank preflight barrier blocks queued game actions until eligible rank is verified`() {
        listOf("mulligan.confirm", "strategy.card.play", "strategy.turn-end", "surrender.request")
            .forEach { action ->
                assertFalse(
                    ActionDispatchGate.allowForState(
                        action = action,
                        paused = false,
                        working = true,
                        rankBarrierState = MulliganRankDispatchBarrier.State.PENDING,
                    ),
                    action,
                )
            }
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "mulligan.confirm",
                paused = false,
                working = true,
                rankBarrierState = MulliganRankDispatchBarrier.State.ELIGIBLE,
            ),
        )
        listOf("startup.handoff", "matchmaking.start", "queue.retry").forEach { action ->
            assertTrue(
                ActionDispatchGate.allowForState(
                    action = action,
                    paused = false,
                    working = true,
                    mandatoryRankSurrenderPending = true,
                    rankBarrierState = MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED,
                ),
                "$action is outside gameplay rank authorization",
            )
        }
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "startup.handoff",
                paused = true,
                working = true,
                rankBarrierState = MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED,
            ),
            "manual/F2 pause still blocks startup handoff",
        )
    }

    @Test
    fun `missing game clears stale rank state then fresh pre-mulligan decision fences gameplay`() {
        MandatoryRankSurrenderGuard.resetForTest()
        MulliganRankDispatchBarrier.resetForTest()
        val oldTicket = MulliganRankDispatchBarrier.beginCurrentGame()
        assertTrue(MulliganRankDispatchBarrier.requireSurrender(oldTicket) != null)
        MandatoryRankSurrenderGuard.begin("stale-game:self")

        // A missing game process is an accepted start condition. Rank state
        // from the previous process cannot block startup or the handoff.
        assertFalse(MandatoryRankSurrenderGuard.resetForNoGameProcess(gameProcessAlive = true))
        assertTrue(MandatoryRankSurrenderGuard.isPending(), "a live game keeps its current rank obligation")
        assertTrue(
            MandatoryRankSurrenderGuard.resetForNoGameProcess(gameProcessAlive = false),
        )
        assertFalse(MandatoryRankSurrenderGuard.isPending())
        assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
        assertTrue(ActionDispatchGate.allowForState("startup.handoff", paused = false, working = true))

        val firstObservation = GameStartupHandoffPolicy.observe(
            GameStartupHandoffPolicy.State(), processAlive = true, windowFound = true, nowMs = 10_000,
        )
        val confirmed = GameStartupHandoffPolicy.observe(
            firstObservation.state, processAlive = true, windowFound = true, nowMs = 10_100,
        )
        assertEquals(GameStartupHandoffPolicy.Decision.HANDOFF, confirmed.decision)

        // CREATE_GAME reaches the fresh mulligan boundary, where a new rank
        // check, and only that check, decides whether play or surrender is legal.
        val newTicket = MulliganRankDispatchBarrier.beginCurrentGame()
        assertTrue(newTicket > oldTicket)
        assertFalse(ActionDispatchGate.allowForState("strategy.card.play", paused = false, working = true,
            rankBarrierState = MulliganRankDispatchBarrier.State.PENDING))
        val surrenderCapability = MulliganRankDispatchBarrier.requireSurrender(newTicket)
        assertTrue(surrenderCapability != null)
        assertFalse(ActionDispatchGate.allowForState("strategy.card.play", paused = false, working = true,
            rankBarrierState = MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED))
        assertTrue(ActionDispatchGate.allowForState("surrender.request", paused = false, working = true,
            rankBarrierState = MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED,
            rankSurrenderRequestCapabilityValid = true))
        assertFalse(ActionDispatchGate.allowForState("surrender.request", paused = true, working = true,
            rankBarrierState = MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED,
            rankSurrenderRequestCapabilityValid = true), "F2 pause retains priority over rank surrender")
        MandatoryRankSurrenderGuard.resetForTest()
        MulliganRankDispatchBarrier.resetForTest()
    }

    @Test
    fun `surrender-required barrier only permits typed surrender recovery and terminal dismissal`() {
        val required = MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED
        assertFalse(
            ActionDispatchGate.allowForState(
                "strategy.card.play", false, true, rankBarrierState = required,
            ),
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                "surrender.request", false, true, rankBarrierState = required,
            ),
            "action text alone must not authorize the surrender request",
        )
        assertTrue(
            ActionDispatchGate.allowForState(
                "surrender.request", false, true,
                rankBarrierState = required,
                rankSurrenderRequestCapabilityValid = true,
            ),
        )
        assertTrue(
            ActionDispatchGate.allowForState(
                "surrender.request", false, true,
                mandatoryRankSurrenderPending = true,
                rankBarrierState = required,
                rankSurrenderRequestCapabilityValid = true,
            ),
            "the one-shot rank capability must not deadlock against its own pending recovery lock",
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                "surrender.request", true, true,
                mandatoryRankSurrenderPending = true,
                recoveryCapabilityValid = true,
                rankBarrierState = required,
                rankSurrenderRequestCapabilityValid = true,
            ),
            "manual F2 pause has priority over both surrender capabilities",
        )
        assertTrue(
            ActionDispatchGate.allowForState(
                "surrender.retry.confirm", false, true,
                mandatoryRankSurrenderPending = true,
                recoveryCapabilityValid = true,
                rankBarrierState = required,
            ),
        )
        assertTrue(
            ActionDispatchGate.allowForState(
                "terminal-result.dismiss", false, true,
                terminalCleanupCapabilityValid = true,
                rankBarrierState = required,
            ),
        )
    }

    @Test
    fun `mandatory rank surrender blocks ordinary game-changing dispatch without pausing`() {
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "mouse.left",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
            ),
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "mouse.right",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
            ),
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "surrender.request",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
            ),
        )
    }

    @Test
    fun `only an explicit recovery capability authorizes retry and recovery clicks`() {
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "surrender.retry.before-menu",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
                recoveryCapabilityValid = true,
            ),
        )
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "rank-surrender.mouse.left",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
                recoveryCapabilityValid = true,
            ),
        )
    }

    @Test
    fun `mandatory surrender recovery input still respects pause and stopped work`() {
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "rank-surrender.mouse.left",
                paused = true,
                working = true,
                mandatoryRankSurrenderPending = true,
                recoveryCapabilityValid = true,
            ),
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "rank-surrender.mouse.left",
                paused = false,
                working = false,
                mandatoryRankSurrenderPending = true,
                recoveryCapabilityValid = true,
            ),
        )
    }

    @Test
    fun `surrender action text alone does not grant mandatory recovery capability`() {
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "surrender.retry.before-menu",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
                recoveryCapabilityValid = false,
            ),
        )
    }

    @Test
    fun `fresh current game surrender capability passes both pending guards once`() {
        val wasWorking = WorkTimeListener.working
        val wasPaused = PauseStatus.isPause
        val oldPauseOrigin = PauseStatus.pauseOrigin
        MandatoryRankSurrenderGuard.resetForTest()
        MulliganRankDispatchBarrier.resetForTest()
        try {
            WorkTimeListener.working = true
            PauseStatus.setManualPause(false)
            MandatoryRankSurrenderGuard.begin()
            val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
            val capability = MulliganRankDispatchBarrier.requireSurrender(ticket)
            assertTrue(capability != null)
            assertTrue(MandatoryRankSurrenderGuard.isPending())

            assertTrue(
                ActionDispatchGate.allow("surrender.request", rankSurrenderCapability = capability),
                "the current game request must pass its own barrier and an already-pending recovery guard",
            )
            assertFalse(MulliganRankDispatchBarrier.isSurrenderCapabilityValid(capability))
            assertFalse(
                ActionDispatchGate.allow("surrender.request", rankSurrenderCapability = capability),
                "the capability is consumed by exactly one accepted request",
            )
            assertFalse(
                ActionDispatchGate.allowForState(
                    action = "mouse.left",
                    paused = false,
                    working = true,
                    mandatoryRankSurrenderPending = true,
                    rankBarrierState = MulliganRankDispatchBarrier.currentState(),
                ),
                "ordinary input remains blocked while mandatory surrender is unresolved",
            )

            val staleCapability = capability
            val nextTicket = MulliganRankDispatchBarrier.beginCurrentGame()
            val nextCapability = MulliganRankDispatchBarrier.requireSurrender(nextTicket)
            assertFalse(
                ActionDispatchGate.allow("surrender.request", rankSurrenderCapability = staleCapability),
                "a previous game's capability cannot authorize a new request",
            )
            assertTrue(ActionDispatchGate.allow("surrender.request", rankSurrenderCapability = nextCapability))
        } finally {
            MandatoryRankSurrenderGuard.resetForTest()
            MulliganRankDispatchBarrier.resetForTest()
            WorkTimeListener.working = wasWorking
            when {
                !wasPaused -> PauseStatus.setManualPause(false)
                oldPauseOrigin == PauseStatus.Origin.AUTOMATIC -> PauseStatus.setAutomaticPause(true)
                else -> PauseStatus.setManualPause(true)
            }
        }
    }

    @Test
    fun `authoritative terminal capability clears rank barrier but allows only result dismissal`() {
        MandatoryRankSurrenderGuard.resetForTest()
        try {
            MandatoryRankSurrenderGuard.begin("test-game:self")
            assertEquals(null, MandatoryRankSurrenderGuard.authorizeTerminalCleanup(null))
            val terminalCapability =
                MandatoryRankSurrenderGuard.authorizeTerminalCleanup(testTerminalEvidence())
            assertTrue(terminalCapability != null)
            assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(terminalCapability))
            assertTrue(MandatoryRankSurrenderGuard.isPending())
            assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", terminalCapability))
            assertFalse(MandatoryRankSurrenderGuard.isPending())
            assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
            assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(terminalCapability))
            assertTrue(
                ActionDispatchGate.allowForState(
                    action = "terminal-result.dismiss",
                    paused = false,
                    working = true,
                    terminalCleanupPending = true,
                    terminalCleanupCapabilityValid = true,
                ),
            )
            listOf(
                "recovery.left",
                "matchmaking.start",
                "startup.handoff",
                "strategy.card.play",
                "strategy.turn-end",
                "surrender.request",
            ).forEach { action ->
                assertFalse(
                    ActionDispatchGate.allowForState(
                        action = action,
                        paused = false,
                        working = true,
                        terminalCleanupPending = true,
                        terminalCleanupCapabilityValid = true,
                    ),
                    action,
                )
            }
            assertFalse(
                ActionDispatchGate.allowForState(
                    action = "terminal-result.dismiss",
                    paused = true,
                    working = true,
                    terminalCleanupPending = true,
                    terminalCleanupCapabilityValid = true,
                ),
            )
            assertTrue(
                MatchmakingGuardPolicy.runtimeAllowsInput(
                    working = true,
                    paused = false,
                    mandatoryRankSurrenderPending = true,
                ),
            )

            assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED", terminalCapability))
            assertFalse(MandatoryRankSurrenderGuard.isPending())
            assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
            assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(terminalCapability))
            // Releasing the cleanup lock does not bypass the ordinary rank
            // preflight, which will run again for the next match.
            assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, false))
        } finally {
            MandatoryRankSurrenderGuard.resetForTest()
        }
    }

    @Test
    fun `repeated terminal authorization keeps result dismissal valid until confirmed clear`() {
        val wasWorking = WorkTimeListener.working
        val wasPaused = PauseStatus.isPause
        val oldPauseOrigin = PauseStatus.pauseOrigin
        MandatoryRankSurrenderGuard.resetForTest()
        MulliganRankDispatchBarrier.resetForTest()
        try {
            WorkTimeListener.working = true
            PauseStatus.setManualPause(false)
            val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
            assertTrue(MulliganRankDispatchBarrier.requireSurrender(ticket) != null)
            MandatoryRankSurrenderGuard.begin("test-game:self")

            assertEquals(null, MandatoryRankSurrenderGuard.authorizeTerminalCleanup(null))
            assertFalse(
                ActionDispatchGate.allow("terminal-result.dismiss"),
                "without terminal evidence, neither guard may be bypassed",
            )
            assertFalse(ActionDispatchGate.allow("strategy.card.play"))
            assertTrue(MandatoryRankSurrenderGuard.isPending())
            assertEquals(
                MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED,
                MulliganRankDispatchBarrier.currentState(),
            )

            val powerLogCapability =
                MandatoryRankSurrenderGuard.authorizeTerminalCleanup(testTerminalEvidence())
            assertTrue(powerLogCapability != null)
            val screenCapability = MandatoryRankSurrenderGuard.existingTerminalCleanupCapability()
            assertSame(powerLogCapability, screenCapability)
            assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(powerLogCapability))
            assertTrue(
                ActionDispatchGate.allow(
                    "terminal-result.dismiss",
                    terminalCleanupCapability = powerLogCapability,
                ),
                "a repeated observer must not stale the capability held by the active cleanup worker",
            )
            assertFalse(ActionDispatchGate.allow("strategy.card.play"))
            assertFalse(ActionDispatchGate.allow("mouse.left"))
            assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL", powerLogCapability))
            assertFalse(MandatoryRankSurrenderGuard.isPending())
            assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
            assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
            assertFalse(ActionDispatchGate.allow("strategy.card.play"))

            assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED", powerLogCapability))
            assertFalse(MandatoryRankSurrenderGuard.isPending())
            assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupPending())
            assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
            assertFalse(
                ActionDispatchGate.allow("matchmaking.start"),
                "terminal proof clears rank fences but cannot replace current-session or fresh-menu readiness",
            )
            assertTrue(
                ActionDispatchGate.allowForState(
                    "matchmaking.start",
                    paused = false,
                    working = true,
                    currentSessionReady = true,
                ),
                "a ready current-session log restores queue dispatch",
            )
            assertFalse(PauseStatus.isPause, "terminal proof releases rank fencing without auto-pausing")
        } finally {
            MandatoryRankSurrenderGuard.resetForTest()
            MulliganRankDispatchBarrier.resetForTest()
            WorkTimeListener.working = wasWorking
            when {
                !wasPaused -> PauseStatus.setManualPause(false)
                oldPauseOrigin == PauseStatus.Origin.AUTOMATIC -> PauseStatus.setAutomaticPause(true)
                else -> PauseStatus.setManualPause(true)
            }
        }
    }

    private fun testTerminalEvidence() = CurrentGameSurrenderTerminalEvidence(
        gameIdentity = "test-game:self",
        ownEntityId = "self",
        opponentEntityId = "opponent",
        ownPlayState = "CONCEDED",
        opponentPlayState = "WON",
        finalGameOver = true,
        complete = true,
    )
}
