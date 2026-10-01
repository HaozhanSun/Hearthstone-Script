package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.ocr.OcrProviderMode
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.ocr.OcrHealth
import club.xiaojiawei.hsscript.ocr.OcrProviderKind
import club.xiaojiawei.hsscript.ocr.OcrTextBridge
import club.xiaojiawei.hsscript.ocr.PaddleXOcrCancelledException
import club.xiaojiawei.hsscript.ocr.PaddleXOcrSettings
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CurrentRankDetectorCancellationTest {

    @Test
    fun `PaddleX cancellation propagates without becoming unknown rank or fallback`() {
        val oldSettingsProvider = OcrRuntime.settingsProvider
        val oldBridgeFactory = OcrRuntime.paddleXBridgeFactory
        val oldModeProvider = OcrRuntime.providerModeProvider
        var providerCalls = 0
        OcrRuntime.providerModeProvider = { OcrProviderMode.PADDLEX_ONLY }
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(
                enabled = true,
                pythonExecutable = "python",
                modulePath = "test-module",
                device = "cpu",
                modelCachePath = "",
                timeoutMs = 10_000L,
            )
        }
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: BufferedImage, desc: String): String {
                    providerCalls++
                    throw PaddleXOcrCancelledException("phase exited")
                }

                override fun healthCheck() = OcrHealth(true, OcrProviderKind.PADDLEX, "ok")
            }
        }

        try {
            assertFailsWith<PaddleXOcrCancelledException> {
                CurrentRankDetector.detectCapturedImage(
                    BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB),
                    saveEvidence = false,
                )
            }
            assertEquals(1, providerCalls, "cancellation must stop before the small-ROI follow-up request")
        } finally {
            OcrRuntime.settingsProvider = oldSettingsProvider
            OcrRuntime.paddleXBridgeFactory = oldBridgeFactory
            OcrRuntime.providerModeProvider = oldModeProvider
        }
    }
}
