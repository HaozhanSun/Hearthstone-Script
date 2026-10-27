package club.xiaojiawei.hsscript.status

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal data class ResultProbeCaptureTimings(
    val authorizedCaptureMs: Long = 0L,
    val screenshotSaveMs: Long = 0L,
    val visualAnalysisMs: Long = 0L,
)

internal data class ResultProbeOcrTimings(
    val targetedMs: Long = 0L,
    val secondaryMs: Long = 0L,
    val provider: String = "LEGACY_TESSERACT",
    val path: String = "NOT_RUN",
    val providerQueueMs: Long? = null,
    val totalMs: Long = 0L,
)

/** Timing fields for the result-screen observer; telemetry does not authorize or alter any action. */
internal object ResultScreenProbeTelemetry {
    private val sequence = AtomicLong(0L)

    fun nextProbeId(): Long = sequence.incrementAndGet()

    fun elapsedMs(startedAtNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedAtNanos).coerceAtLeast(0L))

    fun format(
        probeId: Long,
        windowResolveMs: Long,
        capture: ResultProbeCaptureTimings,
        ocr: ResultProbeOcrTimings,
        decisionMs: Long,
        totalMs: Long,
        resultVisible: Boolean?,
        rankProgressVisible: Boolean,
        destinationScreen: String?,
        captureAuthorized: Boolean,
        continueControlVisible: Boolean,
    ): String = "probeId=$probeId windowResolveMs=$windowResolveMs " +
        "authorizedCaptureMs=${capture.authorizedCaptureMs} screenshotSaveMs=${capture.screenshotSaveMs} " +
        "visualAnalysisMs=${capture.visualAnalysisMs} " +
        "ocrProvider=${ocr.provider} providerQueueMs=${ocr.providerQueueMs?.toString() ?: "NA"} " +
        "ocrPath=${ocr.path} ocrTotalMs=${ocr.totalMs} " +
        "ocrTargetedMs=${ocr.targetedMs} ocrSecondaryMs=${ocr.secondaryMs} " +
        "decisionMs=$decisionMs totalMs=$totalMs result=${resultVisible ?: "UNKNOWN"} " +
        "rankProgress=$rankProgressVisible destination=${destinationScreen ?: "UNKNOWN"} " +
        "captureAuthorized=$captureAuthorized continueBand=$continueControlVisible"

    fun formatScreenRecovery(
        probeId: Long,
        capture: ResultProbeCaptureTimings,
        ocr: ResultProbeOcrTimings,
        decisionMs: Long,
        totalMs: Long,
        screenKind: String?,
        confidence: Int,
        evidence: String?,
        captureAuthorized: Boolean,
    ): String = "probeId=$probeId authorizedCaptureMs=${capture.authorizedCaptureMs} " +
        "screenshotSaveMs=${capture.screenshotSaveMs} visualAnalysisMs=${capture.visualAnalysisMs} " +
        "ocrProvider=${ocr.provider} ocrPath=${ocr.path} ocrTotalMs=${ocr.totalMs} " +
        "ocrTargetedMs=${ocr.targetedMs} ocrSecondaryMs=${ocr.secondaryMs} " +
        "decisionMs=$decisionMs totalMs=$totalMs screen=${screenKind ?: "UNKNOWN"} " +
        "confidence=$confidence evidence=${evidence ?: "none"} captureAuthorized=$captureAuthorized"
}
