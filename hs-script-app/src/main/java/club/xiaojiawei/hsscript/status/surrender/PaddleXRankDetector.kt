package club.xiaojiawei.hsscript.status.surrender

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Optional PaddleX sidecar for the small numeric rank badge.
 *
 * PaddleX is intentionally not a Maven dependency: its Python runtime and
 * model weights are too large for the application bundle. The sidecar is
 * enabled only when HS_SCRIPT_RANK_OCR_ENGINE=paddlex (or the equivalent JVM
 * property) and falls back to the existing Tesseract path on any failure.
 */
internal object PaddleXRankDetector {
    private const val ENGINE_PROPERTY = "hs.script.rank-ocr-engine"
    private const val PYTHON_PROPERTY = "hs.script.paddlex.python"
    // PaddleX may initialize four cached CPU models on the first call. Keep
    // this bounded, but long enough for a cold start on this machine.
    private const val TIMEOUT_SECONDS = 120L
    private const val BRIDGE_RESOURCE = "/paddlex/rank_bridge.py"
    private val mapper = jacksonObjectMapper()
    private var extractedBridge: Path? = null

    data class Result(val rank: Int?, val rawText: String)

    fun isEnabled(): Boolean = configured("$ENGINE_PROPERTY", "HS_SCRIPT_RANK_OCR_ENGINE")
        .equals("paddlex", ignoreCase = true)

    fun detect(image: BufferedImage): Result? = runCatching {
        detectInternal(image)
    }.getOrNull()

    private fun detectInternal(image: BufferedImage): Result? {
        if (!isEnabled()) return null
        val python = configured(PYTHON_PROPERTY, "HS_SCRIPT_PADDLEX_PYTHON")
            .takeUnless(String::isBlank) ?: return null
        val input = Files.createTempFile("hs-script-rank-", ".png")
        val outputFile = Files.createTempFile("hs-script-rank-paddlex-", ".jsonl")
        return try {
            ImageIO.write(image, "png", input.toFile())
            val bridge = bridgePath() ?: return null
            val process = ProcessBuilder(
                python,
                bridge.toString(),
                "--input",
                input.toString(),
            ).redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(outputFile.toFile())
                .start()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            parsePayload(Files.readString(outputFile, StandardCharsets.UTF_8))
        } finally {
            Files.deleteIfExists(input)
            Files.deleteIfExists(outputFile)
        }
    }

    internal fun parsePayload(output: String): Result? = runCatching {
        val json = output.lineSequence().map(String::trim).lastOrNull { it.startsWith("{") }
            ?: return null
        val node = mapper.readTree(json)
        val rankNode = node.path("rank")
        val rank = rankNode.takeUnless(JsonNode::isNull)?.asInt()?.takeIf { it in 1..10 }
        Result(rank, node.path("raw_text").asText(""))
    }.getOrNull()

    private fun bridgePath(): Path? {
        extractedBridge?.let { if (Files.isRegularFile(it)) return it }
        val resource = PaddleXRankDetector::class.java.getResourceAsStream(BRIDGE_RESOURCE) ?: return null
        val path = Files.createTempFile("hs-script-paddlex-rank-bridge-", ".py")
        resource.use { input -> Files.copy(input, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        path.toFile().deleteOnExit()
        extractedBridge = path
        return path
    }

    private fun configured(property: String, environment: String): String =
        System.getProperty(property)?.takeUnless(String::isBlank)
            ?: System.getenv(environment).orEmpty()
}
