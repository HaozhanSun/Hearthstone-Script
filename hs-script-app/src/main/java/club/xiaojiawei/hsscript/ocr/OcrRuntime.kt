package club.xiaojiawei.hsscript.ocr

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscriptbase.config.log
import java.awt.image.BufferedImage

object OcrRuntime {

    internal var settingsProvider: () -> PaddleXOcrSettings = PaddleXOcrSettings::fromConfig
    internal var paddleXBridgeFactory: (PaddleXOcrSettings) -> OcrTextBridge = { PaddleXOcrSidecarBridge(it) }

    fun currentProvider(): OcrProviderKind =
        if (settingsProvider().enabled) OcrProviderKind.PADDLEX else OcrProviderKind.LEGACY

    fun isLegacySelected(): Boolean = currentProvider() == OcrProviderKind.LEGACY

    fun recognize(
        image: BufferedImage?,
        desc: String = "",
        legacyOcr: () -> String,
    ): String {
        if (image == null) {
            log.info { "OCR_PROVIDER_USED provider=LEGACY reason=null-image desc=${desc.ifBlank { "<none>" }}" }
            return legacyOcr()
        }
        val settings = settingsProvider()
        if (!settings.enabled) {
            val text = legacyOcr()
            log.info { "OCR_PROVIDER_USED provider=LEGACY desc=${desc.ifBlank { "<none>" }} chars=${text.length}" }
            return text
        }
        return runCatching {
            paddleXBridgeFactory(settings).recognize(image, desc)
        }.getOrElse { error ->
            log.warn(error) {
                "OCR_PROVIDER_FAILED provider=PADDLEX fallback=false " +
                    "configKey=${ConfigEnum.USE_PADDLEX_OCR.name} desc=${desc.ifBlank { "<none>" }} " +
                    "python=${settings.pythonExecutable} modulePath=${settings.modulePath} " +
                    "device=${settings.device} modelCache=${settings.modelCachePath.ifBlank { "<paddlex-default>" }} " +
                    "timeoutMs=${settings.timeoutMs}"
            }
            throw error
        }
    }

    fun logStartupProvider() {
        val settings = settingsProvider()
        val provider = if (settings.enabled) OcrProviderKind.PADDLEX else OcrProviderKind.LEGACY
        log.info {
            "OCR_PROVIDER_SELECTED provider=$provider " +
                "configKey=${ConfigEnum.USE_PADDLEX_OCR.name} default=${ConfigEnum.USE_PADDLEX_OCR.defaultValue} " +
                "python=${settings.pythonExecutable} modulePath=${settings.modulePath} " +
                "device=${settings.device} modelCache=${settings.modelCachePath.ifBlank { "<paddlex-default>" }} " +
                "timeoutMs=${settings.timeoutMs}"
        }
        if (!settings.enabled) return

        val health = paddleXBridgeFactory(settings).healthCheck()
        val message = "OCR_PROVIDER_HEALTH provider=${health.provider} ok=${health.ok} " +
            "message=${health.message} details=${health.details}"
        if (health.ok) {
            log.info { message }
        } else {
            log.warn { message }
        }
    }
}
