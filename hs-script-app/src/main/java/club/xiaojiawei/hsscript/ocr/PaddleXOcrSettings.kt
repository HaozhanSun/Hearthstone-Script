package club.xiaojiawei.hsscript.ocr

import club.xiaojiawei.hsscript.consts.PADDLEX_VISION_PATH
import club.xiaojiawei.hsscript.consts.ROOT_PATH
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.getBoolean
import club.xiaojiawei.hsscript.utils.getLong
import club.xiaojiawei.hsscript.utils.getString
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

data class PaddleXOcrSettings(
    val enabled: Boolean,
    val pythonExecutable: String,
    val modulePath: String,
    val device: String,
    val modelCachePath: String,
    val timeoutMs: Long,
) {
    companion object {
        const val DEFAULT_PYTHON_EXECUTABLE = "python"
        const val DEFAULT_DEVICE = "cpu"
        const val DEFAULT_TIMEOUT_MS = 120_000L

        fun fromConfig(): PaddleXOcrSettings {
            return PaddleXOcrSettings(
                // OCR_PROVIDER_MODE is authoritative. USE_PADDLEX_OCR remains
                // as a compatibility/audit key but must not override an
                // explicit PADDLEX_ONLY selection.
                enabled = OcrProviderMode.fromConfig().usesPaddleX,
                pythonExecutable = ConfigEnum.PADDLEX_OCR_PYTHON.getString()
                    .ifBlank { System.getenv("PADDLEX_OCR_PYTHON").orEmpty() }
                    .ifBlank { defaultPythonExecutable() },
                modulePath = ConfigEnum.PADDLEX_OCR_MODULE_PATH.getString()
                    .ifBlank { System.getenv("PADDLEX_OCR_MODULE_PATH").orEmpty() }
                    .ifBlank { defaultModulePath() },
                device = ConfigEnum.PADDLEX_OCR_DEVICE.getString()
                    .ifBlank { System.getenv("PADDLEX_OCR_DEVICE").orEmpty() }
                    .ifBlank { DEFAULT_DEVICE },
                modelCachePath = ConfigEnum.PADDLEX_OCR_MODEL_CACHE.getString()
                    .ifBlank { System.getenv("PADDLEX_OCR_MODEL_CACHE").orEmpty() }
                    .ifBlank { defaultModelCachePath() },
                timeoutMs = ConfigEnum.PADDLEX_OCR_TIMEOUT_MS.getLong()
                    .takeIf { it > 0L }
                    ?: DEFAULT_TIMEOUT_MS,
            )
        }

        private fun defaultModulePath(): String {
            val packaged = Path.of(PADDLEX_VISION_PATH)
            if (packaged.exists()) return packaged.toString()
            val experiment = Path.of(ROOT_PATH, "experiments", "paddlex-vision", "src")
            return if (experiment.exists()) experiment.toString() else packaged.toString()
        }

        private fun defaultPythonExecutable(): String {
            val home = System.getProperty("user.home").orEmpty()
            if (home.isBlank()) return DEFAULT_PYTHON_EXECUTABLE
            val candidates = if (System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)) {
                listOf(
                    Path.of(home, ".codex", "paddlex-ocr-venv", "Scripts", "python.exe"),
                    Path.of(home, ".venv", "Scripts", "python.exe"),
                )
            } else {
                listOf(
                    Path.of(home, ".codex", "paddlex-ocr-venv", "bin", "python"),
                    Path.of(home, ".venv", "bin", "python"),
                )
            }
            return candidates.firstOrNull { it.isRegularFile() }?.toString() ?: DEFAULT_PYTHON_EXECUTABLE
        }

        private fun defaultModelCachePath(): String {
            val home = System.getProperty("user.home").orEmpty()
            return if (home.isBlank()) "" else Path.of(home, ".cache", "paddlex-ocr-models").toString()
        }
    }
}
