package club.xiaojiawei.hsscript.utils

import java.nio.file.Path

internal data class AuthenticodeEvidence(
    val status: String,
    val signerSimpleName: String?,
    val originalFilename: String?,
    val productName: String?,
)

internal data class OwnerIdentityDecision(val allowed: Boolean, val reason: String)

/** Narrowly recognizes the configured Battle.net image and Blizzard's signed self-update image. */
internal object BattleNetOwnerIdentityPolicy {
    private const val EXPECTED_SIGNER = "Blizzard Entertainment, Inc."
    private const val EXPECTED_PRODUCT = "Battle.net"
    private val selfUpdateImage = Regex("temp_[0-9a-f]{32}\\.exe", RegexOption.IGNORE_CASE)

    fun evaluate(
        configuredExecutablePath: String?,
        actualImagePath: String?,
        signature: AuthenticodeEvidence?,
    ): OwnerIdentityDecision {
        if (configuredExecutablePath.isNullOrBlank()) return OwnerIdentityDecision(false, "configured-path-missing")
        if (actualImagePath.isNullOrBlank()) return OwnerIdentityDecision(false, "image-path-unavailable")
        val configured = normalizePath(configuredExecutablePath) ?: return OwnerIdentityDecision(false, "configured-path-invalid")
        val actual = normalizePath(actualImagePath) ?: return OwnerIdentityDecision(false, "image-path-invalid")
        if (!configured.parent.toString().equals(actual.parent.toString(), ignoreCase = true)) {
            return OwnerIdentityDecision(false, "image-outside-configured-directory")
        }

        val configuredName = configured.fileName.toString()
        val actualName = actual.fileName.toString()
        if (!actualName.equals(configuredName, ignoreCase = true) && !selfUpdateImage.matches(actualName)) {
            return OwnerIdentityDecision(false, "image-name-unrecognized")
        }
        if (signature == null || !signature.status.equals("Valid", ignoreCase = true)) {
            return OwnerIdentityDecision(false, "authenticode-not-valid")
        }
        if (!signature.signerSimpleName.equals(EXPECTED_SIGNER, ignoreCase = true)) {
            return OwnerIdentityDecision(false, "signer-not-blizzard")
        }
        if (!signature.originalFilename.equals(configuredName, ignoreCase = true)) {
            return OwnerIdentityDecision(false, "original-filename-mismatch")
        }
        if (!signature.productName.equals(EXPECTED_PRODUCT, ignoreCase = true)) {
            return OwnerIdentityDecision(false, "product-name-mismatch")
        }
        return OwnerIdentityDecision(true, "configured-image-or-verified-blizzard-self-update")
    }

    private fun normalizePath(value: String): Path? = runCatching {
        Path.of(value.trim()).toAbsolutePath().normalize()
    }.getOrNull()?.takeIf { it.fileName != null && it.parent != null }
}
