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
        if (BuildChannel.identityToken(BuildInfo.RELEASE_CHANNEL) != "beta") return legacy
        val root = System.getProperty(ROOT_PROPERTY)?.takeIf(String::isNotBlank) ?: return legacy
        return File(root, category)
    }

    fun canWrite(directory: File): Boolean {
        if (BuildChannel.identityToken(BuildInfo.RELEASE_CHANNEL) != "beta") return true
        if (System.getProperty("hs.script.artifact-storage.disabled") == "true") return false
        if (System.getProperty(ROOT_PROPERTY).isNullOrBlank()) return true
        var existing = directory
        while (!existing.exists() && existing.parentFile != null) existing = existing.parentFile
        val usable = runCatching { Files.getFileStore(existing.toPath()).usableSpace }.getOrDefault(0L)
        return usable >= MINIMUM_FREE_BYTES
    }
}
