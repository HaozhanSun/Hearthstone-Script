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
        assertTrue(OcrRuntime.lastRecognitionAccepted())
    }

    @Test
    fun legacyRouteDoesNotUsePaddleXScreenMappingContract() {
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(false, "python", "unused", "cpu", "", 1000)
        }
        OcrRuntime.modeProvider = { OcrProviderMode.LEGACY_ONLY }

        val result = OcrRuntime.recognize(TestImages.onePixel(), "legacy-screen-contract", {
            "unmapped legacy text"
        }) { false }

        assertEquals("unmapped legacy text", result)
        assertEquals(OcrProviderKind.LEGACY, OcrRuntime.lastUsedProvider())
        assertTrue(OcrRuntime.lastRecognitionAccepted())
    }

    @Test
    fun configuredModeReconcilesStaleLegacyModeWithVisiblePaddleXSwitch() {
        assertEquals(
            OcrProviderMode.AUTO,
            OcrRuntime.resolveConfiguredMode(OcrProviderMode.LEGACY_ONLY, paddleXEnabled = true),
        )
        assertEquals(
            OcrProviderMode.LEGACY_ONLY,
            OcrRuntime.resolveConfiguredMode(OcrProviderMode.AUTO, paddleXEnabled = false),
        )
        assertEquals(
            OcrProviderMode.PADDLEX_ONLY,
            OcrRuntime.resolveConfiguredMode(OcrProviderMode.PADDLEX_ONLY, paddleXEnabled = false),
        )
    }

    @Test
    fun emptyLegacyProbeIsQuietAndLaterNonEmptyProbeIsAccepted() {
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(false, "python", "unused", "cpu", "", 1000)
        }
        OcrRuntime.modeProvider = { OcrProviderMode.LEGACY_ONLY }

        val empty = OcrRuntime.recognize(
            TestImages.onePixel(),
            "rank-probe-empty",
            legacyOcr = { "" },
            allowEmptyProbeResult = true,
        )
        assertEquals("", empty)
        assertEquals(OcrProviderKind.LEGACY, OcrRuntime.lastUsedProvider())
        assertFalse(OcrRuntime.lastRecognitionAccepted())

        val accepted = OcrRuntime.recognize(
            TestImages.onePixel(),
            "rank-probe-follow-up",
            legacyOcr = { "10" },
            allowEmptyProbeResult = true,
        )
        assertEquals("10", accepted)
        assertTrue(OcrRuntime.lastRecognitionAccepted())
    }

    @Test
    fun emptyPaddleXProbeDoesNotFallbackOrReportProviderFailure() {
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(true, "python", "fake-module", "cpu", "", 1000)
        }
        OcrRuntime.modeProvider = { OcrProviderMode.PADDLEX_ONLY }
        var legacyCalled = false
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: java.awt.image.BufferedImage, desc: String): String = ""

                override fun healthCheck(): OcrHealth = OcrHealth(true, OcrProviderKind.PADDLEX, "ok")
            }
        }

        val result = OcrRuntime.recognize(
            TestImages.onePixel(),
            "paddlex-rank-probe-empty",
            legacyOcr = {
                legacyCalled = true
                "legacy-must-not-run"
            },
            allowEmptyProbeResult = true,
        )

        assertEquals("", result)
        assertFalse(legacyCalled)
        assertEquals(OcrProviderKind.PADDLEX, OcrRuntime.lastUsedProvider())
        assertFalse(OcrRuntime.lastRecognitionAccepted())
    }

    @Test
    fun paddleXProbeExceptionStillFailsClosedWithoutLegacyFallback() {
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(true, "python", "fake-module", "cpu", "", 1000)
        }
        OcrRuntime.modeProvider = { OcrProviderMode.PADDLEX_ONLY }
        var legacyCalled = false
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: java.awt.image.BufferedImage, desc: String): String =
                    throw PaddleXOcrException("sidecar probe failure")

                override fun healthCheck(): OcrHealth = OcrHealth(false, OcrProviderKind.PADDLEX, "failed")
            }
        }

        assertFailsWith<PaddleXOcrException> {
            OcrRuntime.recognize(
                TestImages.onePixel(),
                "paddlex-rank-probe-error",
                legacyOcr = {
                    legacyCalled = true
                    "legacy-must-not-run"
                },
                allowEmptyProbeResult = true,
            )
        }
        assertFalse(legacyCalled)
        assertEquals(OcrProviderKind.PADDLEX, OcrRuntime.lastUsedProvider())
        assertFalse(OcrRuntime.lastRecognitionAccepted())
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
    fun bothProvidersEmptyAreNotReportedAsAccepted() {
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

        val result = OcrRuntime.recognize(TestImages.onePixel(), "both-empty", { "" }) {
            it.contains("known-state")
        }

        assertEquals("", result)
        assertFalse(OcrRuntime.lastRecognitionAccepted())
        assertEquals(null, OcrRuntime.chooseProvider(false, false, false))
        assertEquals(null, OcrRuntime.chooseProvider(true, true, true))
    }
}
