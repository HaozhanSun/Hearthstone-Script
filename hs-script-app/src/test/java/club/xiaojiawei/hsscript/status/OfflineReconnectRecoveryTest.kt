package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OfflineReconnectRecoveryTest {
    @Test
    fun `prompt focuses reconnect and waits for spinner`() {
        val recovery = OfflineReconnectRecovery()
        val prompt = recovery.observe(OfflineReconnectRecovery.Screen.OFFLINE_PROMPT, 0L)
        val accepted = recovery.reportReconnectDispatched(0L, true)
        val early = recovery.observe(OfflineReconnectRecovery.Screen.RECONNECT_SPINNER, 9_999L)
        assertEquals(OfflineReconnectRecovery.Action.FOCUS_AND_CLICK_RECONNECT, prompt.action)
        assertEquals(OfflineReconnectRecovery.Action.WAIT_FOR_SPINNER, accepted.action)
        assertEquals(OfflineReconnectRecovery.Action.WAIT_FOR_SPINNER, early.action)
        assertEquals(10_000L, accepted.deadlineMs)
    }

    @Test
    fun `spinner is cancelled once after ten seconds`() {
        val recovery = OfflineReconnectRecovery()
        recovery.observe(OfflineReconnectRecovery.Screen.OFFLINE_PROMPT, 0L)
        recovery.reportReconnectDispatched(0L, true)
        val cancel = recovery.observe(OfflineReconnectRecovery.Screen.RECONNECT_SPINNER, 10_000L)
        recovery.reportCancelDispatched(10_000L, true)
        val repeated = recovery.observe(OfflineReconnectRecovery.Screen.RECONNECT_SPINNER, 20_000L)
        assertEquals(OfflineReconnectRecovery.Action.FOCUS_AND_CLICK_CANCEL, cancel.action)
        assertEquals(OfflineReconnectRecovery.Action.ESCALATE, repeated.action)
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
}
