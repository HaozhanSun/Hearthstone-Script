package club.xiaojiawei.hsscript

import club.xiaojiawei.hsscript.utils.ExistingInstanceSignal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InstanceIdentityIsolationTest {

    @Test
    fun `mutex and activation signal identities are same-channel stable and cross-channel isolated`() {
        assertEquals(programLockNameForChannel("stable"), programLockNameForChannel("STABLE"))
        assertEquals(programLockNameForChannel("beta"), programLockNameForChannel(" beta "))
        assertNotEquals(programLockNameForChannel("stable"), programLockNameForChannel("beta"))

        assertEquals(
            ExistingInstanceSignal.requestPathForChannel("stable"),
            ExistingInstanceSignal.requestPathForChannel("stable"),
        )
        assertNotEquals(
            ExistingInstanceSignal.requestPathForChannel("stable"),
            ExistingInstanceSignal.requestPathForChannel("beta"),
        )
    }

    @Test
    fun `launcher resolves only the deployment beside the selected channel launcher`() {
        val root = repositoryRoot()
        val launcher = Files.readString(root.resolve("hs-script-app/src/main/resources/bat/launch-newest-as-admin.ps1"))
        assertTrue(launcher.contains("Resolve-Deployment \$scriptDirectory"))
        assertTrue(launcher.contains("Start-Process -FilePath \$javaPath"))
        assertFalse(launcher.contains("--pause=false"))
        assertTrue(!launcher.contains("Hearthstone Script\\deployment-manifest.json"))

        val channel = Files.readString(root.resolve("release-channel.json"))
        assertTrue(channel.contains("\"runtimeDirectoryName\": \"Hearthstone Script Beta\""))
    }

    @Test
    fun `unchecked start on open remains paused without an explicit start signal`() {
        assertFalse(
            shouldAutoStart(
                rawArgs = emptyList(),
                namedPause = null,
                systemAutoStart = false,
                configuredAutoStart = false,
            ),
        )
        assertFalse(
            shouldAutoStart(
                rawArgs = listOf("--pause=true"),
                namedPause = null,
                systemAutoStart = false,
                configuredAutoStart = false,
            ),
        )
    }

    @Test
    fun `explicit start signals still start when start on open is disabled`() {
        assertTrue(
            shouldAutoStart(
                rawArgs = listOf("--pause=false"),
                namedPause = null,
                systemAutoStart = false,
                configuredAutoStart = false,
            ),
        )
        assertTrue(
            shouldAutoStart(
                rawArgs = emptyList(),
                namedPause = "false",
                systemAutoStart = false,
                configuredAutoStart = false,
            ),
        )
        assertTrue(
            shouldAutoStart(
                rawArgs = emptyList(),
                namedPause = null,
                systemAutoStart = true,
                configuredAutoStart = false,
            ),
        )
    }

    private fun repositoryRoot(): Path {
        val current = Path.of("").toAbsolutePath().normalize()
        return sequenceOf(current, current.parent)
            .filterNotNull()
            .first { Files.isRegularFile(it.resolve("release-channel.json")) }
    }
}
