package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.enums.INIT_CONFIG_GROUP
import club.xiaojiawei.hsscriptbase.const.BuildChannel
import org.ini4j.Config
import org.ini4j.Ini
import java.io.File

/** Beta's Windows paths must be parsed literally before script.ini is normalized and stored. */
internal object BetaConfigPathRecovery {

    data class RecoveredPaths(
        val gamePath: String,
        val platformPath: String,
        val sourceFileName: String,
    )

    fun load(file: File, channel: String): Ini {
        if (BuildChannel.identityToken(channel) != "beta") return Ini(file)
        val ini = Ini()
        ini.config = Config().apply { isEscape = false }
        ini.file = file
        ini.load(file)
        return ini
    }

    /**
     * Recover only the two startup paths, and only when a preserved legacy INI
     * parses to an existing game directory and Battle.net executable. Multiple
     * conflicting backups are treated as ambiguous and left untouched.
     */
    fun recoverFromBackup(
        currentFile: File,
        current: Ini,
        channel: String,
        isGameDirectory: (String) -> Boolean = { File(it).isDirectory },
        isPlatformExecutable: (String) -> Boolean = { File(it).isFile },
    ): RecoveredPaths? {
        if (BuildChannel.identityToken(channel) != "beta") return null
        val group = INIT_CONFIG_GROUP.name
        val currentGamePath = current.get(group, ConfigEnum.GAME_PATH.name).orEmpty()
        val currentPlatformPath = current.get(group, ConfigEnum.PLATFORM_PATH.name).orEmpty()
        if (isGameDirectory(currentGamePath) && isPlatformExecutable(currentPlatformPath)) return null

        val candidates = currentFile.parentFile
            ?.listFiles { file -> file.isFile && file.name.startsWith("script.ini") && file.name.endsWith(".bak") }
            .orEmpty()
            .mapNotNull { backup ->
                runCatching {
                    // Historical backups were written using ini4j's escaped format.
                    val legacy = Ini(backup)
                    val gamePath = legacy.get(group, ConfigEnum.GAME_PATH.name)?.trim().orEmpty()
                    val platformPath = legacy.get(group, ConfigEnum.PLATFORM_PATH.name)?.trim().orEmpty()
                    if (isGameDirectory(gamePath) && isPlatformExecutable(platformPath)) {
                        RecoveredPaths(gamePath, platformPath, backup.name)
                    } else null
                }.getOrNull()
            }
            .distinctBy { it.gamePath.lowercase() to it.platformPath.lowercase() }

        if (candidates.size != 1) return null
        val recovered = candidates.single()
        current.put(group, ConfigEnum.GAME_PATH.name, recovered.gamePath)
        current.put(group, ConfigEnum.PLATFORM_PATH.name, recovered.platformPath)
        return recovered
    }
}
