package club.xiaojiawei.hsscript.ocr

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscriptbase.config.log
import java.awt.image.BufferedImage

object OcrRuntime {

    internal var settingsProvider: () -> PaddleXOcrSettings = PaddleXOcrSettings::fromConfig
    internal var paddleXBridgeFactory: (PaddleXOcrSettings) -> OcrTextBridge = { PaddleXOcrSidecarBridge(it) }
    internal var providerModeProvider: () -> OcrProviderMode = OcrProviderMode::fromConfig

    @Volatile
    private var lastProviderUsed: OcrProviderKind = OcrProviderKind.LEGACY

    internal fun lastProviderUsed(): OcrProviderKind = lastProviderUsed

    fun currentProvider(): OcrProviderKind =
        if (currentMode().usesPaddleX) OcrProviderKind.PADDLEX else OcrProviderKind.LEGACY

    fun currentMode(): OcrProviderMode = providerModeProvider()

    fun isLegacySelected(): Boolean = currentProvider() == OcrProviderKind.LEGACY

    fun recognize(
        image: BufferedImage?,
        desc: String = "",
        legacyOcr: () -> String,
    ): String = recognize(image, desc, allowEmptyProbeResult = false, legacyOcr = legacyOcr)

    fun recognize(
        image: BufferedImage?,
        desc: String = "",
        allowEmptyProbeResult: Boolean = false,
        legacyOcr: () -> String,
    ): String = recognizeResult(image, desc, allowEmptyProbeResult, legacyOcr).text

    fun recognizeResult(
        image: BufferedImage?,
        desc: String = "",
        allowEmptyProbeResult: Boolean = false,
        legacyOcr: () -> String,
    ): OcrRecognition {
        if (image == null) {
            lastProviderUsed = OcrProviderKind.LEGACY
            log.info {
                "OCR_PROVIDER_USED mode=${currentMode()} provider=LEGACY reason=null-image desc=${desc.ifBlank { "<none>" }}"
            }
            return OcrRecognition(legacyOcr(), confidence = null)
        }
        val mode = currentMode()
        if (mode == OcrProviderMode.LEGACY_ONLY) {
            lastProviderUsed = OcrProviderKind.LEGACY
            val text = legacyOcr()
            if (text.isBlank() && allowEmptyProbeResult) {
                log.debug {
                    "OCR_PROBE_EMPTY provider=LEGACY reason=expected-empty-probe " +
                        "desc=${desc.ifBlank { "<none>" }}"
                }
                return OcrRecognition(text, confidence = null)
            }
            val checked = legacyOcrChecked(desc) { text }
            log.info {
                "OCR_PROVIDER_USED mode=$mode provider=LEGACY reason=legacy-selected " +
                    "desc=${desc.ifBlank { "<none>" }} chars=${checked.length}"
            }
            return OcrRecognition(checked, confidence = null)
        }
        val settings = settingsProvider()
        return runCatching {
            val recognition = paddleXBridgeFactory(settings).recognizeWithConfidence(image, desc)
            lastProviderUsed = OcrProviderKind.PADDLEX
            if (allowEmptyProbeResult && recognition.text.isBlank()) {
                log.debug {
                    "OCR_PROBE_EMPTY provider=PADDLEX reason=expected-empty-probe " +
                        "desc=${desc.ifBlank { "<none>" }}"
                }
                return@runCatching recognition
            }
            requireRecognizedText(recognition.text, "PADDLEX", desc)
            recognition
        }.getOrElse { error ->
            if (mode.allowsLegacyFallback) {
                lastProviderUsed = OcrProviderKind.LEGACY
                log.warn(error) {
                    "PADDLEX_FALLBACK_TO_LEGACY mode=$mode reason=${error.javaClass.simpleName} " +
                        "configKey=${ConfigEnum.OCR_PROVIDER_MODE.name} desc=${desc.ifBlank { "<none>" }} " +
                        "python=${settings.pythonExecutable} modulePath=${settings.modulePath} " +
                        "device=${settings.device} modelCache=${settings.modelCachePath.ifBlank { "<paddlex-default>" }} " +
                        "timeoutMs=${settings.timeoutMs}"
                }
                return OcrRecognition(legacyOcrChecked(desc, legacyOcr), confidence = null).also { result ->
                    log.info {
                        "OCR_PROVIDER_USED mode=$mode provider=LEGACY reason=paddlex-fallback " +
                            "desc=${desc.ifBlank { "<none>" }} chars=${result.text.length}"
                    }
                }
            }
            lastProviderUsed = OcrProviderKind.PADDLEX
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
