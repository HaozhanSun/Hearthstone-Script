package club.xiaojiawei.hsscript.ocr

import club.xiaojiawei.hsscript.enums.ConfigEnum
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OcrRuntimeTest {

    private val originalSettingsProvider = OcrRuntime.settingsProvider
    private val originalBridgeFactory = OcrRuntime.paddleXBridgeFactory

    @AfterTest
    fun restoreRuntime() {
        OcrRuntime.settingsProvider = originalSettingsProvider
        OcrRuntime.paddleXBridgeFactory = originalBridgeFactory
    }

    @Test
    fun defaultConfigSelectsPaddleX() {
        assertEquals("true", ConfigEnum.USE_PADDLEX_OCR.defaultValue)
    }

    @Test
    fun explicitLegacyUsesLegacyOcr() {
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(
                enabled = false,
                pythonExecutable = "python",
                modulePath = "unused",
                device = "cpu",
                modelCachePath = "",
                timeoutMs = 1000,
            )
        }
        var legacyCalled = false

        val result = OcrRuntime.recognize(TestImages.onePixel(), "legacy-test") {
            legacyCalled = true
            "legacy-text"
        }

        assertTrue(legacyCalled)
        assertEquals("legacy-text", result)
        assertEquals(OcrProviderKind.LEGACY, OcrRuntime.currentProvider())
    }

    @Test
    fun paddleXRouteUsesBridge() {
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(
                enabled = true,
                pythonExecutable = "python",
                modulePath = "fake-module",
                device = "cpu",
                modelCachePath = "",
                timeoutMs = 1000,
            )
        }
        var legacyCalled = false
        var bridgeCalled = false
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: java.awt.image.BufferedImage, desc: String): String {
                    bridgeCalled = true
                    return "paddlex-text"
                }

                override fun healthCheck(): OcrHealth =
                    OcrHealth(true, OcrProviderKind.PADDLEX, "ok")
            }
        }

        val result = OcrRuntime.recognize(TestImages.onePixel(), "paddlex-test") {
            legacyCalled = true
            "legacy-text"
        }

        assertEquals("paddlex-text", result)
        assertTrue(bridgeCalled)
        assertFalse(legacyCalled)
        assertEquals(OcrProviderKind.PADDLEX, OcrRuntime.currentProvider())
    }
}
