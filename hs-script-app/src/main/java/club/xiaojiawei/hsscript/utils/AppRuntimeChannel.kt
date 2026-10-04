package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.consts.ROOT_PATH
import java.io.File

/** Reads the installed artifact channel without consulting mutable user config. */
internal enum class AppRuntimeChannel { BETA, RELEASE_CANDIDATE, STABLE, UNKNOWN }

internal object AppRuntimeChannelDetector {
    private val channelPattern = Regex("\"channel\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    fun fromMetadata(metadata: String?): AppRuntimeChannel = when (
        channelPattern.find(metadata.orEmpty())?.groupValues?.getOrNull(1)?.lowercase()
    ) {
        "beta" -> AppRuntimeChannel.BETA
        "release-candidate" -> AppRuntimeChannel.RELEASE_CANDIDATE
        "stable" -> AppRuntimeChannel.STABLE
        else -> AppRuntimeChannel.UNKNOWN
    }

    fun installedChannel(rootPath: String = ROOT_PATH): AppRuntimeChannel = runCatching {
        val metadata = File(rootPath, "release-channel.json")
        if (!metadata.isFile || !metadata.canRead()) AppRuntimeChannel.UNKNOWN
        else fromMetadata(metadata.readText(Charsets.UTF_8))
    }.getOrDefault(AppRuntimeChannel.UNKNOWN)
}
