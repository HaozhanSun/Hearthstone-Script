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
        assertNotEquals(programLockNameForChannel("stable"), programLockNameForChannel("release-candidate"))
        assertNotEquals(programLockNameForChannel("beta"), programLockNameForChannel("release-candidate"))

        assertEquals(
            ExistingInstanceSignal.requestPathForChannel("stable"),
            ExistingInstanceSignal.requestPathForChannel("stable"),
        )
        assertNotEquals(
            ExistingInstanceSignal.requestPathForChannel("stable"),
            ExistingInstanceSignal.requestPathForChannel("beta"),
        )
        assertNotEquals(
            ExistingInstanceSignal.requestPathForChannel("beta"),
            ExistingInstanceSignal.requestPathForChannel("release-candidate"),
        )
    }

    @Test
    fun `launcher resolves only the deployment beside the selected channel launcher`() {
        val root = repositoryRoot()
        val launcher = Files.readString(root.resolve("hs-script-app/src/main/resources/bat/launch-newest-as-admin.ps1"))
        assertTrue(launcher.contains("Resolve-Deployment \$scriptDirectory"))
        assertTrue(launcher.contains("Start-Process -FilePath \$javaPath"))
        assertTrue(launcher.contains("Enter-HsScriptRuntimeGate \$scriptDirectory"))
        assertTrue(launcher.contains("Assert-HsScriptRuntimeIdle \$scriptDirectory"))
        assertTrue(launcher.contains("processStartTimeUtc"))
        assertTrue(launcher.contains("hs-script.pid.json"))
        assertTrue(launcher.contains("launcher-guardian-policy.ps1"))
        assertTrue(launcher.contains("guardianRestart"))
        assertTrue(launcher.contains("processStartTimeUtc"))
        assertTrue(launcher.contains("Invoke-HsScriptGuardianLoop"))
        assertTrue(launcher.contains("Enter-HsScriptGuardianMutex"))
        assertFalse(launcher.contains("Stop-ManagedHsScriptProcesses"))
        assertFalse(launcher.contains("Stop-Process"), "launching a channel must never kill a running app/game")
        assertFalse(launcher.contains("--pause=false"))
        assertTrue(!launcher.contains("Hearthstone Script\\deployment-manifest.json"))

        val deploymentContract = Files.readString(root.resolve(
            "hs-script-app/src/main/resources/bat/deployment-contract.ps1",
        ))
        assertTrue(deploymentContract.contains("function Resolve-HsScriptProcessAssessment"))
        assertTrue(deploymentContract.contains("JVM CommandLine is blank"))
        assertTrue(deploymentContract.contains("Hearthstone process may own an active queue"))
        val betaReleaseChannel = Files.readString(root.resolve("release-channel.json"))
        assertTrue(betaReleaseChannel.contains("\"channel\": \"beta\""))

        val guardianPolicy = Files.readString(root.resolve(
            "hs-script-app/src/main/resources/bat/launcher-guardian-policy.ps1",
        ))
        assertTrue(guardianPolicy.contains("PAUSED_MANUAL"))
        assertTrue(guardianPolicy.contains("STOP_RESTART_BUDGET"))
        assertTrue(guardianPolicy.contains("RESTART_SCRIPT_PRESERVE_GAME"))
        assertTrue(guardianPolicy.contains("RESTART_SCRIPT_AND_ALLOW_START_PATH"))
        assertTrue(guardianPolicy.contains("function Invoke-HsScriptGuardianLoop"))
        assertTrue(guardianPolicy.contains("function Test-HsScriptStartTimeMatch"))
        assertTrue(guardianPolicy.contains("Local\\HsScriptGuardian_"), "guardian mutex must be runtime-scoped and single-instance")
        val lifecycle = Files.readString(root.resolve(
            "hs-script-app/src/main/java/club/xiaojiawei/hsscript/status/LifecycleTrace.kt",
        ))
        assertTrue(lifecycle.contains("guardian-state.json"))
        assertTrue(lifecycle.contains("guardian-state."))
        assertTrue(lifecycle.contains("processStartTimeUtc"))
        assertTrue(lifecycle.contains("PAUSED_MANUAL"))
        assertTrue(lifecycle.contains("GUARDIAN_INTENT intent=STOPPED"))
        val systemUtil = Files.readString(root.resolve(
            "hs-script-app/src/main/java/club/xiaojiawei/hsscript/utils/SystemUtil.kt",
        ))
        assertTrue(systemUtil.contains("reason=explicit-shutdown initiator=\$initiator"))
        val mainController = Files.readString(root.resolve(
            "hs-script-app/src/main/java/club/xiaojiawei/hsscript/controller/javafx/MainController.kt",
        ))
        assertTrue(mainController.contains("WorkTimeListener.requestStart()"))
        assertTrue(mainController.contains("START_REQUEST_ACCEPTED source=main-ui"))
        assertTrue(mainController.contains("STARTUP_RUN_WINDOW_MINUTES"))
        val application = Files.readString(root.resolve(
            "hs-script-app/src/main/java/club/xiaojiawei/hsscript/MainApplication.kt",
        ))
        val unsupportedAotExit = application.indexOf("LifecycleTrace.markGuardianStopped(\"unsupported-aot-native-mode\")")
        val aotExit = application.indexOf("exitProcess(0)", unsupportedAotExit)
        assertTrue(unsupportedAotExit >= 0 && aotExit > unsupportedAotExit,
            "intentional AOT shutdown must mark guardian STOPPED before exitProcess")

        val channel = Files.readString(root.resolve("release-channel.json"))
        assertTrue(channel.contains("\"channel\": \"beta\""))
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
