package club.xiaojiawei.hsscript.ocr

import club.xiaojiawei.hsscriptbase.config.log
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.imageio.ImageIO

internal fun interface PaddleXOcrSidecarSessionFactory {
    fun start(settings: PaddleXOcrSettings): PaddleXOcrSidecarSession
}

internal interface PaddleXOcrSidecarSession : AutoCloseable {
    fun request(payload: String, timeoutMs: Long): String
}

/**
 * Persistent PaddleX bridge used by the application.  The Python process
 * creates its pipeline before accepting requests and stays alive until the
 * JVM closes this bridge.  A timeout tears down the session so a wedged
 * native runtime cannot leak into the next request.
 */
internal class PersistentPaddleXOcrSidecarBridge(
    private val settings: PaddleXOcrSettings,
    private val sessionFactory: PaddleXOcrSidecarSessionFactory = PaddleXOcrSidecarSessionFactory(::startProcessSession),
) : OcrTextBridge, AutoCloseable {
    private val sessionLock = Any()
    private var session: PaddleXOcrSidecarSession? = null

    override fun recognize(image: BufferedImage, desc: String): String =
        recognizeWithConfidence(image, desc).text

    override fun recognizeWithConfidence(image: BufferedImage, desc: String): OcrRecognition =
        recognizeWithConfidence(image, desc, roi = null)

    override fun recognizeWithConfidence(
        image: BufferedImage,
        desc: String,
        roi: String?,
    ): OcrRecognition {
        val input = writeTempImage(image, desc)
        try {
            return PaddleXOcrRequestCoordinator.execute(desc, roi, settings.timeoutMs) { requestId ->
                val payload = objectMapper.writeValueAsString(
                    mapOf(
                        "request_id" to requestId.toString(),
                        "op" to "ocr",
                        "input" to input.absolutePath,
                        "roi" to (roi ?: ""),
                    ),
                )
                try {
                    val response = objectMapper.readTree(ensureSession().request(payload, settings.timeoutMs))
                    parseResponse(response, requestId)
                } catch (error: Throwable) {
                    // A timed-out/corrupt protocol session is not reusable;
                    // the next request gets a fresh process and pipeline.
                    closeSession()
                    throw error
                }
            }
        } finally {
            runCatching { Files.deleteIfExists(input.toPath()) }
        }
    }

    override fun healthCheck(): OcrHealth {
        val startedAt = System.nanoTime()
        return runCatching {
            PaddleXOcrRequestCoordinator.execute("health", "none", settings.timeoutMs) { requestId ->
                val payload = objectMapper.writeValueAsString(
                    mapOf("request_id" to requestId.toString(), "op" to "health"),
                )
                val response = objectMapper.readTree(ensureSession().request(payload, settings.timeoutMs))
                if (!response.path("ok").asBoolean(false) || response.path("provider").asText() != "PADDLEX") {
                    throw PaddleXOcrException("PaddleX persistent sidecar returned unhealthy response")
                }
            }
            OcrHealth(
                ok = true,
                provider = OcrProviderKind.PADDLEX,
                message = "PaddleX persistent OCR sidecar is ready",
                details = "python=${settings.pythonExecutable} modulePath=${settings.modulePath} " +
                    "device=${settings.device} modelCache=${settings.modelCachePath.ifBlank { "<paddlex-default>" }} " +
                    "startupOrReuseMs=${elapsedMs(startedAt)}",
            )
        }.getOrElse { error ->
            closeSession()
            OcrHealth(
                ok = false,
                provider = OcrProviderKind.PADDLEX,
                message = "PaddleX persistent OCR sidecar is unavailable",
                details = "python=${settings.pythonExecutable} modulePath=${settings.modulePath} " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "no-message"}",
            )
        }
    }

    override fun close() = closeSession()

    private fun ensureSession(): PaddleXOcrSidecarSession = synchronized(sessionLock) {
        session ?: sessionFactory.start(settings).also {
            session = it
            log.info {
                "PADDLEX_OCR_SIDECAR_STARTED provider=PADDLEX persistent=true " +
                    "python=${settings.pythonExecutable} modulePath=${settings.modulePath} " +
                    "device=${settings.device} action=LOAD_PIPELINE_ONCE"
            }
        }
    }

    private fun closeSession() {
        synchronized(sessionLock) {
            session?.let { runCatching { it.close() } }
            session = null
        }
    }

    private fun parseResponse(response: com.fasterxml.jackson.databind.JsonNode, requestId: Long): OcrRecognition {
        if (response.path("request_id").asText() != requestId.toString()) {
            throw PaddleXOcrException("PaddleX sidecar response request_id mismatch")
        }
        if (response.path("ok").isBoolean && !response.path("ok").asBoolean()) {
            throw PaddleXOcrException("PaddleX sidecar request failed: ${response.path("error").asText("unknown")}")
        }
        if (response.path("schema_version").asInt(-1) != 1) {
            throw PaddleXOcrException("PaddleX sidecar returned unsupported schema_version")
        }
        val textNode = response.get("ocr_text")
        if (textNode == null || !textNode.isTextual) {
            throw PaddleXOcrException("PaddleX sidecar JSON missing text field ocr_text")
        }
        val confidence = response.path("ocr_confidence")
            .takeIf { it.isNumber }
            ?.asDouble()
            ?: response.path("texts")
                .takeIf { it.isArray }
                ?.elements()
                ?.asSequence()
                ?.mapNotNull { it.path("score").takeIf { score -> score.isNumber }?.asDouble() }
                ?.maxOrNull()
        log.info {
            "OCR_PROVIDER_USED provider=PADDLEX persistent=true chars=${textNode.asText().length} " +
                "nativeConfidence=${confidence?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "unavailable"}"
        }
        return OcrRecognition(textNode.asText(), confidence)
    }

    private fun writeTempImage(image: BufferedImage, desc: String): File {
        val safeDesc = desc.replace(Regex("[^A-Za-z0-9._-]+"), "-").lowercase(Locale.ROOT)
            .ifBlank { "ocr" }.take(40)
        val file = Files.createTempFile("hs-paddlex-$safeDesc-", ".png").toFile()
        if (!ImageIO.write(image, "png", file)) {
            throw PaddleXOcrException("Could not encode OCR image as PNG for PaddleX sidecar")
        }
        return file
    }

    private fun elapsedMs(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).coerceAtLeast(0L)

    companion object {
        private val objectMapper: ObjectMapper = jacksonObjectMapper()

        private fun startProcessSession(settings: PaddleXOcrSettings): PaddleXOcrSidecarSession {
            val moduleDir = File(settings.modulePath)
            if (!moduleDir.isDirectory) {
                throw PaddleXOcrException("PaddleX module path is not a directory: ${settings.modulePath}")
            }
            val processBuilder = ProcessBuilder(
                settings.pythonExecutable,
                "-m",
                "paddlex_vision_experiment.cli",
                "--server",
                "--device",
                settings.device,
            )
                .directory(moduleDir.parentFile)
            processBuilder.environment().putAll(pythonEnvironment(settings))
            return ProcessPaddleXOcrSidecarSession(processBuilder.start())
        }

        private fun pythonEnvironment(settings: PaddleXOcrSettings): Map<String, String> {
            val existing = System.getenv("PYTHONPATH").orEmpty()
            val pythonPath = listOf(settings.modulePath, existing).filter(String::isNotBlank)
                .joinToString(File.pathSeparator)
            return buildMap {
                put("PYTHONPATH", pythonPath)
                put("PYTHONIOENCODING", "utf-8")
                put("PADDLEX_DISABLE_MKLDNN", System.getenv("PADDLEX_DISABLE_MKLDNN").orEmpty().ifBlank { "1" })
                if (settings.modelCachePath.isNotBlank()) {
                    put("PADDLE_HOME", settings.modelCachePath)
                    put("PADDLEX_HOME", settings.modelCachePath)
                    put("PADDLE_PDX_CACHE_HOME", settings.modelCachePath)
                }
            }
        }
    }
}

