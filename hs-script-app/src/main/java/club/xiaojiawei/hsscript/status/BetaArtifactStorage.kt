package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.const.BuildChannel
import club.xiaojiawei.hsscriptbase.const.BuildInfo
import java.io.File
import java.nio.file.Files

/** Beta diagnostic artifacts stop before consuming the final 5 GiB of their volume. */
internal object BetaArtifactStorage {
    const val MINIMUM_FREE_BYTES: Long = 5L * 1024L * 1024L * 1024L
    private const val ROOT_PROPERTY = "hs.script.artifact-root"

    fun directory(category: String, legacy: File): File {
        return directoryForChannel(
            channel = BuildInfo.RELEASE_CHANNEL,
            artifactRoot = System.getProperty(ROOT_PROPERTY),
            category = category,
            legacy = legacy,
        )
    }

    internal fun directoryForChannel(channel: String, artifactRoot: String?, category: String, legacy: File): File {
        if (BuildChannel.identityToken(channel) != "beta") return legacy
        val root = artifactRoot?.takeIf(String::isNotBlank) ?: return legacy
        return File(root, category)
    }

    fun canWrite(directory: File): Boolean {
        if (BuildChannel.identityToken(BuildInfo.RELEASE_CHANNEL) != "beta") return true
        return canWriteForChannel(
            channel = BuildInfo.RELEASE_CHANNEL,
            disabled = System.getProperty("hs.script.artifact-storage.disabled") == "true",
            artifactRootConfigured = !System.getProperty(ROOT_PROPERTY).isNullOrBlank(),
            usableSpace = usableSpace(directory),
        )
    }

    internal fun canWriteForChannel(
        channel: String,
        disabled: Boolean,
        artifactRootConfigured: Boolean,
        usableSpace: Long,
    ): Boolean {
        if (BuildChannel.identityToken(channel) != "beta") return true
        if (disabled || !artifactRootConfigured) return false
        return usableSpace >= MINIMUM_FREE_BYTES
    }

    private fun usableSpace(directory: File): Long {
        var existing = directory
        while (!existing.exists() && existing.parentFile != null) existing = existing.parentFile
        return runCatching { Files.getFileStore(existing.toPath()).usableSpace }.getOrDefault(0L)
    }
}
