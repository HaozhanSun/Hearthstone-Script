package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForegroundCaptureRetryPolicyTest {
    @Test
    fun `foreground confirmation with overlay pixels retries a fresh frame and accepts recovered game pixels`() {
        data class Frame(val foregroundConfirmed: Boolean, val provenanceAccepted: Boolean, val observedPixels: String)
        val observed = listOf(
            Frame(true, false, "opaque-overlay-text"),
            Frame(true, true, "hearthstone-home-controls"),
        )
        var captures = 0
        val outcome = ForegroundCaptureRetryPolicy.run(
            acquire = {
                val frame = observed[captures++]
                ForegroundCaptureRetryPolicy.Attempt(
                    frame.foregroundConfirmed,
                    frame.provenanceAccepted,
                    frame.observedPixels.takeIf { frame.provenanceAccepted },
                )
            },
        )
        assertEquals(2, captures)
        assertEquals("opaque-overlay-text", observed[0].observedPixels)
        assertEquals("hearthstone-home-controls", observed[1].observedPixels)
        assertEquals(ForegroundCaptureRetryPolicy.Decision.ACCEPT, outcome.decision)
        assertEquals("hearthstone-home-controls", outcome.frame)
        val transition = ScreenStateRecovery.recoveryTransitionForTest(
            "传统对战 酒馆战棋 竞技模式",
        )
        assertEquals("HOME", transition?.screen)
        assertEquals(club.xiaojiawei.hsscriptbase.enums.ModeEnum.HUB, transition?.mode)
    }

    @Test
    fun `persistent overlay exhausts bounded recapture without trusting pixels`() {
        var captures = 0
        val outcome = ForegroundCaptureRetryPolicy.run(
            acquire = { _ ->
                captures++
                ForegroundCaptureRetryPolicy.Attempt(true, false, "opaque-overlay")
            },
        )
        assertEquals(ForegroundCaptureRetryPolicy.MAX_ATTEMPTS, captures)
        assertEquals(ForegroundCaptureRetryPolicy.Decision.EXHAUSTED, outcome.decision)
        assertNull(outcome.frame)
    }

    @Test
    fun `unconfirmed focus defers without trusting a successful-looking frame`() {
        assertEquals(
            ForegroundCaptureRetryPolicy.Decision.DEFER_FOREGROUND,
            ForegroundCaptureRetryPolicy.decide(1, foregroundConfirmed = false, captureAccepted = true),
        )
    }
}
