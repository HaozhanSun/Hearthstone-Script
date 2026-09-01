package club.xiaojiawei.hsscript.ocr

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.status.ScreenWatchdog
import club.xiaojiawei.hsscript.status.ScreenWatchdogKind
import java.awt.image.BufferedImage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OfflineOcrReplayTest {

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
    fun fixtureCoversRequiredStatesAndAuditableEvidence() {
        val fixture = loadFixture()
        val states = fixture.frames.map { it.expectedScreenState }.toSet()
        val required = setOf(
            "HUB", "MATCHING", "MULLIGAN", "RANK_BADGE", "GAMEPLAY", "WIN",
            "LOST", "DEFEAT", "RESULT", "UNKNOWN", "RECONNECT", "ERROR_DIALOG",
        )

        assertTrue(required.all(states::contains), "missing states=${required - states}")
        fixture.frames.forEach { frame ->
            assertTrue(frame.screenshotPath.isNotBlank(), "${frame.id} has no screenshot path")
            assertTrue(frame.sourceRunId.isNotBlank(), "${frame.id} has no run lineage")
            assertTrue(frame.sourceLogPath.isNotBlank(), "${frame.id} has no source log")
            assertTrue(frame.provider in setOf("PADDLEX", "LEGACY"), "${frame.id} provider")
            assertTrue(frame.contract in setOf("ACCEPTED", "EXPECTED_EMPTY_PROBE", "REJECTED", "ERROR"))
            assertTrue(frame.confidence in 0..100, "${frame.id} confidence")
        }
    }

    @Test
    fun replayedScreenTextPreservesWatchdogClassificationAndFailClosedBoundary() {
        loadFixture().frames.forEach { frame ->
            val actual = ScreenWatchdog.classifyForTest(frame.ocrText)
            assertEquals(ScreenWatchdogKind.valueOf(frame.expectedWatchdogKind), actual, frame.id)
            val shouldPause = ScreenWatchdog.decideForTest(actual) ==
                club.xiaojiawei.hsscript.status.ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_PAUSE_UNKNOWN
            assertEquals(frame.expectedFailClosed, shouldPause, frame.id)
        }
    }

    @Test
    fun providerProbeReplayUsesPaddleXWhenContractIsValid() {
        val probe = loadFixture().providerProbes.first { it.id == "paddlex-valid-contract" }
        var legacyCalls = 0
        configureRuntime(OcrProviderMode.PADDLEX_ONLY, probe.bridgeResult)

        val actual = OcrRuntime.recognize(TestImages.onePixel(), probe.desc) {
            legacyCalls++
            probe.legacyResult
        }

        assertEquals(probe.expectedResult, actual)
        assertEquals(probe.expectedLegacyCalls, legacyCalls)
    }

    @Test
    fun expectedEmptyRankProbeDoesNotBecomeProviderFailure() {
        val probe = loadFixture().providerProbes.first { it.id == "paddlex-empty-rank-probe" }
        var legacyCalls = 0
        configureRuntime(OcrProviderMode.AUTO, probe.bridgeResult)

        val actual = OcrRuntime.recognize(
            TestImages.onePixel(),
            probe.desc,
            allowEmptyProbeResult = true,
        ) {
            legacyCalls++
            probe.legacyResult
        }

        assertEquals(probe.expectedResult, actual)
        assertEquals(probe.expectedLegacyCalls, legacyCalls)
    }

    @Test
    fun ordinaryEmptyAutoFallsBackButPaddleXOnlyFailsClosed() {
        val auto = loadFixture().providerProbes.first { it.id == "paddlex-empty-ordinary-auto" }
        var autoLegacyCalls = 0
        configureRuntime(OcrProviderMode.AUTO, auto.bridgeResult)
        assertEquals(auto.expectedResult, OcrRuntime.recognize(TestImages.onePixel(), auto.desc) {
            autoLegacyCalls++
            auto.legacyResult
        })
        assertEquals(auto.expectedLegacyCalls, autoLegacyCalls)

        val only = loadFixture().providerProbes.first { it.id == "paddlex-empty-ordinary-only" }
        var onlyLegacyCalls = 0
        configureRuntime(OcrProviderMode.PADDLEX_ONLY, only.bridgeResult)
        assertFailsWith<IllegalStateException> {
            OcrRuntime.recognize(TestImages.onePixel(), only.desc) {
                onlyLegacyCalls++
                only.legacyResult
            }
        }
        assertEquals(only.expectedLegacyCalls, onlyLegacyCalls)
    }

    @Test
    fun sidecarErrorAutoFallsBackWithExplicitContractCase() {
        val probe = loadFixture().providerProbes.first { it.id == "paddlex-timeout-auto" }
        var legacyCalls = 0
        configureRuntime(OcrProviderMode.AUTO, probe.bridgeResult)

        val actual = OcrRuntime.recognize(TestImages.onePixel(), probe.desc) {
            legacyCalls++
            probe.legacyResult
        }

        assertEquals(probe.expectedResult, actual)
        assertEquals(probe.expectedLegacyCalls, legacyCalls)
    }

    @Test
    fun defaultConfigurationStillAdvertisesPaddleXFirst() {
        assertEquals("AUTO", ConfigEnum.OCR_PROVIDER_MODE.defaultValue)
        assertEquals("true", ConfigEnum.USE_PADDLEX_OCR.defaultValue)
    }

    private fun configureRuntime(mode: OcrProviderMode, bridgeText: String) {
        OcrRuntime.providerModeProvider = { mode }
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(true, "python", "offline-fake-module", "cpu", "", 1000)
        }
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: BufferedImage, desc: String): String {
                    if (bridgeText == "__THROW__") error("fake-sidecar-timeout")
                    return bridgeText
                }

                override fun healthCheck(): OcrHealth =
                    OcrHealth(true, OcrProviderKind.PADDLEX, "offline-fake")
            }
        }
    }

    private fun loadFixture(): OfflineOcrFixture =
        OfflineOcrFixture::class.java.getResourceAsStream("/offline-ocr/replay-fixtures.json")!!
            .bufferedReader()
            .use { reader -> Json.decodeFromString(reader.readText()) }

    @Serializable
    private data class OfflineOcrFixture(
        val schemaVersion: Int,
        val description: String,
        val frames: List<Frame>,
        val providerProbes: List<ProviderProbe>,
    )

    @Serializable
    private data class Frame(
        val id: String,
        val sequence: Int,
        val screenshotPath: String,
        val sourceRunId: String,
        val sourceLogPath: String,
        val evidenceKind: String,
        val provider: String,
        val ocrText: String,
        val contract: String,
        val confidence: Int,
        val expectedScreenState: String,
        val expectedWatchdogKind: String,
        val expectedFailClosed: Boolean,
    )

    @Serializable
    private data class ProviderProbe(
        val id: String,
        val mode: String,
        val provider: String,
        val desc: String,
        val bridgeResult: String,
        val legacyResult: String,
        val contract: String,
        val expectedResult: String,
        val expectedLegacyCalls: Int,
        val expectFailure: Boolean,
    )
}
