package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.GameStartupModeEnum
import club.xiaojiawei.hsscript.starter.GameStartupModeSequencePolicy
import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `authoritative terminal capability allows only result dismissal while rank guard remains pending`() {
        MandatoryRankSurrenderGuard.resetForTest()
        try {
            MandatoryRankSurrenderGuard.begin()
            assertEquals(null, MandatoryRankSurrenderGuard.authorizeTerminalCleanup("UNKNOWN"))
            val terminalCapability =
                MandatoryRankSurrenderGuard.authorizeTerminalCleanup("POWERLOG_TERMINAL")
            assertTrue(terminalCapability != null)
            assertTrue(MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(terminalCapability))
            assertTrue(MandatoryRankSurrenderGuard.isPending())
            assertTrue(
                ActionDispatchGate.allowForState(
                    action = "terminal-result.dismiss",
                    paused = false,
                    working = true,
                    mandatoryRankSurrenderPending = true,
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
                        mandatoryRankSurrenderPending = true,
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
                    mandatoryRankSurrenderPending = true,
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

            assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_RESULT_DISMISSED"))
            assertFalse(MandatoryRankSurrenderGuard.isPending())
            assertFalse(MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(terminalCapability))
            // Releasing the cleanup lock does not bypass the ordinary rank
            // preflight, which will run again for the next match.
            assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, false))
        } finally {
            MandatoryRankSurrenderGuard.resetForTest()
        }
    }
}
