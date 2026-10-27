package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OfflineReconnectRecoveryTest {
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
    fun `legacy reconnect restart permits stale in-game latch but defers active queue and terminal evidence`() {
        assertTrue(
            ReconnectRestartSafetyPolicy.mayRestart(
                exactReconnectVisible = true,
                activeMatchmaking = false,
                terminalState = false,
            ),
            "WarEx.inWar is deliberately not a restart veto for an exact indefinite reconnect screen",
        )
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
