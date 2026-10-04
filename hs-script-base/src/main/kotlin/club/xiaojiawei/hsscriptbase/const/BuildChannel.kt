package club.xiaojiawei.hsscriptbase.const

import java.util.Locale

/**
 * Release-channel values are injected into build.info by the release script.
 * Keep channel labels, process identity, and user-facing window identity in
 * one place so isolated runtimes cannot accidentally drift in the UI.
 */
object BuildChannel {

    fun label(raw: String?): String = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "stable" -> "Stable"
        "beta" -> "Beta"
        "release-candidate" -> "Release Candidate"
        else -> "Unknown"
    }

    fun identityToken(raw: String?): String = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "stable" -> "stable"
        "beta" -> "beta"
        "release-candidate" -> "release-candidate"
        else -> "unknown"
    }

    fun isBetaDerived(raw: String?): Boolean =
        identityToken(raw) in setOf("beta", "release-candidate")

    /** Keep Stable's identity, while naming each non-stable app instance distinctly. */
    fun mainWindowTitle(programName: String, rawChannel: String?): String =
        when (identityToken(rawChannel)) {
            "beta" -> "$programName-beta"
            "release-candidate" -> "HS Script Release Candidate"
            else -> programName
        }
}
