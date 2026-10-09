package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.ocr.OcrHealth
import club.xiaojiawei.hsscript.ocr.OcrProviderKind
import club.xiaojiawei.hsscript.ocr.OcrProviderMode
import club.xiaojiawei.hsscript.ocr.OcrRecognition
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.ocr.OcrTextBridge
import club.xiaojiawei.hsscript.ocr.PaddleXOcrSettings
import club.xiaojiawei.hsscript.strategy.mode.PreMatchRankGate
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeckSelectionRankBadgeOcrTest {
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
    fun `live 4x5 badge resolves correctly and blocks pre-match queue`() {
        val screenshot = loadDeckSelectionScreenshot()
        for (rawBadgeText in listOf("4x5", "4 x 5", "4×5")) {
            val requestedRois = mutableListOf<String?>()
            configurePaddleX { _, _, roi, _ ->
                requestedRois += roi
                when (roi) {
                    "rank-badge-small" -> OcrRecognition(rawBadgeText, confidence = 0.99)
                    "rank-badge" -> error("full badge should not be needed for an explicit rank-by-stars token")
                    else -> error("unexpected rank ROI: $roi")
                }
            }

            val detection = requireNotNull(
                CurrentRankDetector.detectCapturedImage(
                    screen = screenshot,
                    saveEvidence = false,
                    evidencePhase = "DECK_SELECTION",
                ),
            )
            assertEquals(listOf<String?>("rank-badge-small"), requestedRois, "raw=$rawBadgeText")
            assertEquals(4, detection.rank ?: error("raw=$rawBadgeText must resolve rank 4 or remain unknown"))
            assertFalse(detection.rank == 5, "the star count must never become rank 5: raw=$rawBadgeText")

            assertEquals(4, detection.rank)
            val gate = PreMatchRankGate.evaluate(preMatchEvidence(detection), nowMs = detection.capturedAtMs)
            assertFalse(gate.queueAuthorization.allowed, "rank 4 / $rawBadgeText must not enter queue")
            assertEquals(null, gate.permit)
        }
    }

    @Test
    fun `tight numeral ROI remains positive control for exact rank five and ten`() {
        val screenshot = loadDeckSelectionScreenshot()
        for (rank in listOf(5, 10)) {
            val requestedRois = mutableListOf<String?>()
            configurePaddleX { _, _, roi, _ ->
                requestedRois += roi
                when (roi) {
                    "rank-badge-small" -> OcrRecognition(rank.toString(), confidence = 0.99)
                    "rank-badge" -> error("full badge should not be needed when numeral OCR succeeds")
                    else -> error("unexpected rank ROI: $roi")
                }
            }

            val detection = requireNotNull(
                CurrentRankDetector.detectCapturedImage(
                    screenshot,
                    saveEvidence = false,
                    evidencePhase = "DECK_SELECTION",
                ),
            )
            assertEquals(rank, detection.rank ?: error("positive control rank=$rank must resolve"))
            val gate = PreMatchRankGate.evaluate(preMatchEvidence(detection), nowMs = detection.capturedAtMs)
            assertTrue(gate.queueAuthorization.allowed, "positive control rank=$rank")
            assertTrue(gate.permit != null, "positive control rank=$rank must issue permit")
            assertEquals(listOf<String?>("rank-badge-small"), requestedRois)
        }
    }

    @Test
    fun `live rank nine with one to three stars never becomes rank ten or enters queue`() {
        // The 2026-10-09 deployed incident captured a real deck-selection
        // frame whose visible badge was rank 9 ×2. PaddleX returned "9x2".
        // The generic visual-ten fallback reinterpreted the two numeric runs
        // as rank 10 and sent a queue click. Exercise the retained live
        // deck-selection screenshot through the same capture path for each
        // known star-count spelling; all must remain an explicit rank nine.
        val screenshot = loadDeckSelectionScreenshot()
        for (rawBadgeText in listOf("9x1", "9x2", "9x3", "9 x 2", "9×2")) {
            val requestedRois = mutableListOf<String?>()
            configurePaddleX { _, _, roi, _ ->
                requestedRois += roi
                when (roi) {
                    "rank-badge-small" -> OcrRecognition(rawBadgeText, confidence = 0.99)
                    "rank-badge" -> error("full badge must not override an explicit rank token: $rawBadgeText")
                    else -> error("unexpected rank ROI: $roi")
                }
            }

            val detection = requireNotNull(
                CurrentRankDetector.detectCapturedImage(
                    screen = screenshot,
                    saveEvidence = false,
                    evidencePhase = "DECK_SELECTION",
                ),
            )
            assertEquals(9, detection.rank, "raw=$rawBadgeText")
            assertFalse(detection.rank == 10, "star count must not promote 9 to 10: raw=$rawBadgeText")
            assertEquals(listOf<String?>("rank-badge-small"), requestedRois, "raw=$rawBadgeText")

            val gate = PreMatchRankGate.evaluate(preMatchEvidence(detection), nowMs = detection.capturedAtMs)
            assertFalse(gate.queueAuthorization.allowed, "rank 9 / $rawBadgeText must not enter queue")
            var queueInputs = 0
            gate.permit?.dispatchIfCurrent(
                PreMatchRankGate.RuntimeEvidence(true, false, false, true, false),
                detection.capturedAtMs,
            ) { queueInputs++ }
            assertEquals(0, queueInputs, "rank 9 / $rawBadgeText must dispatch no queue input")
        }
    }

    private fun preMatchEvidence(detection: CurrentRankDetector.Detection) = PreMatchRankGate.Evidence(
        working = true,
        paused = false,
        mandatoryRankSurrenderPending = false,
        tournamentMode = true,
        inWar = false,
        phase = PreMatchRankGate.REQUIRED_PHASE,
        ocrOutcome = if (detection.rank == null) PreMatchRankGate.OcrOutcome.UNKNOWN else PreMatchRankGate.OcrOutcome.SUCCESS,
        observedRank = detection.rank,
        confidence = detection.confidence,
        capturedAtMs = detection.capturedAtMs,
    )

    private fun configurePaddleX(
        recognize: (BufferedImage, String, String?, Long?) -> OcrRecognition,
    ) {
        OcrRuntime.providerModeProvider = { OcrProviderMode.PADDLEX_ONLY }
        OcrRuntime.settingsProvider = {
            PaddleXOcrSettings(true, "offline-python", "offline-rank-badge", "cpu", "", 1_000)
        }
        OcrRuntime.paddleXBridgeFactory = {
            object : OcrTextBridge {
                override fun recognize(image: BufferedImage, desc: String): String =
                    recognize(image, desc, null, null).text

                override fun recognizeWithConfidence(
                    image: BufferedImage,
                    desc: String,
                    roi: String?,
                    timeoutMs: Long?,
                ): OcrRecognition = recognize(image, desc, roi, timeoutMs)

                override fun healthCheck() = OcrHealth(true, OcrProviderKind.PADDLEX, "offline fixture")
            }
        }
    }

    private fun loadDeckSelectionScreenshot(): BufferedImage {
        val fixture = requireNotNull(
            javaClass.getResourceAsStream("/offline-ocr/rank-detection/deck-selection-rank4-1920x1080.png"),
        ) { "missing retained live deck-selection rank-4 screenshot" }
        return fixture.use { requireNotNull(ImageIO.read(it)) }
    }
}
