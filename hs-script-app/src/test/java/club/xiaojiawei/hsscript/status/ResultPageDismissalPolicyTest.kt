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
        // transition releases the result barrier; after the two authorized
        // terminal inputs, UNKNOWN exhausts instead of treating dispatch as success.
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 2,
                maxAttempts = 5,
                clickAttempts = 1,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 3,
                maxAttempts = 2,
                clickAttempts = 2,
                terminalCleanupAuthorized = true,
            ),
            "the center click plus one Enter exhaust the terminal fallback budget",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 4,
                maxAttempts = 2,
                clickAttempts = 2,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
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
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 100,
                maxAttempts = 5,
                clickAttempts = 5,
                terminalCleanupAuthorized = true,
            ),
            "authorized cleanup is bounded and must not idle forever after its click budget",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 100,
                maxAttempts = 5,
                clickAttempts = 5,
                terminalCleanupAuthorized = true,
            ),
            "the terminal capability cannot permit a sixth click",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 100,
                maxAttempts = 5,
                clickAttempts = 5,
                terminalCleanupAuthorized = true,
            ),
            "an unverified negative postcheck cannot confirm dismissal",
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

    @Test
    fun `terminal fallback needs a fresh authorized capture and ends after two inputs`() {
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = true,
                attempt = 1,
                maxAttempts = 2,
                terminalCleanupAuthorized = true,
                captureAuthorized = false,
            ),
            "even OCR-positive classification cannot ground a terminal action without pixel-owned capture",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 1,
                maxAttempts = 2,
                terminalCleanupAuthorized = true,
                captureAuthorized = false,
            ),
            "an unverified negative postcheck cannot release the terminal cleanup lock",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 1,
                maxAttempts = 2,
                terminalCleanupAuthorized = true,
                captureAuthorized = false,
            ),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(
                inWar = true,
                resultPageVisible = null,
                attempt = 1,
                maxAttempts = 2,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
            "same-game Power.log terminal proof plus current pixel-owned capture permits only bounded cleanup",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 2,
                maxAttempts = 2,
                clickAttempts = 1,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 3,
                maxAttempts = 2,
                clickAttempts = 2,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
            "dispatching click/Enter is not confirmation; after two inputs the worker stops unless a destination is observed",
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 3,
                maxAttempts = 2,
                clickAttempts = 2,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
            "only an observed post-result destination completes the cleanup",
        )
    }
}
