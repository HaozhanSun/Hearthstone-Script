package club.xiaojiawei.hsscript.ocr

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.getString
import java.util.Locale

enum class OcrProviderMode {
    AUTO,
    PADDLEX_ONLY,
    LEGACY_ONLY,
    ;

    val usesPaddleX: Boolean
        get() = this != LEGACY_ONLY

    val allowsLegacyFallback: Boolean
        get() = this == AUTO

    companion object {
        fun fromConfig(): OcrProviderMode =
            parse(System.getenv("OCR_PROVIDER_MODE"))
                ?: parse(ConfigEnum.OCR_PROVIDER_MODE.getString())
                ?: AUTO

        fun parse(value: String?): OcrProviderMode? {
            val normalized = value
                ?.trim()
                ?.replace('-', '_')
                ?.uppercase(Locale.ROOT)
                ?: return null
            return entries.firstOrNull { it.name == normalized }
        }
    }
}
