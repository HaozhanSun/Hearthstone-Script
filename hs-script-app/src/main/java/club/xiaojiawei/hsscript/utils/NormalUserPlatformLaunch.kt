package club.xiaojiawei.hsscript.utils

import java.io.File

/**
 * Launch Battle.net from an isolated JVM so the legacy native normal-user
 * handoff cannot terminate the elevated controller process.
 */
internal object NormalUserPlatformLaunch {
    const val HELPER_ARGUMENT = "--hs-internal-launch-platform-as-normal-user"

    fun buildHelperCommand(
        javaExecutable: String,
        appJar: String,
        jnaLibraryPath: String,
        platformExecutable: String,
        platformArguments: List<String>,
    ): List<String> {
        require(javaExecutable.isNotBlank()) { "javaExecutable must not be blank" }
        require(appJar.isNotBlank()) { "appJar must not be blank" }
        require(platformExecutable.isNotBlank()) { "platformExecutable must not be blank" }
        return buildList {
            add(javaExecutable)
            add("-Djna.library.path=$jnaLibraryPath")
            add("-jar")
            add(appJar)
            add(HELPER_ARGUMENT)
            add(platformExecutable)
            addAll(platformArguments)
        }
    }

    /** Returns null for a normal application launch; otherwise an isolated-helper exit code. */
    fun runHelper(
        arguments: Array<String>,
        launchAsNormalUser: (String, List<String>) -> Boolean,
    ): Int? {
        if (arguments.firstOrNull() != HELPER_ARGUMENT) return null
        if (arguments.size < 3 || arguments[1].isBlank()) {
            System.err.println("NORMAL_USER_LAUNCH_HELPER_INVALID_ARGUMENTS")
            return 2
        }

        val platformExecutable = arguments[1]
        val platformArguments = arguments.drop(2)
        val launched = runCatching {
            launchAsNormalUser(platformExecutable, platformArguments)
        }.onFailure { error ->
            System.err.println(
                "NORMAL_USER_LAUNCH_HELPER_FAILED type=${error.javaClass.name} message=${error.message}",
            )
        }.getOrDefault(false)
        if (!launched) System.err.println("NORMAL_USER_LAUNCH_HELPER_FAILED result=false")
        return if (launched) 0 else 1
    }

    fun startFromCurrentJar(
        platformExecutable: String,
        platformArguments: List<String>,
    ): Process {
        val appJar = File(GameUtil::class.java.protectionDomain.codeSource.location.toURI())
        require(appJar.isFile) { "Normal-user launch helper requires packaged application JAR: $appJar" }

        val javaHome = File(System.getProperty("java.home"))
        val javaw = File(javaHome, "bin/javaw.exe").takeIf(File::isFile)
            ?: File(javaHome, "bin/java.exe").takeIf(File::isFile)
            ?: error("Java executable not found under $javaHome")
        val workingDirectory = File(System.getProperty("user.dir"))
        val command = buildHelperCommand(
            javaExecutable = javaw.absolutePath,
            appJar = appJar.absolutePath,
            jnaLibraryPath = System.getProperty("jna.library.path", "lib"),
            platformExecutable = platformExecutable,
            platformArguments = platformArguments,
        )
        return ProcessBuilder(command)
            .directory(workingDirectory)
            .start()
    }
}
