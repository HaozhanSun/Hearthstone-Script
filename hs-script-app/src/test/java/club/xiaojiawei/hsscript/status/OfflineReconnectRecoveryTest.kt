package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class OfflineReconnectRecoveryTest {
    @AfterEach
    fun clearMatchmakingEvidence() = LifecycleTrace.resetMatchmakingEvidenceForTest()

    @Test
    fun `reconnect restarts at 60 seconds without clicking or pausing`() {
        val policy = IndefiniteReconnectRestartPolicy()
        assertEquals(
            IndefiniteReconnectRestartPolicy.Decision.WAIT,
            policy.observe(exactReconnectVisible = true, nowMs = 10_000L, activeQueue = false, terminalState = false),
        )
        assertEquals(
            IndefiniteReconnectRestartPolicy.Decision.WAIT,
            policy.observe(
                exactReconnectVisible = true,
                nowMs = 10_000L + 59_999L,
                activeQueue = false,
                terminalState = false,
            ),
            "a reconnect screen held for less than 60 seconds is transient",
        )
        assertEquals(60_000L, IndefiniteReconnectRestartPolicy.DEFAULT_THRESHOLD_MS)
        assertEquals(
            IndefiniteReconnectRestartPolicy.Decision.DEFER_ACTIVE_QUEUE,
            policy.observe(
                exactReconnectVisible = true,
                nowMs = 10_000L + IndefiniteReconnectRestartPolicy.DEFAULT_THRESHOLD_MS,
                activeQueue = true,
                terminalState = false,
            ),
            "an ordinary active queue always fences client restart",
        )
        val indefinite = policy.observe(
            exactReconnectVisible = true,
            nowMs = 10_000L + IndefiniteReconnectRestartPolicy.DEFAULT_THRESHOLD_MS,
            activeQueue = false,
            terminalState = false,
        )
        assertEquals(IndefiniteReconnectRestartPolicy.Decision.RESTART_CLIENT_NO_PAUSE, indefinite)
        assertEquals(1, policy.attempts())
        assertEquals(
            IndefiniteReconnectRestartPolicy.Decision.WAIT,
            policy.observe(
                exactReconnectVisible = true,
                nowMs = 10_000L + 2 * IndefiniteReconnectRestartPolicy.DEFAULT_THRESHOLD_MS - 1,
                activeQueue = false,
                terminalState = false,
            ),
            "there is no repeat reconnect click and retry cooldown is enforced",
        )
        assertEquals(
            IndefiniteReconnectRestartPolicy.Decision.RESTART_CLIENT_NO_PAUSE,
            policy.observe(
                exactReconnectVisible = true,
                nowMs = 10_000L + 2 * IndefiniteReconnectRestartPolicy.DEFAULT_THRESHOLD_MS,
                activeQueue = false,
                terminalState = false,
            ),
        )
        assertEquals(
            IndefiniteReconnectRestartPolicy.Decision.ALERT_OPERATOR,
            policy.observe(
                exactReconnectVisible = true,
                nowMs = 10_000L + 3 * IndefiniteReconnectRestartPolicy.DEFAULT_THRESHOLD_MS,
                activeQueue = false,
                terminalState = false,
            ),
        )
    }

    @Test
    fun `non reconnect screen and terminal state never restart client`() {
        val policy = IndefiniteReconnectRestartPolicy()
        assertEquals(
            IndefiniteReconnectRestartPolicy.Decision.WAIT,
            policy.observe(false, 1L, activeQueue = false, terminalState = false),
        )
        assertEquals(
            IndefiniteReconnectRestartPolicy.Decision.DEFER_TERMINAL,
            policy.observe(true, 10L, activeQueue = false, terminalState = true),
        )
        assertEquals(0, policy.attempts())
    }

    @Test
    fun `reconnect restart defers active game queue and terminal evidence`() {
        assertTrue(
            ReconnectRestartSafetyPolicy.mayRestart(
                exactReconnectVisible = true,
                activeGame = false,
                activeMatchmaking = false,
                terminalState = false,
            ),
            "an exact offline modal restarts after the bounded policy grace when all safety fences are clear",
        )
        assertFalse(ReconnectRestartSafetyPolicy.mayRestart(
            exactReconnectVisible = true,
            activeGame = true,
            currentSessionReady = true,
            activeMatchmaking = false,
            terminalState = false,
        ))
        assertFalse(ReconnectRestartSafetyPolicy.mayRestart(
            exactReconnectVisible = true,
            activeMatchmaking = true,
            terminalState = false,
        ))
        assertFalse(ReconnectRestartSafetyPolicy.mayRestart(
            exactReconnectVisible = true,
            activeMatchmaking = false,
            terminalState = true,
        ))
        assertFalse(ReconnectRestartSafetyPolicy.mayRestart(
            exactReconnectVisible = false,
            activeMatchmaking = false,
            terminalState = false,
        ))
    }

    @Test
    fun `stale WarEx latch with empty current process log does not block exact reconnect restart`() {
        val currentSessionPowerLog = PowerLogSessionBindingPolicy.isCurrentSession(
            powerLogPath = "C:/Game/Logs/Power.log",
            gameLogsRoot = "C:/Game/Logs",
            length = 0L,
            lastModifiedMs = 2_000_000L,
            processStartedAtMs = 1_999_000L,
        )
        val activeGameEvidence = ReconnectActiveGameEvidencePolicy.isAuthoritative(
            warLatch = true,
            currentSessionPowerLog = currentSessionPowerLog,
        )
        assertFalse(currentSessionPowerLog, "empty Power.log is not current game evidence")
        assertFalse(activeGameEvidence, "WarEx alone may be stale after a client replacement")
        assertTrue(
            ReconnectRestartSafetyPolicy.mayRestart(
                exactReconnectVisible = true,
                activeGame = activeGameEvidence,
                activeMatchmaking = false,
                terminalState = false,
                currentSessionReady = true,
            ),
        )
    }

    @Test
    fun `fresh current process log confirms active WarEx game and defers reconnect restart`() {
        val currentSessionPowerLog = PowerLogSessionBindingPolicy.isCurrentSession(
            powerLogPath = "C:/Game/Logs/Power.log",
            gameLogsRoot = "C:/Game/Logs",
            length = 512L,
            lastModifiedMs = 2_000_000L,
            processStartedAtMs = 1_999_000L,
        )
        val activeGameEvidence = ReconnectActiveGameEvidencePolicy.isAuthoritative(
            warLatch = true,
            currentSessionPowerLog = currentSessionPowerLog,
        )
        assertTrue(currentSessionPowerLog)
        assertTrue(activeGameEvidence)
        assertFalse(
            ReconnectRestartSafetyPolicy.mayRestart(
                exactReconnectVisible = true,
                activeGame = activeGameEvidence,
                activeMatchmaking = false,
                terminalState = false,
                currentSessionReady = true,
            ),
        )
    }

    @Test
    fun `ten minute exact offline observation escalates to restart when safety fences are clear`() {
        val policy = IndefiniteReconnectRestartPolicy()
        val observedAt = 21L * 60 * 60 * 1_000 + 56L * 60 * 1_000 + 11_000L
        assertEquals(
            IndefiniteReconnectRestartPolicy.Decision.WAIT,
            policy.observe(true, observedAt, activeQueue = false, terminalState = false),
        )
        assertEquals(
            IndefiniteReconnectRestartPolicy.Decision.RESTART_CLIENT_NO_PAUSE,
            policy.observe(
                true,
                observedAt + 10 * 60_000L,
                activeQueue = false,
                terminalState = false,
            ),
            "the persisted exact offline prompt restarts without waiting on the unrelated matchmaking-error probe",
        )
        assertEquals(1, policy.attempts())
    }

    @Test
    fun `stale matchmaking intent and screen latch do not fence an exact reconnect`() {
        val observedAt = 100_000L
        LifecycleTrace.markActiveMatchmakingScreenObserved(observedAt)
        LifecycleTrace.markMatchmakingInputDispatched(observedAt)
        val reconnectAt = observedAt + 120_001L

        assertTrue(LifecycleTrace.hasRecentMatchmakingInputIntent(reconnectAt))
        assertFalse(LifecycleTrace.hasRecentActiveMatchmakingScreen(reconnectAt))
        assertFalse(ReconnectRestartEvidencePolicy.shouldDefer(
            exactReconnectVisible = true,
            freshActiveQueue = LifecycleTrace.hasRecentActiveMatchmakingScreen(reconnectAt),
            currentSessionReady = false,
            activeGame = false,
            terminalState = false,
        ))
        assertTrue(ReconnectRestartSafetyPolicy.mayRestart(
            exactReconnectVisible = true,
            activeMatchmaking = LifecycleTrace.hasRecentActiveMatchmakingScreen(reconnectAt),
            terminalState = false,
        ))
    }

    @Test
    fun `exact reconnect clears a recent queue-screen latch and restarts after 60 seconds despite stale intent`() {
        val queueObservedAt = 100_000L
        LifecycleTrace.markActiveMatchmakingScreenObserved(queueObservedAt)
        LifecycleTrace.markMatchmakingInputDispatched(queueObservedAt)
        val reconnectObservedAt = queueObservedAt + 30_000L
        assertTrue(LifecycleTrace.hasRecentActiveMatchmakingScreen(reconnectObservedAt))
        assertTrue(LifecycleTrace.hasRecentMatchmakingInputIntent(reconnectObservedAt))

        // This is the production RECONNECT branch behavior: the exact current
        // reconnect capture supersedes the previous queue screen, but leaves
        // long-window input intent history intact for diagnostics.
        assertTrue(LifecycleTrace.supersedeActiveMatchmakingScreenForReconnect(now = reconnectObservedAt))
        assertFalse(LifecycleTrace.hasRecentActiveMatchmakingScreen(reconnectObservedAt))
        assertTrue(LifecycleTrace.hasRecentMatchmakingInputIntent(reconnectObservedAt))
        assertFalse(ReconnectRestartEvidencePolicy.shouldDefer(
            exactReconnectVisible = true,
            freshActiveQueue = LifecycleTrace.hasRecentActiveMatchmakingScreen(reconnectObservedAt),
            currentSessionReady = false,
            activeGame = false,
            terminalState = false,
        ))

        val policy = IndefiniteReconnectRestartPolicy()
        assertEquals(IndefiniteReconnectRestartPolicy.Decision.WAIT,
            policy.observe(true, reconnectObservedAt, activeQueue = false, terminalState = false))
        assertEquals(IndefiniteReconnectRestartPolicy.Decision.WAIT,
            policy.observe(true, reconnectObservedAt + 59_999L, activeQueue = false, terminalState = false))
        assertEquals(IndefiniteReconnectRestartPolicy.Decision.RESTART_CLIENT_NO_PAUSE,
            policy.observe(true, reconnectObservedAt + 60_000L, activeQueue = false, terminalState = false))
    }

    @Test
    fun `fresh queue authoritative active game and terminal proof still defer restart`() {
        assertTrue(ReconnectRestartEvidencePolicy.shouldDefer(true, true, false, false, false))
        assertTrue(ReconnectRestartEvidencePolicy.shouldDefer(true, false, true, true, false))
        assertTrue(ReconnectRestartEvidencePolicy.shouldDefer(true, false, false, false, true))
        assertFalse(ReconnectRestartEvidencePolicy.shouldDefer(true, false, true, false, false))
        assertFalse(ReconnectRestartEvidencePolicy.shouldDefer(true, false, false, true, false))
    }

    @Test
    fun `reconnect age continues through a temporary fresh queue deferral`() {
        val policy = IndefiniteReconnectRestartPolicy()
        assertEquals(IndefiniteReconnectRestartPolicy.Decision.WAIT,
            policy.observe(true, 10_000L, activeQueue = false, terminalState = false))
        assertEquals(IndefiniteReconnectRestartPolicy.Decision.DEFER_ACTIVE_QUEUE,
            policy.observe(true, 20_000L, activeQueue = true, terminalState = false))
        assertEquals(IndefiniteReconnectRestartPolicy.Decision.RESTART_CLIENT_NO_PAUSE,
            policy.observe(true, 70_000L, activeQueue = false, terminalState = false))
    }

    @Test
    fun `exact offline modal receives bounded grace before restart without input`() {
        val transition = ScreenStateRecovery.recoveryTransitionForTest(
            ocrText = "",
            targeted = mapOf(
                ScreenStateRoiSelector.RECONNECT_DIALOG_STATUS_ROI to "当前处于离线状态",
                ScreenStateRoiSelector.RECONNECT_DIALOG_MESSAGE_ROI to "游戏连接中断",
            ),
        )
        assertEquals("RECONNECT", transition?.screen)
        assertEquals(true, ScreenRecoveryCapturePurposePolicy.allowsStaleProbeRecovery("RECONNECT"))

        val recovery = OfflineReconnectRecovery()
        val request = recovery.observe(OfflineReconnectRecovery.Screen.OFFLINE_PROMPT, 10_000L)
        assertEquals(OfflineReconnectRecovery.Action.WAIT_FOR_SPINNER, request.action)
        assertEquals(
            OfflineReconnectRecovery.Action.WAIT_FOR_SPINNER,
            recovery.observe(OfflineReconnectRecovery.Screen.OFFLINE_PROMPT, 69_999L).action,
        )
        val persistent = recovery.observe(OfflineReconnectRecovery.Screen.OFFLINE_PROMPT, 70_000L)
        assertEquals(OfflineReconnectRecovery.Action.ESCALATE, persistent.action)
        assertEquals("reconnect-persisted-through-grace", persistent.reason)
    }

    @Test
    fun `prompt observes bounded reconnect grace without clicking`() {
        val recovery = OfflineReconnectRecovery()
        val prompt = recovery.observe(OfflineReconnectRecovery.Screen.OFFLINE_PROMPT, 0L)
        val early = recovery.observe(OfflineReconnectRecovery.Screen.OFFLINE_PROMPT, 9_999L)
        assertEquals(OfflineReconnectRecovery.Action.WAIT_FOR_SPINNER, prompt.action)
        assertEquals(OfflineReconnectRecovery.Action.WAIT_FOR_SPINNER, early.action)
        assertEquals(OfflineReconnectRecovery.DEFAULT_SPINNER_WAIT_MS, prompt.deadlineMs)
    }

    @Test
    fun `spinner timeout escalates without clicking cancel`() {
        val recovery = OfflineReconnectRecovery()
        recovery.observe(OfflineReconnectRecovery.Screen.OFFLINE_PROMPT, 0L)
        recovery.reportReconnectDispatched(0L, true)
        assertEquals(
            OfflineReconnectRecovery.Action.WAIT_FOR_SPINNER,
            recovery.observe(OfflineReconnectRecovery.Screen.RECONNECT_SPINNER, 10_000L).action,
        )
        val timeout = recovery.observe(
            OfflineReconnectRecovery.Screen.RECONNECT_SPINNER,
            OfflineReconnectRecovery.DEFAULT_SPINNER_WAIT_MS,
        )
        val repeated = recovery.observe(
            OfflineReconnectRecovery.Screen.RECONNECT_SPINNER,
            OfflineReconnectRecovery.DEFAULT_SPINNER_WAIT_MS + 10_000L,
        )
        assertEquals(OfflineReconnectRecovery.Action.ESCALATE, timeout.action)
        assertEquals(OfflineReconnectRecovery.State.FAILED, timeout.state)
        assertEquals("spinner-still-visible-after-ten-seconds", timeout.reason)
        assertEquals(OfflineReconnectRecovery.Action.NONE, repeated.action)
        assertEquals(
            OfflineReconnectRecovery.Action.MARK_RECONNECTED,
            recovery.observe(OfflineReconnectRecovery.Screen.CONNECTED, 70_001L).action,
            "a later verified connected screen still completes the flow after escalation",
        )
    }

    @Test
    fun `connected screen completes the reconnect flow`() {
        val recovery = OfflineReconnectRecovery()
        recovery.observe(OfflineReconnectRecovery.Screen.OFFLINE_PROMPT, 0L)
        recovery.reportReconnectDispatched(0L, true)
        assertEquals(
            OfflineReconnectRecovery.Action.MARK_RECONNECTED,
            recovery.observe(OfflineReconnectRecovery.Screen.CONNECTED, 4_000L).action,
        )
    }

    @Test
    fun `standalone spinner is ignored`() {
        val decision = OfflineReconnectRecovery().observe(OfflineReconnectRecovery.Screen.RECONNECT_SPINNER, 10_000L)
        assertEquals(OfflineReconnectRecovery.Action.NONE, decision.action)
        assertEquals("spinner-without-reconnect-flow", decision.reason)
    }

    @Test
    fun `process replacement resets the flow`() {
        val recovery = OfflineReconnectRecovery()
        assertEquals(OfflineReconnectRecovery.ProbeLineage.INITIAL, recovery.observeProbeLineage(10L, "a.png"))
        recovery.observe(OfflineReconnectRecovery.Screen.OFFLINE_PROMPT, 0L)
        assertEquals(OfflineReconnectRecovery.ProbeLineage.PROCESS_REPLACED, recovery.observeProbeLineage(11L, "b.png"))
        assertEquals(OfflineReconnectRecovery.State.IDLE, recovery.state)
    }

    @Test
    fun `offline reconnect grace escalates at 60 seconds without any click`() {
        val recovery = OfflineReconnectRecovery()
        assertEquals(
            OfflineReconnectRecovery.Action.WAIT_FOR_SPINNER,
            recovery.observe(OfflineReconnectRecovery.Screen.OFFLINE_PROMPT, 1_000L).action,
        )
        assertEquals(
            OfflineReconnectRecovery.Action.WAIT_FOR_SPINNER,
            recovery.observe(OfflineReconnectRecovery.Screen.OFFLINE_PROMPT, 30_000L).action,
        )
        assertEquals(
            OfflineReconnectRecovery.Action.ESCALATE,
            recovery.observe(
                OfflineReconnectRecovery.Screen.OFFLINE_PROMPT,
                1_000L + OfflineReconnectRecovery.DEFAULT_SPINNER_WAIT_MS,
            ).action,
        )
        assertEquals(1, recovery.reconnectAttempts)
    }
}
