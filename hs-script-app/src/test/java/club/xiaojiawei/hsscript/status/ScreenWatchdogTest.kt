package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.ocr.OcrTextBridge
import club.xiaojiawei.hsscript.ocr.OcrHealth
import club.xiaojiawei.hsscript.ocr.OcrProviderKind
import club.xiaojiawei.hsscript.ocr.OcrProviderMode
import club.xiaojiawei.hsscript.ocr.PaddleXOcrSettings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

class ScreenWatchdogTest {

    private val originalSettingsProvider = OcrRuntime.settingsProvider
    private val originalBridgeFactory = OcrRuntime.paddleXBridgeFactory
    private val originalProviderModeProvider = OcrRuntime.providerModeProvider

    @AfterEach
    fun tearDown() {
        OcrRuntime.settingsProvider = originalSettingsProvider
        OcrRuntime.paddleXBridgeFactory = originalBridgeFactory
        OcrRuntime.providerModeProvider = originalProviderModeProvider
    }

    @Test
    fun `lost result page stops surrender and records loss`() {
        val kind = ScreenWatchdog.classifyForTest("败北 点击继续")
        assertEquals(ScreenWatchdogKind.LOST, kind)
        assertEquals(
            ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECORD_LOSS,
            ScreenWatchdog.decideForTest(kind),
        )
    }

    @Test
    fun `won result page stops surrender and records win`() {
        val kind = ScreenWatchdog.classifyForTest("胜利 点击继续")
        assertEquals(ScreenWatchdogKind.WIN, kind)
        assertEquals(
            ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECORD_WIN,
            ScreenWatchdog.decideForTest(kind),
        )
    }

    @Test
    fun `unknown screen stops surrender without pausing bounded recovery`() {
        val kind = ScreenWatchdog.classifyForTest("一些无法判定的文字")
        assertEquals(ScreenWatchdogKind.UNKNOWN, kind)
        assertEquals(
            ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_CONTINUE_UNKNOWN,
            ScreenWatchdog.decideForTest(kind),
        )
    }

    @Test
    fun `capture failure is explicit and stops surrender`() {
        val observation = ScreenWatchdog.inspectForSurrender(
            state = "test-state",
            attempts = 9,
            captureProvider = { null },
            ocrProvider = { error("should not OCR without capture") },
        )
        assertEquals(ScreenWatchdogKind.CAPTURE_FAILED, observation.kind)
        assertEquals(ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_CONTINUE_UNKNOWN, observation.action)
        assertEquals(null, observation.screenshotPath)
    }

    @Test
    fun `timing gate waits until repeated action threshold`() {
        assertFalse(
            ScreenWatchdog.shouldInspect(
                startedAt = 1_000L,
                attempts = 2,
                now = 2_000L,
                stuckMs = 30_000L,
                maxRetries = 3,
                cooldownMs = 0L,
            ).shouldInspect,
        )
        assertTrue(
            ScreenWatchdog.shouldInspect(
                startedAt = 1_000L,
                attempts = 3,
                now = 40_000L,
                stuckMs = 30_000L,
                maxRetries = 3,
                cooldownMs = 0L,
            ).shouldInspect,
        )
    }

    @Test
    fun `fake PaddleX bridge OCR classifies same captured image`() {
        OcrRuntime.providerModeProvider = { OcrProviderMode.PADDLEX_ONLY }
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(
                enabled = true,
                pythonExecutable = "fake-python",
                modulePath = "fake-module",
                device = "cpu",
                modelCachePath = "",
                timeoutMs = 1000,
            )
        }
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: BufferedImage, desc: String): String = "失败 点击继续"

                override fun healthCheck(): OcrHealth =
                    OcrHealth(true, OcrProviderKind.PADDLEX, "ok", "fake")
            }
        }

        val observation = ScreenWatchdog.inspectForSurrender(
            state = "mode=GAMEPLAY|warPhase=DRAWN_INIT_CARD",
            attempts = 4,
            captureProvider = { BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB) },
        )

        assertEquals("PADDLEX", observation.provider)
        assertEquals(ScreenWatchdogKind.LOST, observation.kind)
        assertEquals(ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECORD_LOSS, observation.action)
    }
}
