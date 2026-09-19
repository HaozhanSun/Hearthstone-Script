package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeFaultBackoffTest {
    @Test
    fun `repeated worker faults back off and open a bounded circuit`() {
        val backoff = RuntimeFaultBackoff(maxDelayMs = 1_000L, baseDelayMs = 100L)

        assertEquals(RuntimeFaultBackoff.Decision(1, 100L, false), backoff.onFailure())
        assertEquals(RuntimeFaultBackoff.Decision(2, 200L, false), backoff.onFailure())
        assertEquals(RuntimeFaultBackoff.Decision(3, 400L, true), backoff.onFailure())
        assertEquals(RuntimeFaultBackoff.Decision(4, 800L, true), backoff.onFailure())
        assertEquals(RuntimeFaultBackoff.Decision(5, 1_000L, true), backoff.onFailure())
    }

    @Test
    fun `successful observation clears stale fault budget`() {
        val backoff = RuntimeFaultBackoff(baseDelayMs = 100L)
        backoff.onFailure()
        backoff.onFailure()
        backoff.onSuccess()

        assertEquals(0, backoff.failureCount())
        assertFalse(backoff.onFailure().circuitOpened)
        assertTrue(backoff.onFailure().delayMs > 0L)
    }
}
