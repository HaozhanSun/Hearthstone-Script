package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.const.BuildChannel
import java.io.File
import java.nio.file.Files

/** Keeps Beta's active diagnostics on a fixed volume separate from its runtime directory. */
internal object BetaLogDirectoryPolicy {
    const val MINIMUM_FREE_BYTES: Long = 5L * 1024L * 1024L * 1024L

    fun resolve(
        channel: String,
        runtimeDirectory: File,
        configuredDirectory: String?,
        javaTempDirectory: String?,
        minimumFreeBytes: Long = MINIMUM_FREE_BYTES,
        usableSpace: (File) -> Long = ::usableSpace,
    ): File? {
        if (BuildChannel.identityToken(channel) != "beta") return File(runtimeDirectory, "log")
        val runtimeRoot = rootOf(runtimeDirectory)
        return listOfNotNull(configuredDirectory, javaTempDirectory)
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(::File)
            .distinctBy { it.absoluteFile.normalize().path.lowercase() }
            .firstOrNull { candidate ->
                rootOf(candidate) != runtimeRoot && usableSpace(candidate) >= minimumFreeBytes
            }
    }

    private fun rootOf(file: File): String? = runCatching {
        file.absoluteFile.normalize().toPath().root?.toString()?.trimEnd('\\', '/')?.lowercase()
    }.getOrNull()

    private fun usableSpace(directory: File): Long {
        var existing = directory
        while (!existing.exists() && existing.parentFile != null) existing = existing.parentFile
        return runCatching { Files.getFileStore(existing.toPath()).usableSpace }.getOrDefault(0L)
    }
}
