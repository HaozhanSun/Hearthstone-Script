package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.enums.INIT_CONFIG_GROUP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.ini4j.Ini
import java.io.File
import java.nio.file.Files

class BetaConfigPathRecoveryTest {

    @Test
    fun `Beta ini4j read store reload preserves Windows separators and spaces`() {
        val directory = Files.createTempDirectory("beta-ini-path-roundtrip-").toFile()
        try {
            val file = File(directory, "script.ini")
            val gamePath = "D:\\Hearthstone"
            val platformPath = "C:\\Users\\tester\\OneDrive - Duke University\\炉石美服\\Battle.net\\Battle.net.exe"
            file.writeText(
                "[init]\r\nGAME_PATH = $gamePath\r\nPLATFORM_PATH = $platformPath\r\n",
                Charsets.UTF_8,
            )

            val loaded = BetaConfigPathRecovery.load(file, "beta")
            assertEquals(gamePath, loaded.get("init", "GAME_PATH"))
            assertEquals(platformPath, loaded.get("init", "PLATFORM_PATH"))
            loaded.store()

            val reloaded = BetaConfigPathRecovery.load(file, "beta")
            assertEquals(gamePath, reloaded.get("init", "GAME_PATH"))
            assertEquals(platformPath, reloaded.get("init", "PLATFORM_PATH"))
            assertTrue(file.readText(Charsets.UTF_8).contains("D:\\Hearthstone"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `Beta recovers only damaged startup paths from valid escaped backups`() {
        val directory = Files.createTempDirectory("beta-ini-path-recovery-").toFile()
        try {
            val gameDirectory = File(directory, "D-drive/Hearthstone").apply { mkdirs() }
            val platformExecutable = File(directory, "OneDrive - Duke University/Battle.net/Battle.net.exe").apply {
                parentFile.mkdirs()
                writeText("test executable")
            }
            val currentFile = File(directory, "script.ini")
            val backupFile = File(directory, "script.ini.pre-v570.bak")
            backupFile.createNewFile()
            val oldGamePath = gameDirectory.absolutePath
            val oldPlatformPath = platformExecutable.absolutePath
            Ini(backupFile).apply {
                put(INIT_CONFIG_GROUP.name, ConfigEnum.GAME_PATH.name, oldGamePath)
                put(INIT_CONFIG_GROUP.name, ConfigEnum.PLATFORM_PATH.name, oldPlatformPath)
                store()
            }
            currentFile.writeText(
                "[init]\r\nGAME_PATH = D:Hearthstone\r\n" +
                    "PLATFORM_PATH = C:UsersyzjshOneDrive - Duke UniversityDesktop炉石美服Battle.netBattle.net.exe\r\n" +
                    "KEEP_THIS = untouched\r\n",
                Charsets.UTF_8,
            )

            val current = BetaConfigPathRecovery.load(currentFile, "beta")
            val recovered = BetaConfigPathRecovery.recoverFromBackup(
                currentFile = currentFile,
                current = current,
                channel = "beta",
                isGameDirectory = { it == oldGamePath && gameDirectory.isDirectory },
                isPlatformExecutable = { it == oldPlatformPath && platformExecutable.isFile },
            )
            assertEquals("script.ini.pre-v570.bak", recovered?.sourceFileName)
            assertEquals(oldGamePath, current.get(INIT_CONFIG_GROUP.name, ConfigEnum.GAME_PATH.name))
            assertEquals(oldPlatformPath, current.get(INIT_CONFIG_GROUP.name, ConfigEnum.PLATFORM_PATH.name))
            assertEquals("untouched", current.get(INIT_CONFIG_GROUP.name, "KEEP_THIS"))

            current.store()
            val reloaded = BetaConfigPathRecovery.load(currentFile, "beta")
            assertEquals(oldGamePath, reloaded.get(INIT_CONFIG_GROUP.name, ConfigEnum.GAME_PATH.name))
            assertEquals(oldPlatformPath, reloaded.get(INIT_CONFIG_GROUP.name, ConfigEnum.PLATFORM_PATH.name))
            assertEquals("untouched", reloaded.get(INIT_CONFIG_GROUP.name, "KEEP_THIS"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `path recovery is Beta only and leaves configuration unchanged for other channels`() {
        val directory = Files.createTempDirectory("nonbeta-ini-path-").toFile()
        try {
            val gameDirectory = File(directory, "game").apply { mkdirs() }
            val platformExecutable = File(directory, "Battle.net.exe").apply { writeText("test") }
            val currentFile = File(directory, "script.ini")
            val backupFile = File(directory, "script.ini.backup.bak")
            backupFile.createNewFile()
            Ini(backupFile).apply {
                put(INIT_CONFIG_GROUP.name, ConfigEnum.GAME_PATH.name, gameDirectory.absolutePath)
                put(INIT_CONFIG_GROUP.name, ConfigEnum.PLATFORM_PATH.name, platformExecutable.absolutePath)
                store()
            }
            currentFile.writeText("[init]\nGAME_PATH = D:Hearthstone\nPLATFORM_PATH = C:Battle.net.exe\n")
            val current = BetaConfigPathRecovery.load(currentFile, "release-candidate")
            assertNull(
                BetaConfigPathRecovery.recoverFromBackup(
                    currentFile,
                    current,
                    "release-candidate",
                    isGameDirectory = { it == gameDirectory.absolutePath },
                    isPlatformExecutable = { it == platformExecutable.absolutePath },
                ),
            )
            assertEquals("D:Hearthstone", current.get(INIT_CONFIG_GROUP.name, ConfigEnum.GAME_PATH.name))
        } finally {
            directory.deleteRecursively()
        }
    }
}
