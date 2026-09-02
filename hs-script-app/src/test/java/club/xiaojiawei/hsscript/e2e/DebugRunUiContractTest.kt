package club.xiaojiawei.hsscript.e2e

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Prevents the DebugRun implementation from compiling while its main-screen
 * control is silently dropped from the packaged application resources.
 */
class DebugRunUiContractTest {
    @Test
    fun `debug run UI is wired to the non-persistent thirty minute lease`() {
        val moduleRoot = Path.of("hs-script-app")
        val fxml = moduleRoot.resolve(Path.of("src", "main", "resources", "fxml", "main.fxml"))
        assertTrue(Files.isRegularFile(fxml), "main.fxml must remain checked in")
        val fxmlText = Files.readString(fxml)

        assertTrue(fxmlText.contains("fx:id=\"debugRunModeCheckBox\""))
        assertTrue(fxmlText.contains("config=\"DEBUG_RUN_MODE\""))
        assertTrue(fxmlText.contains("onAction=\"#toggleDebugRun\""))
        assertTrue(fxmlText.contains("fx:id=\"debugRunStatus\""))

        val config = moduleRoot.resolve(Path.of(
            "src",
            "main",
            "java",
            "club",
            "xiaojiawei",
            "hsscript",
            "enums",
            "ConfigEnum.kt",
        ))
        assertTrue(Files.isRegularFile(config), "ConfigEnum.kt must remain checked in")
        val configText = Files.readString(config)
        assertTrue(configText.contains("DEBUG_RUN_MODE"))
        assertTrue(configText.contains("defaultValueInitializer = { FALSE_STR }"))

        val appText = Files.readString(moduleRoot.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "MainApplication.kt",
        )))
        assertTrue(appText.contains("prearmBeforeScheduleChecks()"))
        assertTrue(appText.indexOf("prearmBeforeScheduleChecks()") < appText.indexOf("launchService()"))

        val controllerText = Files.readString(moduleRoot.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "status", "DebugRunController.kt",
        )))
        assertTrue(controllerText.contains("PREARM_PROPERTY"))
        assertTrue(controllerText.contains("DEBUG_OVERRIDE_UI_PREARM_RETAINED"))

        val runner = moduleRoot.resolve(Path.of("src", "main", "resources", "bat", "run-debug.ps1"))
        val runnerText = Files.readString(runner)
        assertTrue(runnerText.contains("-Dhs.script.debugrun.prearm=true"))
        assertTrue(runnerText.contains("deployment-manifest.json"))
        assertTrue(runnerText.contains("manifest.appJar"))
        assertTrue(runnerText.contains("manifest.appJarSha256"))
        assertTrue(runnerText.contains("Get-FileHash"))
        assertTrue(!runnerText.contains("\$jar = Get-ChildItem"))

        val deploy = Files.readString(Path.of("build-and-deploy.ps1"))
        assertTrue(deploy.contains("run-debug.ps1"))
        assertTrue(deploy.contains("debugRunnerSource"))

        val lease = moduleRoot.resolve(Path.of(
            "src",
            "main",
            "java",
            "club",
            "xiaojiawei",
            "hsscript",
            "status",
            "DebugRunLease.kt",
        ))
        assertTrue(Files.isRegularFile(lease), "DebugRunLease.kt must remain checked in")
        val leaseText = Files.readString(lease)
        assertTrue(leaseText.contains("MAX_DURATION_MILLIS"))
        assertTrue(leaseText.contains("effectiveCanWork"))

        val listener = moduleRoot.resolve(Path.of(
            "src",
            "main",
            "java",
            "club",
            "xiaojiawei",
            "hsscript",
            "listener",
            "WorkTimeListener.kt",
        ))
        assertTrue(Files.isRegularFile(listener), "WorkTimeListener.kt must remain checked in")
        val listenerText = Files.readString(listener)
        assertTrue(listenerText.contains("SCHEDULE_OVERRIDE_SUPPRESSED_OUTSIDE_HOURS"))
        assertTrue(listenerText.contains("overrideLogGate"))
        assertTrue(listenerText.contains("normalScheduleActive"))
        assertTrue(listenerText.contains("overrideInfo == null"))

        val override = moduleRoot.resolve(Path.of(
            "src",
            "main",
            "java",
            "club",
            "xiaojiawei",
            "hsscript",
            "status",
            "ScheduleOverride.kt",
        ))
        assertTrue(Files.isRegularFile(override), "ScheduleOverride.kt must remain checked in")
    }
}
