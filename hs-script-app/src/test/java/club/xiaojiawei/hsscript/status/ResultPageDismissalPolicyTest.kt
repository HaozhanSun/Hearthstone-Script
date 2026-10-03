package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResultPageDismissalPolicyTest {
    @Test
    fun `valid terminal cleanup survives tournament mode transition but pause still stops it`() {
        assertEquals(
            false,
            ResultPageDismissalPolicy.shouldStopWorker(
                paused = false,
                gameplayMode = false,
                terminalCleanupCapabilityValid = true,
            ),
        )
        assertEquals(
            true,
            ResultPageDismissalPolicy.shouldStopWorker(
                paused = false,
                gameplayMode = false,
                terminalCleanupCapabilityValid = false,
            ),
        )
        assertEquals(
            true,
            ResultPageDismissalPolicy.shouldStopWorker(
                paused = true,
                gameplayMode = false,
                terminalCleanupCapabilityValid = true,
            ),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 1,
                maxAttempts = 5,
            ),
        )
    }

    @Test
    fun `active war permits result click only while fresh screen evidence confirms result`() {
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(inWar = true, resultPageVisible = true, attempt = 1, maxAttempts = 5),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.BLOCKED_UNCONFIRMED_DURING_WAR,
            ResultPageDismissalPolicy.decide(inWar = true, resultPageVisible = null, attempt = 1, maxAttempts = 5),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(inWar = true, resultPageVisible = false, attempt = 2, maxAttempts = 5),
        )
    }

    @Test
    fun `unknown postcheck never counts as success and retries remain bounded`() {
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(inWar = false, resultPageVisible = null, attempt = 1, maxAttempts = 5),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(inWar = false, resultPageVisible = true, attempt = 5, maxAttempts = 5),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(inWar = false, resultPageVisible = null, attempt = 6, maxAttempts = 5),
        )
    }
}
