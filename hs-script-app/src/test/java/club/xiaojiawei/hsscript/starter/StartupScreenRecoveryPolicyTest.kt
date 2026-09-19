package club.xiaojiawei.hsscript.starter

import kotlin.test.Test
import kotlin.test.assertEquals

class StartupScreenRecoveryPolicyTest {
    @Test
    fun `first probe is allowed after three seconds`() {
        assertEquals(
            StartupScreenRecoveryPolicy.Decision.PROBE,
            StartupScreenRecoveryPolicy.decide(3_000L, 3_000L, false, false),
        )
    }

    @Test
    fun `later probe waits for thirty seconds without Power log progress`() {
        assertEquals(
            StartupScreenRecoveryPolicy.Decision.PROBE,
            StartupScreenRecoveryPolicy.decide(33_000L, 30_000L, false, true),
        )
    }

    @Test
    fun `normal flow suppresses startup recovery`() {
        assertEquals(
            StartupScreenRecoveryPolicy.Decision.DEFER_NORMAL_FLOW,
            StartupScreenRecoveryPolicy.decide(3_000L, 30_000L, true, false),
        )
    }
}
