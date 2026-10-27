package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals

class ResultTerminalWaitPolicyTest {

    @Test
    fun `terminal evidence arriving after initial unknown resumes result handling immediately`() {
        val policy = ResultTerminalWaitPolicy(timeoutMs = 15_000L)

        assertEquals(
            ResultTerminalWaitPolicy.Evaluation(ResultTerminalWaitPolicy.Outcome.WAITING, firstWait = true),
            policy.evaluate(authoritativeTerminalAvailable = false, nowMs = 1_000L),
        )
        assertEquals(
            ResultTerminalWaitPolicy.Evaluation(ResultTerminalWaitPolicy.Outcome.READY),
            policy.evaluate(authoritativeTerminalAvailable = true, nowMs = 2_000L),
        )
    }

    @Test
    fun `missing terminal evidence exits the wait at the bounded deadline`() {
        val policy = ResultTerminalWaitPolicy(timeoutMs = 15_000L)
        policy.evaluate(authoritativeTerminalAvailable = false, nowMs = 1_000L)

        assertEquals(
            ResultTerminalWaitPolicy.Outcome.WAITING,
            policy.evaluate(authoritativeTerminalAvailable = false, nowMs = 15_999L).outcome,
        )
        assertEquals(
            ResultTerminalWaitPolicy.Outcome.TIMED_OUT,
            policy.evaluate(authoritativeTerminalAvailable = false, nowMs = 16_000L).outcome,
        )
    }
}
