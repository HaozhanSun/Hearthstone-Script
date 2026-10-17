package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.ocr.OcrHealth
import club.xiaojiawei.hsscript.ocr.OcrProviderKind
import club.xiaojiawei.hsscript.ocr.OcrProviderMode
import club.xiaojiawei.hsscript.ocr.OcrRecognition
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.ocr.OcrTextBridge
import club.xiaojiawei.hsscript.ocr.PaddleXOcrSettings
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun `lower-left rank badge keeps full context and exact number-only ROI`() {
        assertEquals(Rectangle(0, 885, 105, 108), CurrentRankDetector.rankBadgeBoundsForTest(1920, 1080))
        assertEquals(Rectangle(31, 944, 45, 34), CurrentRankDetector.rankDigitBoundsForTest(1920, 1080))

        for ((width, height) in listOf(2560 to 1440, 1600 to 900, 1280 to 720)) {
            val badge = CurrentRankDetector.rankBadgeBoundsForTest(width, height)
            val digits = CurrentRankDetector.rankDigitBoundsForTest(width, height)
            assertTrue(badge.contains(digits), "$width x $height digit ROI must stay inside the full badge")
            val expectedLeft = (width * 31.0 / 1920.0).toInt()
            val expectedRight = (width * 76.0 / 1920.0).toInt()
            val expectedTop = (height * 944.0 / 1080.0).toInt()
            val expectedBottom = (height * 978.0 / 1080.0).toInt()
            assertEquals(expectedLeft, digits.x)
            assertEquals(expectedTop, digits.y)
            assertEquals(expectedRight - expectedLeft, digits.width)
            assertEquals(expectedBottom - expectedTop, digits.height)
        }
    }

    @Test
    fun `real rank nine and ten evidence use number-only PaddleX ROI without decoration zero`() {
        val fixtures = listOf(
            "/offline-ocr/rank-detection/rank9-paddlex-evidence-20260909.png" to 9,
            "/offline-ocr/rank-detection/rank10-paddlex-evidence-20260909.png" to 10,
        )

        fixtures.forEach { (resourcePath, expectedRank) ->
            val stream = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
                "missing evidence screenshot $resourcePath"
            }
            val screen = stream.use { requireNotNull(ImageIO.read(it)) }
            var fullBadgeCalls = 0
            var digitCalls = 0
            configurePaddleX { image, roi ->
                when (roi) {
                    "rank-badge" -> {
                        fullBadgeCalls++
                        // A decorative number on the badge must not be able
                        // to override the authoritative number-only crop.
                        OcrRecognition("9", confidence = 0.50)
                    }
                    "rank-badge-small" -> {
                        digitCalls++
                        assertEquals(45 * 4, image.width, "rank=$expectedRank digit ROI width")
                        assertEquals(34 * 4, image.height, "rank=$expectedRank digit ROI height")
                        OcrRecognition(expectedRank.toString(), confidence = 0.99)
                    }
                    else -> error("unexpected OCR ROI=$roi")
                }
            }

            val detection = requireNotNull(
                CurrentRankDetector.detectCapturedImage(
                    screen = screen,
                    saveEvidence = false,
                    evidencePhase = "REPLACE_CARD",
                ),
            )
            assertEquals(0, fullBadgeCalls, "rank=$expectedRank full badge OCR must not precede a valid number crop")
            assertEquals(1, digitCalls, "rank=$expectedRank number-only crop must be queried once")
            assertEquals(expectedRank, detection.rank)
            assertEquals(expectedRank.toString(), detection.ocrText)
            assertEquals(0.99, detection.confidence)
        }
    }

    @Test
    fun `narrow rank ten zero is rejected and expanded ROI resolves ten`() {
        val source = javaClass.getResourceAsStream(
            "/offline-ocr/rank-detection/rank10-paddlex-evidence-20260909.png",
        ).use { stream ->
            requireNotNull(stream) { "missing rank-ten evidence screenshot" }
            requireNotNull(ImageIO.read(stream))
        }
        val requestedRois = mutableListOf<String?>()
        configurePaddleX { _, roi ->
            requestedRois += roi
            when (roi) {
                "rank-badge-small" -> OcrRecognition(
                    text = "0",
                    confidence = 0.65,
                    runtimeDetails = "python=fixture model=OCR version=3.7.2",
                )
                "rank-badge" -> OcrRecognition(
                    text = "",
                    confidence = null,
                    runtimeDetails = "python=fixture model=OCR version=3.7.2",
                )
                "rank-badge-expanded" -> OcrRecognition(
                    text = "10",
                    confidence = 1.0,
                    runtimeDetails = "python=fixture model=OCR version=3.7.2",
                )
                else -> error("unexpected OCR ROI=$roi")
            }
        }

        val detection = requireNotNull(
            CurrentRankDetector.detectCapturedImage(
                screen = source,
                saveEvidence = false,
                evidencePhase = "REPLACE_CARD",
            ),
        )

        assertEquals(
            listOf<String?>("rank-badge-small", "rank-badge", "rank-badge-expanded"),
            requestedRois,
        )
        assertEquals(10, detection.rank)
        assertTrue(detection.rank != 0, "PaddleX raw zero must never finalize as rank zero")
        val resolvedRank = requireNotNull(detection.rank)
        assertNull(
            SurrenderPolicy.evaluateCurrentRank(resolvedRank, detection.tier),
            "a corroborated rank 10 must remain eligible and avoid surrender",
        )
    }

    @Test
    fun `production rank ten capture corroborates expanded one as ten`() {
        val source = javaClass.getResourceAsStream(
            "/offline-ocr/rank-detection/rank10-production-paddlex-misread-20261017.png",
        ).use { stream ->
            requireNotNull(stream) { "missing production rank-ten misread capture" }
            requireNotNull(ImageIO.read(stream))
        }
        val digitBounds = CurrentRankDetector.rankDigitBoundsForTest(source.width, source.height)
        assertTrue(
            CurrentRankDetector.looksLikeTwoDigitRank(
                source.getSubimage(digitBounds.x, digitBounds.y, digitBounds.width, digitBounds.height),
            ),
            "the exact production capture must provide the two-digit corroboration",
        )
        configurePaddleX { _, roi ->
            when (roi) {
                "rank-badge-small" -> OcrRecognition("0", confidence = 0.65)
                "rank-badge" -> OcrRecognition("", confidence = null)
                "rank-badge-expanded" -> OcrRecognition("1", confidence = 1.0)
                else -> error("unexpected OCR ROI=$roi")
            }
        }

        val detection = requireNotNull(
            CurrentRankDetector.detectCapturedImage(
                screen = source,
                saveEvidence = false,
                evidencePhase = "REPLACE_CARD",
            ),
        )

        assertEquals(10, detection.rank)
        assertEquals("1", detection.ocrText)
        val resolvedRank = requireNotNull(detection.rank)
        assertNull(
            SurrenderPolicy.evaluateCurrentRank(resolvedRank, detection.tier),
            "the confidently corroborated rank 10 must be allowed",
        )
    }

    @Test
    fun `ambiguous expanded one after zero remains unknown and cannot authorize surrender`() {
        val source = javaClass.getResourceAsStream(
            "/offline-ocr/rank-detection/rank10-production-paddlex-misread-20261017.png",
        ).use { stream ->
            requireNotNull(stream) { "missing production rank-ten misread capture" }
            requireNotNull(ImageIO.read(stream))
        }
        val oldVisualHint = System.getProperty("rank.disable.visual.hint")
        try {
            System.setProperty("rank.disable.visual.hint", "true")
            configurePaddleX { _, roi ->
                when (roi) {
                    "rank-badge-small" -> OcrRecognition("0", confidence = 0.65)
                    "rank-badge" -> OcrRecognition("", confidence = null)
                    "rank-badge-expanded" -> OcrRecognition("1", confidence = 1.0)
                    else -> error("unexpected OCR ROI=$roi")
                }
            }

            val detection = CurrentRankDetector.detectCapturedImage(
                screen = source,
                saveEvidence = false,
                evidencePhase = "REPLACE_CARD",
            )

            assertNull(detection?.rank, "10-vs-1 ambiguity must fail closed")
            val decision = SurrenderPolicy.unresolvedRankDecision(attempts = 3)
            assertFalse(decision.shouldSurrender, "unknown rank must never authorize surrender")
            assertTrue(decision.blocksAutomaticSurrender)
        } finally {
            if (oldVisualHint == null) System.clearProperty("rank.disable.visual.hint")
            else System.setProperty("rank.disable.visual.hint", oldVisualHint)
        }
    }

    @Test
    fun `historical rank nine empty full badge remains fail closed and retains all attempted ROI evidence`() {
        val source = javaClass.getResourceAsStream(
            "/offline-ocr/rank-detection/rank9-paddlex-evidence-20260909.png",
        ).use { stream ->
            requireNotNull(stream) { "missing historical rank-nine evidence screenshot" }
            requireNotNull(ImageIO.read(stream))
        }
        val requestedRois = mutableListOf<String?>()
        val unknownRoot = Files.createTempDirectory("rank-nine-unknown-evidence-").toFile()
        val diagnosticRoot = Files.createTempDirectory("rank-nine-probe-evidence-").toFile()
        val oldUnknownRoot = System.getProperty("hs.script.unknown-state.dir")
        val oldDiagnosticRoot = System.getProperty("hs.script.rank-ocr-diagnostic.dir")
        try {
            System.setProperty("hs.script.unknown-state.dir", unknownRoot.absolutePath)
            System.setProperty("hs.script.rank-ocr-diagnostic.dir", diagnosticRoot.absolutePath)
            configurePaddleX { _, roi ->
                requestedRois += roi
                OcrRecognition(
                    text = "",
                    confidence = null,
                    runtimeDetails = "python=fixture model=OCR version=3.7.2",
                )
            }

            val detection = CurrentRankDetector.detectCapturedImage(
                screen = source,
                saveEvidence = true,
                evidencePhase = "REPLACE_CARD",
            )

            assertNull(detection?.rank, "empty rank-nine evidence must remain fail closed")
            assertEquals(
                listOf<String?>("rank-badge-small", "rank-badge", "rank-badge-expanded"),
                requestedRois,
            )
            val diagnosticFiles = diagnosticRoot.walkTopDown()
                .filter { it.isFile && it.extension == "png" }
                .toList()
            assertEquals(3, diagnosticFiles.size, "each attempted ROI needs retained payload evidence")
            assertTrue(diagnosticFiles.any { it.name.contains("smallRoi") })
            assertTrue(diagnosticFiles.any { it.name.contains("bigRoi") })
            assertTrue(diagnosticFiles.any { it.name.contains("expandedRoi") })
            assertTrue(
                unknownRoot.walkTopDown().any { it.isFile && it.extension == "png" },
                "unknown rank must retain fail-closed full-screen evidence",
            )
        } finally {
            if (oldUnknownRoot == null) System.clearProperty("hs.script.unknown-state.dir")
            else System.setProperty("hs.script.unknown-state.dir", oldUnknownRoot)
            if (oldDiagnosticRoot == null) System.clearProperty("hs.script.rank-ocr-diagnostic.dir")
            else System.setProperty("hs.script.rank-ocr-diagnostic.dir", oldDiagnosticRoot)
            unknownRoot.deleteRecursively()
            diagnosticRoot.deleteRecursively()
        }
    }

    @Test
    fun `unknown probe metadata keeps every attempted ROI output and runtime`() {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val metadata = CurrentRankDetector.formatRankProbeMetadata(
            listOf(
                CurrentRankDetector.RankProbeResult(
                    roi = "smallRoi",
                    bounds = Rectangle(0, 0, 2, 2),
                    scale = 4,
                    rawText = "0",
                    normalizedText = "0",
                    candidate = null,
                    confidence = 0.65,
                    preprocessedImage = image,
                    runtimeDetails = "python=fixture model=OCR version=3.7.2",
                ),
                CurrentRankDetector.RankProbeResult(
                    roi = "bigRoi",
                    bounds = Rectangle(0, 0, 4, 4),
                    scale = 1,
                    rawText = "",
                    normalizedText = "",
                    candidate = null,
                    confidence = null,
                    preprocessedImage = image,
                    runtimeDetails = "python=fixture model=OCR version=3.7.2",
                ),
                CurrentRankDetector.RankProbeResult(
                    roi = "expandedRoi",
                    bounds = Rectangle(0, 0, 5, 5),
                    scale = 4,
                    rawText = "",
                    normalizedText = "",
                    candidate = null,
                    confidence = null,
                    preprocessedImage = image,
                    runtimeDetails = "python=fixture model=OCR version=3.7.2",
                ),
            ),
        )

        assertTrue(metadata.contains("roi=smallRoi,raw=0"))
        assertTrue(metadata.contains("roi=bigRoi,raw=<empty>"))
        assertTrue(metadata.contains("roi=expandedRoi,raw=<empty>"))
        assertEquals(3, Regex("runtime=python=fixture").findAll(metadata).count())
    }

    private fun configurePaddleX(
        recognize: (BufferedImage, String?) -> OcrRecognition,
    ) {
        OcrRuntime.providerModeProvider = { OcrProviderMode.PADDLEX_ONLY }
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(true, "offline-python", "rank-digit-evidence", "cpu", "", 1_000)
        }
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: BufferedImage, desc: String): String =
                    recognize(image, null).text

                override fun recognizeWithConfidence(
                    image: BufferedImage,
                    desc: String,
                    roi: String?,
                    timeoutMs: Long?,
                ): OcrRecognition = recognize(image, roi)

                override fun healthCheck() = OcrHealth(true, OcrProviderKind.PADDLEX, "offline evidence fixture")
            }
        }
    }
}
