package club.xiaojiawei.hsscript.ocr

import club.xiaojiawei.hsscript.enums.ConfigEnum
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OcrRuntimeTest {

    private val originalSettingsProvider = OcrRuntime.settingsProvider
    private val originalBridgeFactory = OcrRuntime.paddleXBridgeFactory
    private val originalModeProvider = OcrRuntime.modeProvider

    @AfterTest
    fun restoreRuntime() {
        OcrRuntime.settingsProvider = originalSettingsProvider
        OcrRuntime.paddleXBridgeFactory = originalBridgeFactory
        OcrRuntime.modeProvider = originalModeProvider
    }

    @Test
    fun defaultConfigSelectsPaddleX() {
        assertEquals("true", ConfigEnum.USE_PADDLEX_OCR.defaultValue)
        assertEquals("AUTO", ConfigEnum.OCR_PROVIDER_MODE.defaultValue)
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
        OcrRuntime.modeProvider = { OcrProviderMode.LEGACY_ONLY }
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
        OcrRuntime.modeProvider = { OcrProviderMode.AUTO }
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
        assertEquals(OcrProviderKind.PADDLEX, OcrRuntime.lastUsedProvider())
    }

    @Test
    fun paddleXTimeoutFallsBackToLegacy() {
        OcrRuntime.modeProvider = { OcrProviderMode.AUTO }
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(true, "python", "fake-module", "cpu", "", 1000)
        }
        var legacyCalled = false
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: java.awt.image.BufferedImage, desc: String): String {
                    throw PaddleXOcrException("PaddleX OCR sidecar timed out after 1000ms")
                }

                override fun healthCheck(): OcrHealth = OcrHealth(true, OcrProviderKind.PADDLEX, "ok")
            }
        }

        val result = OcrRuntime.recognize(TestImages.onePixel(), "timeout") {
            legacyCalled = true
            "legacy-text"
        }

        assertEquals("legacy-text", result)
        assertTrue(legacyCalled)
        assertEquals(OcrProviderKind.LEGACY, OcrRuntime.lastUsedProvider())
        assertTrue(OcrRuntime.lastRecognitionAccepted())
    }

    @Test
    fun emptyPaddleXResultFallsBackWhenLegacySatisfiesContract() {
        OcrRuntime.modeProvider = { OcrProviderMode.AUTO }
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(true, "python", "fake-module", "cpu", "", 1000)
        }
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: java.awt.image.BufferedImage, desc: String): String = ""

                override fun healthCheck(): OcrHealth = OcrHealth(true, OcrProviderKind.PADDLEX, "ok")
            }
        }

        val result = OcrRuntime.recognize(TestImages.onePixel(), "empty", { "寻找对手" }) {
            it.contains("寻找对手")
        }

        assertEquals("寻找对手", result)
        assertEquals(OcrProviderKind.LEGACY, OcrRuntime.lastUsedProvider())
    }

    @Test
    fun nonEmptyButUnmappedPaddleXResultFallsBackToLegacy() {
        OcrRuntime.modeProvider = { OcrProviderMode.AUTO }
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(true, "python", "fake-module", "cpu", "", 1000)
        }
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: java.awt.image.BufferedImage, desc: String): String = "unmapped"

                override fun healthCheck(): OcrHealth = OcrHealth(true, OcrProviderKind.PADDLEX, "ok")
            }
        }

        val result = OcrRuntime.recognize(TestImages.onePixel(), "unmapped", { "known-state" }) {
            it == "known-state"
        }

        assertEquals("known-state", result)
        assertEquals(OcrProviderKind.LEGACY, OcrRuntime.lastUsedProvider())
        assertTrue(OcrRuntime.lastRecognitionAccepted())
    }

    @Test
    fun paddleXOnlyDoesNotSilentlyFallback() {
        OcrRuntime.modeProvider = { OcrProviderMode.PADDLEX_ONLY }
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(true, "python", "fake-module", "cpu", "", 1000)
        }
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: java.awt.image.BufferedImage, desc: String): String = ""

                override fun healthCheck(): OcrHealth = OcrHealth(true, OcrProviderKind.PADDLEX, "ok")
            }
        }

        var legacyCalled = false
        assertFailsWith<PaddleXOcrException> {
            OcrRuntime.recognize(TestImages.onePixel(), "paddlex-only", {
                legacyCalled = true
                "legacy-text"
            })
        }
        assertFalse(legacyCalled)
    }

    @Test
    fun bothProvidersFailAreNotReportedAsAccepted() {
        OcrRuntime.modeProvider = { OcrProviderMode.AUTO }
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(true, "python", "fake-module", "cpu", "", 1000)
        }
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: java.awt.image.BufferedImage, desc: String): String = ""

                override fun healthCheck(): OcrHealth = OcrHealth(true, OcrProviderKind.PADDLEX, "ok")
            }
        }

        val result = OcrRuntime.recognize(TestImages.onePixel(), "both-fail", { "garbage" }) {
            it.contains("known-state")
        }

        assertEquals("garbage", result)
        assertFalse(OcrRuntime.lastRecognitionAccepted())
        assertEquals(null, OcrRuntime.chooseProvider(false, false, false))
        assertEquals(null, OcrRuntime.chooseProvider(true, true, true))
    }
}
