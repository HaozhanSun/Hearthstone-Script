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
    private val originalProviderModeProvider = OcrRuntime.providerModeProvider

    @AfterTest
    fun restoreRuntime() {
        OcrRuntime.settingsProvider = originalSettingsProvider
        OcrRuntime.paddleXBridgeFactory = originalBridgeFactory
        OcrRuntime.providerModeProvider = originalProviderModeProvider
    }

    @Test
    fun defaultConfigUsesAutoPaddleXFirstMode() {
        assertEquals("AUTO", ConfigEnum.OCR_PROVIDER_MODE.defaultValue)
        assertEquals("true", ConfigEnum.USE_PADDLEX_OCR.defaultValue)
    }

    @Test
    fun legacyOnlyUsesLegacyOcrAndDoesNotConstructPaddleXBridge() {
        OcrRuntime.providerModeProvider = { OcrProviderMode.LEGACY_ONLY }
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
        OcrRuntime.paddleXBridgeFactory = {
            error("LEGACY_ONLY must not construct PaddleX sidecar")
        }

        val result = OcrRuntime.recognize(TestImages.onePixel(), "legacy-test") {
            legacyCalled = true
            "legacy-text"
        }

        assertTrue(legacyCalled)
        assertEquals("legacy-text", result)
        assertEquals(OcrProviderKind.LEGACY, OcrRuntime.currentProvider())
    }

    @Test
    fun paddleXOnlyRouteUsesBridgeWithoutLegacyFallback() {
        OcrRuntime.providerModeProvider = { OcrProviderMode.PADDLEX_ONLY }
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

    @Test
    fun paddleXOnlyRemainsAuthoritativeWhenCompatibilitySwitchIsFalse() {
        OcrRuntime.providerModeProvider = { OcrProviderMode.PADDLEX_ONLY }
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(
                enabled = false,
                pythonExecutable = "python",
                modulePath = "fake-module",
                device = "cpu",
                modelCachePath = "",
                timeoutMs = 1000,
            )
        }
        var legacyCalled = false
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: java.awt.image.BufferedImage, desc: String): String = "paddlex-authoritative"

                override fun healthCheck(): OcrHealth =
                    OcrHealth(true, OcrProviderKind.PADDLEX, "ok")
            }
        }

        val result = OcrRuntime.recognize(TestImages.onePixel(), "paddlex-authority-test") {
            legacyCalled = true
            "legacy-must-not-run"
        }

        assertEquals("paddlex-authoritative", result)
        assertFalse(legacyCalled)
        assertEquals(OcrProviderKind.PADDLEX, OcrRuntime.currentProvider())
    }

    @Test
    fun autoFallsBackToLegacyWhenPaddleXFails() {
        OcrRuntime.providerModeProvider = { OcrProviderMode.AUTO }
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
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: java.awt.image.BufferedImage, desc: String): String =
                    error("sidecar unavailable")

                override fun healthCheck(): OcrHealth =
                    OcrHealth(false, OcrProviderKind.PADDLEX, "failed")
            }
        }

        val result = OcrRuntime.recognize(TestImages.onePixel(), "auto-fallback-test") {
            legacyCalled = true
            "legacy-after-failure"
        }

        assertTrue(legacyCalled)
        assertEquals("legacy-after-failure", result)
    }

    @Test
    fun autoFallsBackToLegacyWhenPaddleXReturnsEmptyText() {
        OcrRuntime.providerModeProvider = { OcrProviderMode.AUTO }
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
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: java.awt.image.BufferedImage, desc: String): String = ""

                override fun healthCheck(): OcrHealth =
                    OcrHealth(true, OcrProviderKind.PADDLEX, "ok")
            }
        }

        val result = OcrRuntime.recognize(TestImages.onePixel(), "auto-empty-test") {
            "legacy-after-empty"
        }

        assertEquals("legacy-after-empty", result)
    }

    @Test
    fun autoThrowsWhenBothProvidersFailContract() {
        OcrRuntime.providerModeProvider = { OcrProviderMode.AUTO }
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
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: java.awt.image.BufferedImage, desc: String): String = ""

                override fun healthCheck(): OcrHealth =
                    OcrHealth(true, OcrProviderKind.PADDLEX, "ok")
            }
        }

        kotlin.test.assertFailsWith<IllegalStateException> {
            OcrRuntime.recognize(TestImages.onePixel(), "double-empty-test") {
                ""
            }
        }
    }
}
