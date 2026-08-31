package club.xiaojiawei.hsscript.ocr

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.getString
import club.xiaojiawei.hsscriptbase.config.log
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicReference

object OcrRuntime {

    internal var settingsProvider: () -> PaddleXOcrSettings = PaddleXOcrSettings::fromConfig
    internal var paddleXBridgeFactory: (PaddleXOcrSettings) -> OcrTextBridge = { PaddleXOcrSidecarBridge(it) }
    internal var modeProvider: () -> OcrProviderMode = { configuredMode() }

    private val lastProvider = AtomicReference<OcrProviderKind?>(null)
    private val lastContractAccepted = ThreadLocal.withInitial { true }

    fun currentProvider(): OcrProviderKind =
        when (modeProvider()) {
            OcrProviderMode.LEGACY_ONLY -> OcrProviderKind.LEGACY
            OcrProviderMode.AUTO, OcrProviderMode.PADDLEX_ONLY -> OcrProviderKind.PADDLEX
        }

    fun isLegacySelected(): Boolean = modeProvider() == OcrProviderMode.LEGACY_ONLY

    internal fun lastUsedProvider(): OcrProviderKind? = lastProvider.get()

    /** Whether the result returned by the most recent call satisfied its contract. */
    internal fun lastRecognitionAccepted(): Boolean = lastContractAccepted.get()

    /** Shared deterministic choice rule for callers that have both observations. */
    internal fun chooseProvider(
        paddleXAccepted: Boolean,
        legacyAccepted: Boolean,
        conflict: Boolean,
    ): OcrProviderKind? = when {
        conflict -> null
        paddleXAccepted -> OcrProviderKind.PADDLEX
        legacyAccepted -> OcrProviderKind.LEGACY
        else -> null
    }

    /** Preserve the original trailing-lambda call shape used by callers. */
    fun recognize(
        image: BufferedImage?,
        desc: String = "",
        legacyOcr: () -> String,
    ): String = recognize(image, desc, legacyOcr, { it.isNotBlank() })

    fun recognize(
        image: BufferedImage?,
        desc: String = "",
        legacyOcr: () -> String,
        acceptPaddleX: (String) -> Boolean = { it.isNotBlank() },
    ): String {
        lastContractAccepted.set(false)
        val mode = modeProvider()
        if (mode == OcrProviderMode.LEGACY_ONLY) return runLegacy(legacyOcr, desc, "mode=legacy-only", acceptPaddleX)
        if (image == null) {
            if (mode == OcrProviderMode.PADDLEX_ONLY) {
                throw PaddleXOcrException("PaddleX OCR cannot inspect a null image")
            }
            log.warn { "PADDLEX_FALLBACK_TO_LEGACY reason=null-image desc=${desc.ifBlank { "<none>" }}" }
            return runLegacy(legacyOcr, desc, "null-image", acceptPaddleX)
        }
        val settings = settingsProvider()
        if (!settings.enabled) {
            if (mode == OcrProviderMode.PADDLEX_ONLY) {
                throw PaddleXOcrException("PaddleX OCR is disabled by USE_PADDLEX_OCR")
            }
            log.info { "OCR_PROVIDER_SELECTED provider=LEGACY reason=paddlex-disabled desc=${desc.ifBlank { "<none>" }}" }
            return runLegacy(legacyOcr, desc, "paddlex-disabled", acceptPaddleX)
        }

        val paddleXText = runCatching {
            paddleXBridgeFactory(settings).recognize(image, desc)
        }.getOrElse { error ->
            log.warn(error) {
                "OCR_PROVIDER_FAILED provider=PADDLEX fallback=${mode != OcrProviderMode.PADDLEX_ONLY} " +
                    "configKey=${ConfigEnum.USE_PADDLEX_OCR.name} desc=${desc.ifBlank { "<none>" }} " +
                    "python=${settings.pythonExecutable} modulePath=${settings.modulePath} " +
                    "device=${settings.device} modelCache=${settings.modelCachePath.ifBlank { "<paddlex-default>" }} " +
                    "timeoutMs=${settings.timeoutMs}"
            }
            if (mode == OcrProviderMode.PADDLEX_ONLY) throw error
            log.warn { "PADDLEX_FALLBACK_TO_LEGACY reason=${fallbackReason(error)} desc=${desc.ifBlank { "<none>" }}" }
            return runLegacy(legacyOcr, desc, fallbackReason(error), acceptPaddleX)
        }

        if (!acceptPaddleX(paddleXText)) {
            val reason = if (paddleXText.isBlank()) "empty-result" else "contract-rejected"
            if (mode == OcrProviderMode.PADDLEX_ONLY) {
                throw PaddleXOcrException("PaddleX OCR result rejected: $reason")
            }
            log.warn {
                "PADDLEX_FALLBACK_TO_LEGACY reason=$reason desc=${desc.ifBlank { "<none>" }} " +
                    "chars=${paddleXText.length}"
            }
            return runLegacy(legacyOcr, desc, reason, acceptPaddleX)
        }

        lastProvider.set(OcrProviderKind.PADDLEX)
        lastContractAccepted.set(true)
        log.info {
            "OCR_PROVIDER_SELECTED provider=PADDLEX reason=contract-accepted desc=${desc.ifBlank { "<none>" }} " +
                "chars=${paddleXText.length}"
        }
        return paddleXText
    }

