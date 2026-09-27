package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.starter.GameStartupHandoffPolicy
import club.xiaojiawei.hsscript.starter.StartupScreenRecoveryPolicy
import club.xiaojiawei.hsscript.strategy.mode.LoginModeActionPolicy
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Deterministic replay of the opaque-overlay + zero-byte session-log startup failure. */
class StartupRecoveryEvidenceReplayTest {
    @Test
    fun `opaque overlay cannot promote OCR or allow speculative startup clicks and timeout stays bounded`() {
        val pixels = ScreenCaptureOcclusionPolicy.evaluate(
            targetHandle = 0xC840B0L,
            captureBounds = Rectangle(0, 0, 1920, 1080),
            layersAboveTarget = listOf(
                ScreenCaptureOcclusionPolicy.Layer(0x6740BEL, visible = true, bounds = Rectangle(0, 0, 1920, 1080)),
            ),
            enumerationComplete = true,
        )
        assertFalse(pixels.accepted)

        val clickAllowed = LoginModeActionPolicy.mayRetryCoordinateAction(
            safeNative = true,
        )
        assertFalse(clickAllowed)

        assertEquals(
            StartupScreenRecoveryPolicy.Decision.FINISHED,
            StartupScreenRecoveryPolicy.decide(
                elapsedMs = StartupScreenRecoveryPolicy.MAX_PROBE_WINDOW_MS,
                noLogProgressMs = 0L,
                normalFlowActive = false,
                initialProbeAttempted = true,
            ),
        )
        assertEquals(
            GameStartupHandoffPolicy.HandshakeTimeoutDecision.AUTOMATIC_PAUSE,
            GameStartupHandoffPolicy.onHandshakeTimeout(
                startupConfirmed = false,
                screenProbeInProgress = false,
            ),
        )
    }
}
