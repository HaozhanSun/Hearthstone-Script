package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResultScreenProbeTelemetryTest {
    @Test
    fun `elapsed time is nonnegative and probe ids increase`() {
        val started = System.nanoTime()
        assertTrue(ResultScreenProbeTelemetry.elapsedMs(started) >= 0L)
        val first = ResultScreenProbeTelemetry.nextProbeId()
        val second = ResultScreenProbeTelemetry.nextProbeId()
        assertTrue(second > first)
    }

    @Test
    fun `screen timing record keeps capture OCR failure and detection stages distinct`() {
        val line = ResultScreenProbeTelemetry.formatScreenRecovery(
            probeId = 12L,
            capture = ResultProbeCaptureTimings(authorizedCaptureMs = 48L, screenshotSaveMs = 2L, visualAnalysisMs = 7L),
            ocr = ResultProbeOcrTimings(
                targetedMs = 431L,
                provider = "LEGACY_TESSERACT",
                path = "FAILED",
                totalMs = 431L,
            ),
            decisionMs = 1L,
            totalMs = 489L,
            screenKind = null,
            confidence = 0,
            evidence = null,
            captureAuthorized = true,
        )

        assertTrue(line.contains("screenshotSaveMs=2"))
        assertTrue(line.contains("ocrTotalMs=431"))
        assertTrue(line.contains("ocrPath=FAILED"))
        assertTrue(line.contains("totalMs=489"))
        assertTrue(line.contains("screen=UNKNOWN"))
    }
}
