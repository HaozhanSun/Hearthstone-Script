package club.xiaojiawei.hsscript.strategy.phase

import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MulliganRankSurrenderRetryPolicyTest {

    @Test
    fun `executor rejection gets a bounded retry budget and leaves phase closed`() {
        val policy = MulliganRankSurrenderRetryPolicy(listOf(10L, 20L, 40L))
        repeat(3) { index ->
            val retry = policy.nextRetryAfterRejection()
            assertEquals(listOf(10L, 20L, 40L)[index], retry.delayMs)
            assertEquals(index + 1, retry.attempt)
            assertFalse(retry.pause)
            assertFalse(retry.allowPhaseAdvance)
            assertFalse(
                phaseMayAdvance(MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED),
                "an executor rejection must not let MULLIGAN_STATE=DONE open gameplay",
            )
        }

        val cooldown = policy.nextRetryAfterRejection()
        assertEquals(10_000L, cooldown.delayMs)
        assertEquals(0, cooldown.attempt)
        assertTrue(cooldown.cooldown)
        assertFalse(cooldown.pause)
        assertFalse(cooldown.allowPhaseAdvance)
        assertEquals(0, policy.scheduledRetryCount(), "cooldown begins a fresh bounded retry burst")
        assertFalse(
            phaseMayAdvance(MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED),
            "cooldown must keep the task unpaused but fail closed behind the rank barrier",
        )
    }

    @Test
    fun `after cooldown a fresh bounded retry burst resumes without pausing or opening gameplay`() {
        val policy = MulliganRankSurrenderRetryPolicy(listOf(1L, 2L), cooldownMs = 25L)
        val retry = policy.nextRetryAfterRejection()
        assertEquals(1L, retry.delayMs)
        assertFalse(retry.pause)
        assertFalse(retry.allowPhaseAdvance)
        assertEquals(2L, policy.nextRetryAfterRejection().delayMs)
        val cooldown = policy.nextRetryAfterRejection()
        assertTrue(cooldown.cooldown)
        assertEquals(25L, cooldown.delayMs)
        // The production scheduler executes the returned plan after the
        // cooldown; another rejection starts the next bounded burst.
        val resumedRetry = policy.nextRetryAfterRejection()
        assertEquals(1L, resumedRetry.delayMs)
        assertEquals(1, resumedRetry.attempt)
        assertFalse(resumedRetry.pause)
        assertFalse(resumedRetry.allowPhaseAdvance)
        assertFalse(phaseMayAdvance(MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED))
        assertFalse(phaseMayAdvance(MulliganRankDispatchBarrier.State.PENDING))
        assertTrue(phaseMayAdvance(MulliganRankDispatchBarrier.State.ELIGIBLE))
        assertTrue(phaseMayAdvance(MulliganRankDispatchBarrier.State.IDLE))
    }

    private fun phaseMayAdvance(state: MulliganRankDispatchBarrier.State): Boolean =
        !MulliganRankSurrenderRetryPolicy.mustBlockMulliganAdvance(state)
}
