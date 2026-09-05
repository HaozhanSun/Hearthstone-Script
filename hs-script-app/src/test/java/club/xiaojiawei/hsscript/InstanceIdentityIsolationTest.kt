package club.xiaojiawei.hsscript

import club.xiaojiawei.hsscript.utils.ExistingInstanceSignal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(!launcher.contains("Hearthstone Script\\deployment-manifest.json"))

        val channel = Files.readString(root.resolve("release-channel.json"))
        assertTrue(channel.contains("\"runtimeDirectoryName\": \"Hearthstone Script Beta\""))
    }

    private fun repositoryRoot(): Path {
        val current = Path.of("").toAbsolutePath().normalize()
        return sequenceOf(current, current.parent)
            .filterNotNull()
            .first { Files.isRegularFile(it.resolve("release-channel.json")) }
    }
}
