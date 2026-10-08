package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.GameStartupModeEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.starter.GameStartupModeSequencePolicy
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
import club.xiaojiawei.hsscript.status.surrender.CurrentGameSurrenderTerminalEvidence
import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActionDispatchGateTest {

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
    fun `rank preflight barrier blocks queued game actions until eligible rank is verified`() {
        listOf("mulligan.confirm", "strategy.card.play", "strategy.turn-end", "queue.retry", "surrender.request")
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
    fun `authoritative terminal cleanup may pass automatic pause but never manual F2 or ordinary actions`() {
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "terminal-result.dismiss",
                paused = true,
                working = false,
                terminalCleanupPending = true,
                terminalCleanupCapabilityValid = true,
                automaticPause = true,
            ),
            "same-game terminal capability permits only the cleanup action through a watchdog safety pause",
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "terminal-result.dismiss",
                paused = true,
                working = false,
                terminalCleanupPending = true,
                terminalCleanupCapabilityValid = true,
                automaticPause = false,
            ),
            "manual/F2 pause remains absolute even when terminal cleanup is pending",
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "matchmaking.start",
                paused = true,
                working = false,
                terminalCleanupPending = true,
                terminalCleanupCapabilityValid = true,
                automaticPause = true,
            ),
            "terminal capability cannot authorize queueing or any ordinary action",
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
            PauseStatus.setManualPauseForTest(false)
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
                !wasPaused -> PauseStatus.setManualPauseForTest(false)
                oldPauseOrigin == PauseStatus.Origin.AUTOMATIC -> PauseStatus.setAutomaticPause(true)
                else -> PauseStatus.setManualPauseForTest(true)
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
            assertFalse(
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
            PauseStatus.setManualPauseForTest(false)
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
            assertTrue(ActionDispatchGate.allow("matchmaking.start"))
            assertFalse(PauseStatus.isPause, "terminal proof releases rank fencing without auto-pausing")
        } finally {
            MandatoryRankSurrenderGuard.resetForTest()
            MulliganRankDispatchBarrier.resetForTest()
            WorkTimeListener.working = wasWorking
            when {
                !wasPaused -> PauseStatus.setManualPauseForTest(false)
                oldPauseOrigin == PauseStatus.Origin.AUTOMATIC -> PauseStatus.setAutomaticPause(true)
                else -> PauseStatus.setManualPauseForTest(true)
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
