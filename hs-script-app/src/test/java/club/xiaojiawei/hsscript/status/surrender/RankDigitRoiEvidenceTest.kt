package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.ocr.OcrHealth
import club.xiaojiawei.hsscript.ocr.OcrProviderKind
import club.xiaojiawei.hsscript.ocr.OcrProviderMode
import club.xiaojiawei.hsscript.ocr.OcrRecognition
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.ocr.OcrTextBridge
import club.xiaojiawei.hsscript.ocr.PaddleXOcrSettings
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RankDigitRoiEvidenceTest {
    private val oldSettingsProvider = OcrRuntime.settingsProvider
    private val oldBridgeFactory = OcrRuntime.paddleXBridgeFactory
    private val oldModeProvider = OcrRuntime.providerModeProvider

    @AfterTest
    fun restoreOcrRuntime() {
        OcrRuntime.settingsProvider = oldSettingsProvider
        OcrRuntime.paddleXBridgeFactory = oldBridgeFactory
        OcrRuntime.providerModeProvider = oldModeProvider
    }

    @Test
    fun `lower-left rank number ROI uses calibrated 45 by 34 bounds`() {
        assertEquals(Rectangle(31, 944, 45, 34), CurrentRankDetector.rankDigitBoundsForTest(1920, 1080))
    }

    @Test
    fun `exact Beta rank nine replay prioritizes numeric crop and resolves nine`() {
        val image = fixture()
        val bounds = CurrentRankDetector.rankDigitBoundsForTest(image.width, image.height)
        val expectedPayload = RankOcrImagePreprocessor.upscale(
            image.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height), 4,
        )
        val requested = mutableListOf<String?>()
        configurePaddleX { payload, roi ->
            requested += roi
            assertEquals("rank-badge-small", roi)
            assertEquals(180, payload.width)
            assertEquals(136, payload.height)
            for (y in 0 until payload.height) for (x in 0 until payload.width) {
                assertEquals(expectedPayload.getRGB(x, y), payload.getRGB(x, y), "payload pixel ($x,$y)")
            }
            OcrRecognition("9", confidence = 0.99999)
        }

        val detection = CurrentRankDetector.detectCapturedImage(image, saveEvidence = false)

        assertEquals(listOf<String?>("rank-badge-small"), requested)
        assertEquals(9, detection?.rank)
        assertEquals(0.99999, detection?.confidence)
    }

    @Test
    fun `exact Beta rank ten live frame uses expanded PaddleX fallback after tight zero`() {
        val stream = requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/rank-detection/rank10-beta-20261022-174238-paddlex-zero.png",
        )) { "missing exact Beta rank-ten zero replay screenshot" }
        val image = stream.use { requireNotNull(ImageIO.read(it)) }
        val requested = mutableListOf<String?>()
        val oldVisualHint = System.getProperty("rank.disable.visual.hint")
        try {
            System.setProperty("rank.disable.visual.hint", "true")
            configurePaddleX { payload, roi ->
                requested += roi
                when (roi) {
                    "rank-badge-small" -> {
                        assertEquals(180, payload.width)
                        assertEquals(136, payload.height)
                        OcrRecognition("0", confidence = 0.6507286)
                    }
                    "rank-badge" -> OcrRecognition("", confidence = null)
                    "rank-badge-expanded" -> {
                        assertEquals(228, payload.width)
                        assertEquals(188, payload.height)
                        OcrRecognition("10", confidence = 0.999)
                    }
                    else -> error("unexpected OCR ROI=$roi")
                }
            }

            val detection = CurrentRankDetector.detectCapturedImage(image, saveEvidence = false)

            assertEquals(
                listOf<String?>("rank-badge-small", "rank-badge", "rank-badge-expanded"),
                requested,
            )
            assertEquals(10, detection?.rank)
            assertTrue(detection?.rank != 0, "tight-ROI zero must never finalize as rank zero")
        } finally {
            if (oldVisualHint == null) System.clearProperty("rank.disable.visual.hint")
            else System.setProperty("rank.disable.visual.hint", oldVisualHint)
        }
    }

    @Test
    fun `exact Beta replay with empty OCR remains unknown and fail closed`() {
        val requested = mutableListOf<String?>()
        configurePaddleX { _, roi ->
            requested += roi
            OcrRecognition("", confidence = null)
        }

        val detection = CurrentRankDetector.detectCapturedImage(fixture(), saveEvidence = false)

        assertNull(detection?.rank)
        assertEquals(listOf<String?>("rank-badge-small", "rank-badge", "rank-badge-expanded"), requested)
        val decision = SurrenderPolicy.unresolvedRankDecision(attempts = 3)
        assertTrue(decision.shouldSurrender, "unresolved in-game rank must retain fail-closed surrender handling")
        assertFalse(decision.blocksAutomaticSurrender)
        assertTrue(
            MatchmakingGuardPolicy.runtimeAllowsInput(working = true, paused = false),
            "rank OCR must not add a pre-game matchmaking gate",
        )
    }

    private fun fixture(): BufferedImage {
        val stream = requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/rank-detection/rank9-beta-20261022-164056-paddlex-empty.png",
        )) { "missing exact Beta rank-nine replay screenshot" }
        return stream.use { requireNotNull(ImageIO.read(it)) }
    }

    private fun configurePaddleX(recognize: (BufferedImage, String?) -> OcrRecognition) {
        OcrRuntime.providerModeProvider = { OcrProviderMode.PADDLEX_ONLY }
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(true, "offline-python", "rank-replay", "cpu", "", 1_000)
        }
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: BufferedImage, desc: String): String = recognize(image, null).text
                override fun recognizeWithConfidence(
                    image: BufferedImage,
                    desc: String,
                    roi: String?,
                    timeoutMs: Long?,
                ): OcrRecognition = recognize(image, roi)
                override fun healthCheck() = OcrHealth(true, OcrProviderKind.PADDLEX, "offline replay fixture")
            }
        }
    }
}
