package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StuckScreenUnblockRecheckGateTest {
    @Test
    fun `completed recheck releases lease`() {
        val gate = StuckScreenUnblockRecheckGate()
        var queued: (() -> Unit)? = null
        var ran = false
        assertTrue(gate.schedule(
            nowMs = 1_000L,
            enqueue = { queued = it },
            currentTimeMs = { 1_001L },
        ) { ran = true })
        assertTrue(gate.isInFlight(1_001L))
        queued!!.invoke()
        assertTrue(ran)
        assertFalse(gate.isInFlight(1_002L))
    }

    @Test
    fun `failed callback and rejected schedule both release lease`() {
        val callbackGate = StuckScreenUnblockRecheckGate()
        var queued: (() -> Unit)? = null
        assertTrue(callbackGate.schedule(
            nowMs = 2_000L,
            enqueue = { queued = it },
            currentTimeMs = { 2_001L },
        ) { error("probe failure") })
        assertThrows(IllegalStateException::class.java) { queued!!.invoke() }
        assertFalse(callbackGate.isInFlight(2_002L))

        val rejectedGate = StuckScreenUnblockRecheckGate()
        assertFalse(rejectedGate.schedule(
            nowMs = 3_000L,
            enqueue = { throw IllegalStateException("executor rejected") },
        ) {})
        assertFalse(rejectedGate.isInFlight(3_001L))
    }

    @Test
    fun `timeout invalidates stale callback and stale completion cannot clear a newer lease`() {
        val gate = StuckScreenUnblockRecheckGate(timeoutMs = 100L)
        var queued: (() -> Unit)? = null
        var staleActionRan = false
        assertTrue(gate.schedule(
            nowMs = 1_000L,
            enqueue = { queued = it },
            currentTimeMs = { 1_200L },
        ) { staleActionRan = true })
        val old = requireNotNull(gate.expireIfTimedOut(1_100L))
        val current = requireNotNull(gate.tryBegin(1_101L))
        queued!!.invoke()
        assertFalse(staleActionRan, "a callback that resumes after its timeout has no permission to inspect or act")
        assertFalse(gate.finish(old), "old completion must not release the replacement lease")
        assertTrue(gate.isCurrent(current, 1_102L))
    }
}
