package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscriptbase.const.BuildChannel

/** Beta startup paths must be absolute and resolve to the configured client files. */
internal object BetaStartupPathPolicy {

    data class ResolvedPaths(val gamePath: String, val platformPath: String)

    data class Rejection(val field: String, val reason: String)

    data class Result(val paths: ResolvedPaths?, val rejection: Rejection?)

    fun shouldStopRetry(channel: String, mode: String, dispatchSucceeded: Boolean): Boolean =
        BuildChannel.identityToken(channel) == "beta" && mode == "PLATFORM_ARG" && !dispatchSucceeded

    fun resolve(
        gamePath: String,
        platformPath: String,
        isGameDirectory: (String) -> Boolean = { java.io.File(it).isDirectory },
        isPlatformExecutable: (String) -> Boolean = { java.io.File(it).isFile },
    ): Result {
        val game = normalize(gamePath)
        val platform = normalize(platformPath)
        if (game.isNullOrBlank()) return Result(null, Rejection("GAME_PATH", "blank"))
        if (platform.isNullOrBlank()) return Result(null, Rejection("PLATFORM_PATH", "blank"))

        val resolvedGame = resolveDriveRelativeGamePath(game, isGameDirectory)
        if (!isAbsoluteWindowsPath(resolvedGame)) {
            return Result(null, Rejection("GAME_PATH", "not-absolute-windows-path"))
        }
        if (!isGameDirectory(resolvedGame)) {
            return Result(null, Rejection("GAME_PATH", "directory-not-found"))
        }
        if (!isAbsoluteWindowsPath(platform)) {
            return Result(null, Rejection("PLATFORM_PATH", "not-absolute-windows-path"))
        }
        if (!platform.endsWith(".exe", ignoreCase = true) || !isPlatformExecutable(platform)) {
            return Result(null, Rejection("PLATFORM_PATH", "executable-not-found"))
        }
        return Result(ResolvedPaths(resolvedGame, platform), null)
    }

    /** Normalize slash direction only; never guess missing separators in a path. */
    private fun normalize(value: String): String = value.trim().removeSurrounding("\"").replace('/', '\\')

    private fun isAbsoluteWindowsPath(value: String): Boolean =
        Regex("^[A-Za-z]:\\\\.+").matches(value) || value.startsWith("\\\\")

    private fun resolveDriveRelativeGamePath(
        value: String,
        isGameDirectory: (String) -> Boolean,
    ): String {
        if (!Regex("^[A-Za-z]:[^\\\\].+").matches(value)) return value
        val rooted = value.substring(0, 2) + "\\" + value.substring(2)
        return if (isGameDirectory(rooted)) rooted else value
    }
}
