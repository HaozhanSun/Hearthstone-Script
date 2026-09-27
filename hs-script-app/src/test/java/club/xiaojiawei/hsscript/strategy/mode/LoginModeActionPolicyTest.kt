package club.xiaojiawei.hsscript.strategy.mode

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginModeActionPolicyTest {
    @Test
    fun `safe native startup blocks speculative clicks without verified screen evidence`() {
        assertFalse(LoginModeActionPolicy.mayRetryCoordinateAction(true))
    }

    @Test
    fun `safe native login retry resumes only with independently verified screen evidence`() {
        assertTrue(LoginModeActionPolicy.mayRetryCoordinateAction(true, independentlyVerifiedScreen = true))
    }

    @Test
    fun `independently verified screen can use its explicit recovery action`() {
        assertTrue(
            LoginModeActionPolicy.mayRetryCoordinateAction(
                safeNative = true,
                independentlyVerifiedScreen = true,
            ),
        )
    }

    @Test
    fun `ordinary non safe-native login behavior is unchanged`() {
        assertTrue(LoginModeActionPolicy.mayRetryCoordinateAction(false))
    }
}
