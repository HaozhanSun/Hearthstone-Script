package club.xiaojiawei.hsscript.ocr

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscriptbase.config.log
import java.awt.image.BufferedImage

object OcrRuntime {

    internal var settingsProvider: () -> PaddleXOcrSettings = PaddleXOcrSettings::fromConfig
    internal var paddleXBridgeFactory: (PaddleXOcrSettings) -> OcrTextBridge = { PaddleXOcrSidecarBridge(it) }
    internal var providerModeProvider: () -> OcrProviderMode = OcrProviderMode::fromConfig

    fun currentProvider(): OcrProviderKind =
        if (currentMode().usesPaddleX) OcrProviderKind.PADDLEX else OcrProviderKind.LEGACY

    fun currentMode(): OcrProviderMode = providerModeProvider()

    fun isLegacySelected(): Boolean = currentProvider() == OcrProviderKind.LEGACY

    fun recognize(
        image: BufferedImage?,
        desc: String = "",
        legacyOcr: () -> String,
    ): String {
        if (image == null) {
            log.info {
                "OCR_PROVIDER_USED mode=${currentMode()} provider=LEGACY reason=null-image desc=${desc.ifBlank { "<none>" }}"
            }
            return legacyOcr()
        }
        val mode = currentMode()
        if (mode == OcrProviderMode.LEGACY_ONLY) {
            val text = legacyOcrChecked(desc, legacyOcr)
            log.info {
                "OCR_PROVIDER_USED mode=$mode provider=LEGACY reason=legacy-selected " +
                    "desc=${desc.ifBlank { "<none>" }} chars=${text.length}"
            }
            return text
        }
        val settings = settingsProvider()
        return runCatching {
            val text = paddleXBridgeFactory(settings).recognize(image, desc)
            requireRecognizedText(text, "PADDLEX", desc)
            text
        }.getOrElse { error ->
            if (mode.allowsLegacyFallback) {
                log.warn(error) {
                    "PADDLEX_FALLBACK_TO_LEGACY mode=$mode reason=${error.javaClass.simpleName} " +
                        "configKey=${ConfigEnum.OCR_PROVIDER_MODE.name} desc=${desc.ifBlank { "<none>" }} " +
                        "python=${settings.pythonExecutable} modulePath=${settings.modulePath} " +
                        "device=${settings.device} modelCache=${settings.modelCachePath.ifBlank { "<paddlex-default>" }} " +
                        "timeoutMs=${settings.timeoutMs}"
                }
                return legacyOcrChecked(desc, legacyOcr).also { text ->
                    log.info {
                        "OCR_PROVIDER_USED mode=$mode provider=LEGACY reason=paddlex-fallback " +
                            "desc=${desc.ifBlank { "<none>" }} chars=${text.length}"
                    }
                }
            }
            log.warn(error) {
                "OCR_PROVIDER_FAILED mode=$mode provider=PADDLEX fallback=false " +
                    "configKey=${ConfigEnum.OCR_PROVIDER_MODE.name} desc=${desc.ifBlank { "<none>" }} " +
                    "python=${settings.pythonExecutable} modulePath=${settings.modulePath} " +
                    "device=${settings.device} modelCache=${settings.modelCachePath.ifBlank { "<paddlex-default>" }} " +
                    "timeoutMs=${settings.timeoutMs}"
            }
            throw error
        }
    }

    fun logStartupProvider() {
        val settings = settingsProvider()
        val mode = currentMode()
        val provider = if (mode.usesPaddleX) OcrProviderKind.PADDLEX else OcrProviderKind.LEGACY
        log.info {
            "OCR_PROVIDER_MODE mode=$mode configKey=${ConfigEnum.OCR_PROVIDER_MODE.name} " +
                "default=${ConfigEnum.OCR_PROVIDER_MODE.defaultValue} compatibilitySwitch=${ConfigEnum.USE_PADDLEX_OCR.name}=" +
                    ConfigUtil.getBoolean(ConfigEnum.USE_PADDLEX_OCR)
        }
        log.info {
            "OCR_PROVIDER_SELECTED mode=$mode provider=$provider " +
                "configKey=${ConfigEnum.OCR_PROVIDER_MODE.name} default=${ConfigEnum.OCR_PROVIDER_MODE.defaultValue} " +
                "python=${settings.pythonExecutable} modulePath=${settings.modulePath} " +
                "device=${settings.device} modelCache=${settings.modelCachePath.ifBlank { "<paddlex-default>" }} " +
                "timeoutMs=${settings.timeoutMs}"
        }
        if (provider == OcrProviderKind.LEGACY) return

        val health = paddleXBridgeFactory(settings).healthCheck()
        val message = "OCR_PROVIDER_HEALTH provider=${health.provider} ok=${health.ok} " +
            "message=${health.message} details=${health.details}"
        if (health.ok) {
            log.info { message }
        } else {
            log.warn { message }
        }
    }

    private fun legacyOcrChecked(desc: String, legacyOcr: () -> String): String {
        val text = legacyOcr()
        requireRecognizedText(text, "LEGACY", desc)
        return text
    }

    private fun requireRecognizedText(text: String, provider: String, desc: String) {
        if (text.isBlank()) {
            throw IllegalStateException(
                "OCR result rejected provider=$provider reason=empty desc=${desc.ifBlank { "<none>" }}"
            )
        }
    }
}