private class ProcessPaddleXOcrSidecarSession(
    private val process: Process,
) : PaddleXOcrSidecarSession {
    private val writer: BufferedWriter = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8).buffered()
    private val reader: BufferedReader = InputStreamReader(process.inputStream, StandardCharsets.UTF_8).buffered()
    private val responseExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "paddlex-ocr-response-reader").apply { isDaemon = true }
    }
    private val stderrThread = Thread {
        process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }.apply {
        isDaemon = true
        name = "paddlex-ocr-sidecar-stderr"
        start()
    }

    @Synchronized
    override fun request(payload: String, timeoutMs: Long): String {
        if (!process.isAlive) throw PaddleXOcrException("PaddleX sidecar exited before request")
        writer.write(payload)
        writer.newLine()
        writer.flush()
        val read: Future<String?> = responseExecutor.submit<String?> { reader.readLine() }
        return try {
            read.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                ?: throw PaddleXOcrException("PaddleX sidecar closed stdout")
        } catch (error: TimeoutException) {
            read.cancel(true)
            destroy("request-timeout")
            throw PaddleXOcrException("PaddleX persistent sidecar timed out after ${timeoutMs}ms", error)
        } catch (error: InterruptedException) {
            read.cancel(true)
            destroy("request-cancelled")
            Thread.currentThread().interrupt()
            throw PaddleXOcrCancelledException("PaddleX persistent sidecar request was cancelled", error)
        } catch (error: ExecutionException) {
            destroy("response-read-failed")
            throw PaddleXOcrException("PaddleX persistent sidecar response read failed", error.cause)
        }
    }

    override fun close() = destroy("bridge-close")

    private fun destroy(reason: String) {
        runCatching { writer.close() }
        runCatching { reader.close() }
        if (process.isAlive) process.destroyForcibly()
        responseExecutor.shutdownNow()
        runCatching { responseExecutor.awaitTermination(1, TimeUnit.SECONDS) }
        log.info { "PADDLEX_OCR_SIDECAR_STOPPED provider=PADDLEX reason=$reason" }
    }
}