    private fun runLegacy(
        legacyOcr: () -> String,
        desc: String,
        reason: String,
        acceptPaddleX: (String) -> Boolean,
    ): String {
        return runCatching { legacyOcr() }.getOrElse { error ->
            lastProvider.set(OcrProviderKind.LEGACY)
            lastContractAccepted.set(false)
            log.error(error) {
                "OCR_PROVIDERS_FAILED paddlex=$reason legacy=exception " +
                    "desc=${desc.ifBlank { "<none>" }}"
            }
            throw PaddleXOcrException("PaddleX and legacy OCR both failed: legacy exception", error)
        }.also { text ->
            val accepted = acceptPaddleX(text)
            lastProvider.set(OcrProviderKind.LEGACY)
            lastContractAccepted.set(accepted)
            log.info {
                "OCR_PROVIDER_USED provider=LEGACY reason=$reason desc=${desc.ifBlank { "<none>" }} " +
                    "chars=${text.length} contractAccepted=$accepted"
            }
            if (accepted) {
                log.info {
                    "OCR_PROVIDER_SELECTED provider=LEGACY reason=$reason " +
                        "desc=${desc.ifBlank { "<none>" }} chars=${text.length}"
                }
            } else {
                log.warn {
                    "OCR_PROVIDERS_FAILED paddlex=$reason legacy=contract-rejected " +
                        "desc=${desc.ifBlank { "<none>" }} chars=${text.length}"
                }
            }
        }
    }

    private fun fallbackReason(error: Throwable): String = when (error) {
        is PaddleXOcrException -> when {
            error.message?.contains("timed out", ignoreCase = true) == true -> "sidecar-timeout"
            error.message?.contains("failed exit=", ignoreCase = true) == true -> "sidecar-nonzero"
            error.message?.contains("empty", ignoreCase = true) == true -> "empty-result"
            else -> "exception"
        }
        else -> "exception"
    }

    private fun configuredMode(): OcrProviderMode {
        val requested = OcrProviderMode.parse(ConfigEnum.OCR_PROVIDER_MODE.getString())
        // Preserve the pre-mode USE_PADDLEX_OCR=false contract when the new
        // mode is left at its default AUTO value.
        return if (requested == OcrProviderMode.AUTO && !settingsProvider().enabled) {
            OcrProviderMode.LEGACY_ONLY
        } else {
            requested
        }
    }

    fun logStartupProvider() {
        val settings = settingsProvider()
        val mode = modeProvider()
        val provider = when (mode) {
            OcrProviderMode.LEGACY_ONLY -> OcrProviderKind.LEGACY
            OcrProviderMode.AUTO, OcrProviderMode.PADDLEX_ONLY -> OcrProviderKind.PADDLEX
        }
        log.info {
            "OCR_PROVIDER_SELECTED provider=$provider " +
                "mode=$mode " +
                "configKey=${ConfigEnum.USE_PADDLEX_OCR.name} default=${ConfigEnum.USE_PADDLEX_OCR.defaultValue} " +
                "python=${settings.pythonExecutable} modulePath=${settings.modulePath} " +
                "device=${settings.device} modelCache=${settings.modelCachePath.ifBlank { "<paddlex-default>" }} " +
                "timeoutMs=${settings.timeoutMs}"
        }
        if (!settings.enabled || mode == OcrProviderMode.LEGACY_ONLY) return

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
