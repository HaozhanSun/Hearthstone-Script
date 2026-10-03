package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResultPageDismissalPolicyTest {
    @Test
    fun `OS accepted center click is not UI acceptance and second attempt uses upstream Enter fallback`() {
        assertEquals(ResultPageDismissalPolicy.Input.CENTER_CLICK, ResultPageDismissalPolicy.inputForClickAttempt(1, 5))
        assertEquals(ResultPageDismissalPolicy.Input.KEYBOARD_ENTER, ResultPageDismissalPolicy.inputForClickAttempt(2, 5))
        assertEquals(ResultPageDismissalPolicy.Input.RETRY_CLICK, ResultPageDismissalPolicy.inputForClickAttempt(3, 5))
        assertEquals(null, ResultPageDismissalPolicy.inputForClickAttempt(6, 5))

        // Even if SendInput returned accepted=true, only a fresh observed
        // transition releases the result barrier; a still-visible result is
        // retried and UNKNOWN remains passive.
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 2,
                maxAttempts = 5,
                clickAttempts = 1,
                terminalCleanupAuthorized = true,
            ),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 3,
                maxAttempts = 5,
                clickAttempts = 2,
                terminalCleanupAuthorized = true,
            ),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 4,
                maxAttempts = 5,
                clickAttempts = 2,
                terminalCleanupAuthorized = true,
            ),
        )
    }

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
            true,
            ResultPageDismissalPolicy.shouldStopWorker(
                paused = false,
                gameplayMode = true,
                terminalCleanupCapabilityValid = true,
                newGameDetected = true,
            ),
            "even an authorized old-result worker must stop when a newer game begins",
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
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(inWar = false, resultPageVisible = null, attempt = 1, maxAttempts = 5),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(inWar = false, resultPageVisible = null, attempt = 5, maxAttempts = 5),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(inWar = false, resultPageVisible = true, attempt = 5, maxAttempts = 5),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(inWar = false, resultPageVisible = null, attempt = 6, maxAttempts = 5),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 100,
                maxAttempts = 5,
                clickAttempts = 5,
                terminalCleanupAuthorized = true,
            ),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 100,
                maxAttempts = 5,
                clickAttempts = 5,
                terminalCleanupAuthorized = true,
            ),
            "the terminal capability permits passive observation, not a sixth click",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 100,
                maxAttempts = 5,
                clickAttempts = 5,
                terminalCleanupAuthorized = true,
            ),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 100,
                maxAttempts = 5,
                clickAttempts = 5,
                terminalCleanupAuthorized = false,
            ),
        )
    }
}
