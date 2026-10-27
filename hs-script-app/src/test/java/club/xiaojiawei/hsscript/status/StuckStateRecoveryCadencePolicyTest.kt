package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StuckStateRecoveryCadencePolicyTest {
    @Test
    fun `repeated OCR TIFF failures keep Beta recovery probes due at ten second intervals`() {
        val start = 1_000_000L
        var lastAttempt = 0L
        repeat(8) { index ->
            val now = start + index * StuckStateRecoveryCadencePolicy.BETA_RETRY_INTERVAL_MS
            assertTrue(
                StuckStateRecoveryCadencePolicy.shouldProbe(
                    channel = "beta",
                    stillStuck = true, // An OCR/TIFF failure leaves the screen unresolved.
                    stuckForMs = 30_000L + index * 10_000L,
                    lastAttemptAtMs = lastAttempt,
                    nowMs = now,
                ),
            )
            lastAttempt = now
        }
        assertFalse(
            StuckStateRecoveryCadencePolicy.shouldProbe(
                channel = "beta",
                stillStuck = true,
                stuckForMs = 100_000L,
                lastAttemptAtMs = lastAttempt,
                nowMs = lastAttempt + 9_999L,
            ),
        )
    }

    @Test
    fun `confirmed destination ends retries and cadence change is Beta only`() {
        assertFalse(
            StuckStateRecoveryCadencePolicy.shouldProbe(
                channel = "beta",
                stillStuck = false,
                stuckForMs = 120_000L,
                lastAttemptAtMs = 1_000L,
                nowMs = 200_000L,
            ),
        )
        assertEquals(30_000L, StuckStateRecoveryCadencePolicy.retryIntervalMs("release-candidate"))
        assertEquals(30_000L, StuckStateRecoveryCadencePolicy.retryIntervalMs("stable"))
        assertEquals(10_000L, StuckStateRecoveryCadencePolicy.retryIntervalMs("beta"))
    }
}
