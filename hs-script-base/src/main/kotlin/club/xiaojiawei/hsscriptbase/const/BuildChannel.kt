package club.xiaojiawei.hsscriptbase.const

import java.util.Locale

/**
 * Release-channel values are injected into build.info by the release script.
 * Keep the user-facing spelling in one place so Stable and Beta builds cannot
 * accidentally drift in the UI.
 */
object BuildChannel {

    fun label(raw: String?): String = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "stable" -> "Stable"
        "beta" -> "Beta"
        else -> "Unknown"
    }

    fun identityToken(raw: String?): String = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "stable" -> "stable"
        "beta" -> "beta"
        else -> "unknown"
    }
}
