package club.xiaojiawei.hsscript.ocr

import java.util.Locale

/**
 * Selects how OCR providers are routed. AUTO keeps PaddleX first and only
 * uses legacy OCR after a contract-level PaddleX failure.
 */
enum class OcrProviderMode {
    AUTO,
    PADDLEX_ONLY,
    LEGACY_ONLY,
    ;

    companion object {
        fun parse(raw: String): OcrProviderMode = when (raw.trim().uppercase(Locale.ROOT)) {
            "PADDLEX_ONLY", "PADDLEX-ONLY" -> PADDLEX_ONLY
            "LEGACY_ONLY", "LEGACY-ONLY", "TESSERACT" -> LEGACY_ONLY
            "", "AUTO", "PADDLEX_FIRST", "PADDLEX-FIRST" -> AUTO
            else -> AUTO
        }
    }
}
