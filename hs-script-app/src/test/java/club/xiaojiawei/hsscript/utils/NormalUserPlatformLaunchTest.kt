package club.xiaojiawei.hsscript.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NormalUserPlatformLaunchTest {

    @Test
    fun `safe native mode keeps the known working direct launch path`() {
        assertFalse(NormalUserPlatformLaunch.shouldUseHelper(preventAdminLaunch = false))
    }

    @Test
    fun `explicit prevent admin setting uses isolated normal user helper`() {
        assertTrue(NormalUserPlatformLaunch.shouldUseHelper(preventAdminLaunch = true))
    }

    @Test
    fun `helper command starts an isolated JVM and preserves paths and platform arguments`() {
        assertEquals(
            listOf(
                "C:\\Program Files\\Java\\bin\\javaw.exe",
                "-Djna.library.path=lib",
                "-jar",
                "C:\\runtime with spaces\\hs-script.jar",
                NormalUserPlatformLaunch.HELPER_ARGUMENT,
                "C:\\Battle.net\\Battle.net.exe",
                "--exec=launch WTCG",
            ),
            NormalUserPlatformLaunch.buildHelperCommand(
                javaExecutable = "C:\\Program Files\\Java\\bin\\javaw.exe",
                appJar = "C:\\runtime with spaces\\hs-script.jar",
                jnaLibraryPath = "lib",
                platformExecutable = "C:\\Battle.net\\Battle.net.exe",
                platformArguments = listOf("--exec=launch WTCG"),
            ),
        )
    }

    @Test
    fun `helper invokes native normal-user handoff only in the child entry path`() {
        var receivedExecutable: String? = null
        var receivedArguments: List<String>? = null

        val exitCode = NormalUserPlatformLaunch.runHelper(
            arrayOf(
                NormalUserPlatformLaunch.HELPER_ARGUMENT,
                "C:\\Battle.net\\Battle.net.exe",
                "--exec=launch WTCG",
            ),
        ) { executable, arguments ->
            receivedExecutable = executable
            receivedArguments = arguments
            true
        }

        assertEquals(0, exitCode)
        assertEquals("C:\\Battle.net\\Battle.net.exe", receivedExecutable)
        assertEquals(listOf("--exec=launch WTCG"), receivedArguments)
    }

    @Test
    fun `normal controller entry does not invoke native handoff`() {
        var invoked = false
        val exitCode = NormalUserPlatformLaunch.runHelper(arrayOf("--pause=false")) { _, _ ->
            invoked = true
            true
        }

        assertNull(exitCode)
        assertFalse(invoked)
    }

    @Test
    fun `failed child handoff reports failure without selecting an elevated fallback`() {
        var invocationCount = 0
        val exitCode = NormalUserPlatformLaunch.runHelper(
            arrayOf(
                NormalUserPlatformLaunch.HELPER_ARGUMENT,
                "C:\\Battle.net\\Battle.net.exe",
                "--exec=launch WTCG",
            ),
        ) { _, _ ->
            invocationCount++
            false
        }

        assertEquals(1, exitCode)
        assertEquals(1, invocationCount)
    }
}
