package club.xiaojiawei.hsscript.strategy.mode

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginModeActionPolicyTest {
    @Test
    fun `safe native scheduled login retries stay blocked pending visual recovery`() {
        assertFalse(LoginModeActionPolicy.mayRetryCoordinateAction(true))
    }

    @Test
    fun `ordinary non safe-native login behavior is unchanged`() {
        assertTrue(LoginModeActionPolicy.mayRetryCoordinateAction(false))
    }
}
