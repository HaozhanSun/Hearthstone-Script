package club.xiaojiawei.hsscript.ocr

import club.xiaojiawei.hsscriptbase.config.log
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

interface OcrTextBridge {
    fun recognize(image: BufferedImage, desc: String = ""): String
    fun healthCheck(): OcrHealth
}

data class OcrHealth(
    val ok: Boolean,
    val provider: OcrProviderKind,
    val message: String,
    val details: String = "",
)

class PaddleXOcrException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class SidecarProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)

fun interface SidecarProcessRunner {
    fun run(
        command: List<String>,
        workingDirectory: File?,
        environment: Map<String, String>,
        timeoutMs: Long,
    ): SidecarProcessResult
}

class PaddleXOcrSidecarBridge(
    private val settings: PaddleXOcrSettings,
    private val processRunner: SidecarProcessRunner = SidecarProcessRunner(::runProcess),
) : OcrTextBridge {

    override fun recognize(image: BufferedImage, desc: String): String {
        val input = writeTempImage(image, desc)
        try {
            val result = processRunner.run(
                command = baseCommand() + listOf(
                    "-m",
                    "paddlex_vision_experiment.cli",
                    "--ocr-only",
                    "--input",
                    input.absolutePath,
                    "--device",
                    settings.device,
                ),
                workingDirectory = File(settings.modulePath).parentFile,
                environment = pythonEnvironment(),
                timeoutMs = settings.timeoutMs,
            )
            if (result.timedOut) {
                throw PaddleXOcrException("PaddleX OCR sidecar timed out after ${settings.timeoutMs}ms")
            }
            if (result.exitCode != 0) {
                throw PaddleXOcrException(
                    "PaddleX OCR sidecar failed exit=${result.exitCode} stderr=${result.stderr.takeForLog()}"
                )
            }
            val text = parseOcrText(result.stdout)
            log.info { "OCR_PROVIDER_USED provider=PADDLEX desc=${desc.ifBlank { "<none>" }} chars=${text.length}" }
            return text
        } finally {
            runCatching { Files.deleteIfExists(input.toPath()) }
        }
    }

    override fun healthCheck(): OcrHealth {
        val moduleDir = File(settings.modulePath)
        if (!moduleDir.isDirectory) {
            return OcrHealth(
                ok = false,
                provider = OcrProviderKind.PADDLEX,
                message = "PaddleX OCR module path is not a directory",
                details = "modulePath=${settings.modulePath}",
            )
        }
        val result = runCatching {
            processRunner.run(
                command = baseCommand() + listOf(
                    "-c",
                    "import paddlex; import paddlex_vision_experiment.cli; print('ok')",
                ),
                workingDirectory = moduleDir.parentFile,
                environment = pythonEnvironment(),
                timeoutMs = minOf(settings.timeoutMs, HEALTH_TIMEOUT_MS),
            )
        }.getOrElse { error ->
            return OcrHealth(
                ok = false,
                provider = OcrProviderKind.PADDLEX,
                message = "PaddleX OCR health check could not start Python",
                details = "python=${settings.pythonExecutable} modulePath=${settings.modulePath} error=${error.message}",
            )
        }
        if (result.timedOut) {
            return OcrHealth(
                ok = false,
                provider = OcrProviderKind.PADDLEX,
                message = "PaddleX OCR health check timed out",
                details = "timeoutMs=${minOf(settings.timeoutMs, HEALTH_TIMEOUT_MS)}",
            )
        }
        if (result.exitCode != 0) {
            return OcrHealth(
                ok = false,
                provider = OcrProviderKind.PADDLEX,
                message = "PaddleX OCR health check failed",
                details = "exit=${result.exitCode} stderr=${result.stderr.takeForLog()}",
            )
        }
        return OcrHealth(
            ok = true,
            provider = OcrProviderKind.PADDLEX,
                message = "PaddleX OCR health check passed",
            details = "python=${settings.pythonExecutable} modulePath=${settings.modulePath} " +
                "device=${settings.device} modelCache=${settings.modelCachePath.ifBlank { "<paddlex-default>" }}",
        )
    }

    private fun baseCommand(): List<String> = listOf(settings.pythonExecutable)

    private fun pythonEnvironment(): Map<String, String> {
        val existing = System.getenv("PYTHONPATH").orEmpty()
        val pythonPath = listOf(settings.modulePath, existing)
            .filter { it.isNotBlank() }
            .joinToString(File.pathSeparator)
        val environment = mutableMapOf(
            "PYTHONPATH" to pythonPath,
            "PYTHONIOENCODING" to "utf-8",
            "PADDLEX_DISABLE_MKLDNN" to System.getenv("PADDLEX_DISABLE_MKLDNN").orEmpty().ifBlank { "1" },
        )
        if (settings.modelCachePath.isNotBlank()) {
            environment["PADDLE_HOME"] = settings.modelCachePath
            environment["PADDLEX_HOME"] = settings.modelCachePath
            environment["PADDLE_PDX_CACHE_HOME"] = settings.modelCachePath
        }
        return environment
    }

    private fun parseOcrText(stdout: String): String {
        val start = stdout.indexOf('{')
        val end = stdout.lastIndexOf('}')
        if (start < 0 || end < start) {
            throw PaddleXOcrException("PaddleX OCR sidecar returned no JSON stdout=${stdout.takeForLog()}")
        }
        val json = stdout.substring(start, end + 1)
        val root = runCatching { objectMapper.readTree(json) }.getOrElse { error ->
            throw PaddleXOcrException("PaddleX OCR sidecar returned invalid JSON stdout=${stdout.takeForLog()}", error)
        }
        val schema = root.path("schema_version").asInt(-1)
        if (schema != 1) {
            throw PaddleXOcrException("PaddleX OCR sidecar returned unsupported schema_version=$schema")
        }
        val textNode = root.get("ocr_text")
        if (textNode == null || !textNode.isTextual) {
            throw PaddleXOcrException("PaddleX OCR sidecar JSON missing text field ocr_text")
        }
        return textNode.asText()
    }

    private fun writeTempImage(image: BufferedImage, desc: String): File {
        val safeDesc = desc
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .lowercase(Locale.ROOT)
            .ifBlank { "ocr" }
            .take(40)
        val file = Files.createTempFile("hs-paddlex-$safeDesc-", ".png").toFile()
        if (!ImageIO.write(image, "png", file)) {
            throw PaddleXOcrException("Could not encode OCR image as PNG for PaddleX sidecar")
        }
        return file
    }

    private fun String.takeForLog(limit: Int = 600): String =
        replace(Regex("\\s+"), " ").trim().let { if (it.length <= limit) it else it.take(limit) + "..." }

    companion object {
        private const val HEALTH_TIMEOUT_MS = 10_000L
        private val objectMapper: ObjectMapper = jacksonObjectMapper()

        private fun runProcess(
            command: List<String>,
            workingDirectory: File?,
            environment: Map<String, String>,
            timeoutMs: Long,
        ): SidecarProcessResult {
            val processBuilder = ProcessBuilder(command)
            workingDirectory?.let { processBuilder.directory(it) }
            processBuilder.environment().putAll(environment)
            val process = processBuilder.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutThread = streamReaderThread(process.inputStream, stdout)
            val stderrThread = streamReaderThread(process.errorStream, stderr)
            stdoutThread.start()
            stderrThread.start()
            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                stdoutThread.join(1_000)
                stderrThread.join(1_000)
                return SidecarProcessResult(-1, stdout.toString(), stderr.toString(), timedOut = true)
            }
            stdoutThread.join(1_000)
            stderrThread.join(1_000)
            return SidecarProcessResult(process.exitValue(), stdout.toString(), stderr.toString())
        }

        private fun streamReaderThread(input: java.io.InputStream, target: StringBuilder): Thread =
            Thread {
                input.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    target.append(reader.readText())
                }
            }.apply {
                isDaemon = true
                name = "paddlex-ocr-sidecar-stream"
            }
    }
}
