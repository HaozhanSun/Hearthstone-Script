package club.xiaojiawei.hsscript.starter

import kotlin.test.Test
import kotlin.test.assertEquals

class StartupScreenRecoveryPolicyTest {
    @Test
    fun `first probe is allowed after upstream startup grace even with an empty current log`() {
        assertEquals(
            StartupScreenRecoveryPolicy.Decision.PROBE,
            StartupScreenRecoveryPolicy.decide(
                StartupScreenRecoveryPolicy.INITIAL_PROBE_DELAY_MS,
                StartupScreenRecoveryPolicy.INITIAL_PROBE_DELAY_MS,
                false,
                false,
            ),
        )
    }

    @Test
    fun `retry probe follows bounded two second no-progress interval`() {
        assertEquals(
            StartupScreenRecoveryPolicy.Decision.PROBE,
            StartupScreenRecoveryPolicy.decide(
                StartupScreenRecoveryPolicy.INITIAL_PROBE_DELAY_MS + StartupScreenRecoveryPolicy.PROBE_RETRY_INTERVAL_MS,
                StartupScreenRecoveryPolicy.PROBE_RETRY_INTERVAL_MS,
                false,
                true,
            ),
        )
    }

    @Test
    fun `probe retries stop at the upstream fifteen second bound`() {
        assertEquals(
            StartupScreenRecoveryPolicy.Decision.FINISHED,
            StartupScreenRecoveryPolicy.decide(
                StartupScreenRecoveryPolicy.MAX_PROBE_WINDOW_MS,
                StartupScreenRecoveryPolicy.PROBE_RETRY_INTERVAL_MS,
                false,
                true,
            ),
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
