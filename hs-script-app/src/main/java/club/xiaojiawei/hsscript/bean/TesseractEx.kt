package club.xiaojiawei.hsscript.bean

import club.xiaojiawei.hsscript.consts.ROOT_PATH
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.status.BetaArtifactStorage
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.FileUtil
import net.sourceforge.tess4j.Tesseract
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * @author 肖嘉威
 * @date 2025/8/25 10:57
 */
class TesseractEx : Tesseract() {

    private val ocrDir = "ocr_res"

    fun doOCR(
        p0: BufferedImage?,
        desc: String = "",
        allowEmptyProbeResult: Boolean = false,
    ): String {
        if (ConfigUtil.getBoolean(ConfigEnum.SAVE_OCR_IMG)) {
            val legacyDirectory = File(ROOT_PATH, ocrDir)
            val directory = BetaArtifactStorage.directory("ocr-res", legacyDirectory)
            if (!BetaArtifactStorage.canWrite(directory)) {
                club.xiaojiawei.hsscriptbase.config.log.warn {
                    "OCR_IMAGE_SAVE_SKIPPED reason=artifact-volume-reserve path=${directory.absolutePath}"
                }
            } else {
                val ocrPath = Path(directory.absolutePath)
                if (!ocrPath.exists()) ocrPath.createDirectories()
                ocrPath.toFile().listFiles()?.let {
                    if (it.size > 20) FileUtil.clearDirectory(ocrPath.toFile())
                }
                p0?.let {
                    val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss"))
                    val imageFile = ocrPath.resolve("${time}_${desc}_${UUID.randomUUID()}.png").toFile()
                    runCatching { ImageIO.write(it, "png", imageFile) }
                        .onFailure { error ->
                            club.xiaojiawei.hsscriptbase.config.log.warn(error) {
                                "OCR_IMAGE_SAVE_FAILED path=${imageFile.absolutePath}"
                            }
                        }
                }
            }
        }
        return OcrRuntime.recognize(
            p0,
            desc,
            legacyOcr = { doLegacyOCR(p0) },
            allowEmptyProbeResult = allowEmptyProbeResult,
        )
    }

    private fun doLegacyOCR(image: BufferedImage?): String = super.doOCR(image)
}
