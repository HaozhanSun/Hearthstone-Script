package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnknownStartupRestartPolicyTest {
    @Test
    fun `unknown startup remains read only until over sixty seconds then bounded no pause restart`() {
        val policy = UnknownStartupRestartPolicy()
        fun observe(now: Long, stuck: Long, eligible: Boolean = true, game: Boolean = true, queue: Boolean = true) =
            policy.observe("same-unknown-screen", now, stuck, eligible, game, queue)

        assertEquals(UnknownStartupRestartPolicy.Decision.WAIT, observe(30_000, 30_000))
        assertEquals(UnknownStartupRestartPolicy.Decision.WAIT, observe(59_999, 59_999))
        assertEquals(UnknownStartupRestartPolicy.Decision.RESTART_CLIENT, observe(60_001, 60_001))
        assertEquals(UnknownStartupRestartPolicy.Decision.WAIT, observe(180_000, 120_000))
        assertEquals(UnknownStartupRestartPolicy.Decision.RESTART_CLIENT, observe(180_001, 180_001))
        assertEquals(UnknownStartupRestartPolicy.Decision.ALERT_OPERATOR, observe(240_001, 60_000))
    }

    @Test
    fun `unknown recovery defers for active games queues and non startup probes`() {
        val policy = UnknownStartupRestartPolicy()
        assertEquals(
            UnknownStartupRestartPolicy.Decision.WAIT,
            policy.observe("unknown", 90_000, 90_000, eligibleProbe = true, noActiveGame = false, noActiveQueue = true),
        )
        assertEquals(
            UnknownStartupRestartPolicy.Decision.WAIT,
            policy.observe("unknown", 90_000, 90_000, eligibleProbe = true, noActiveGame = true, noActiveQueue = false),
        )
        assertEquals(
            UnknownStartupRestartPolicy.Decision.WAIT,
            policy.observe("unknown", 90_000, 90_000, eligibleProbe = false, noActiveGame = true, noActiveQueue = true),
        )
    }

    @Test
    fun `resolved startup clears the restart budget`() {
        val policy = UnknownStartupRestartPolicy(maxRestarts = 1)
        assertEquals(
            UnknownStartupRestartPolicy.Decision.RESTART_CLIENT,
            policy.observe("unknown", 60_001, 60_001, true, true, true),
        )
        assertEquals(
            UnknownStartupRestartPolicy.Decision.ALERT_OPERATOR,
            policy.observe("unknown", 180_000, 60_000, true, true, true),
        )
        policy.reset()
        assertEquals(
            UnknownStartupRestartPolicy.Decision.RESTART_CLIENT,
            policy.observe("unknown", 240_001, 60_001, true, true, true),
        )
    }

    @Test
    fun `queued restart is coalesced and operator exhaustion cannot overlap it`() {
        val requests = ClientRestartRequestGate()
        assertTrue(requests.tryAcquire(processId = 38168, now = 10_000L))
        assertTrue(requests.isInFlight(10_001L))
        assertFalse(requests.tryAcquire(processId = 38168, now = 10_002L), "a second observer must not queue another client restart")
        requests.releaseIfProcessChanged(51240)
        assertFalse(requests.isInFlight(10_003L))
        assertTrue(requests.tryAcquire(processId = 51240, now = 10_004L), "a replacement process can start a later bounded attempt")

        val policy = UnknownStartupRestartPolicy(maxRestarts = 1)
        assertEquals(UnknownStartupRestartPolicy.Decision.RESTART_CLIENT, policy.observe("same", 60_001, 60_001, true, true, true))
        assertEquals(UnknownStartupRestartPolicy.Decision.ALERT_OPERATOR, policy.observe("same", 120_001, 60_000, true, true, true))
        assertTrue(requests.isInFlight(10_005L), "exhaustion does not revoke or duplicate an already queued request")
        assertFalse(requests.isInFlight(130_004L), "stuck restart callback is released after the bounded in-flight timeout")
    }
}
