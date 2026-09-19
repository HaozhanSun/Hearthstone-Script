package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenRecoveryFocusRetryPolicyTest {
    @Test
    fun `foreground recovery remains retryable without pausing the worker`() {
        assertEquals(
            ScreenRecoveryFocusRetryPolicy.Decision.RETRY_LATER,
            ScreenRecoveryFocusRetryPolicy.afterForegroundFailure(0),
        )
        assertEquals(
            ScreenRecoveryFocusRetryPolicy.Decision.RETRY_LATER,
            ScreenRecoveryFocusRetryPolicy.afterForegroundFailure(1),
        )
        assertEquals(
            ScreenRecoveryFocusRetryPolicy.Decision.RETRY_LATER,
            ScreenRecoveryFocusRetryPolicy.afterForegroundFailure(2),
        )
        assertEquals(
            ScreenRecoveryFocusRetryPolicy.Decision.RETRY_LATER,
            ScreenRecoveryFocusRetryPolicy.afterForegroundFailure(20),
        )
    }
}
