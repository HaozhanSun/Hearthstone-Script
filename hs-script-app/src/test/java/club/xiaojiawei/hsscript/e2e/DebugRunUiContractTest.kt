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
        // Surefire normally runs with hs-script-app as user.dir, while an
        // IDE and a root Maven invocation can use the repository root.
        // Resolve both layouts so this contract test checks the packaged
        // resources instead of failing on a relative-path accident.
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        val moduleRoot = sequenceOf(
            workingDirectory,
            workingDirectory.resolve("hs-script-app"),
            workingDirectory.parent?.resolve("hs-script-app"),
        ).filterNotNull().firstOrNull { Files.isDirectory(it.resolve("src")) }
            ?: error("hs-script-app module root was not found from $workingDirectory")
        val repositoryRoot = moduleRoot.parent
        val fxml = moduleRoot.resolve(Path.of("src", "main", "resources", "fxml", "main.fxml"))
        assertTrue(Files.isRegularFile(fxml), "main.fxml must remain checked in")
        val fxmlText = Files.readString(fxml)

        assertTrue(fxmlText.contains("fx:id=\"debugRunModeCheckBox\""))
        assertTrue(fxmlText.contains("config=\"DEBUG_RUN_MODE\""))
        assertTrue(fxmlText.contains("onAction=\"#toggleDebugRun\""))
        assertTrue(fxmlText.contains("fx:id=\"debugRunStatus\""))
        assertTrue(fxmlText.contains("启动后默认开启一次，最多运行30分钟"))

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
        assertTrue(
            appText.indexOf("InitializerConfig.initializer.init()") <
                appText.indexOf("SCHEDULE_LISTENER_STARTED_AFTER_PLUGIN_INIT"),
            "schedule polling must start after plugin/strategy initialization",
        )

        val mainControllerText = Files.readString(moduleRoot.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "controller", "javafx", "MainController.kt",
        )))
        assertTrue(mainControllerText.contains("enableDefaultAfterRestart()"))
        assertTrue(mainControllerText.contains("startupDebugRun.state == DebugRunLease.State.ACTIVE"))
        assertTrue(mainControllerText.contains("ACTIVE ·"))

        val controllerText = Files.readString(moduleRoot.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "status", "DebugRunController.kt",
        )))
        assertTrue(controllerText.contains("PREARM_PROPERTY"))
        assertTrue(controllerText.contains("DEBUG_OVERRIDE_UI_PREARM_RETAINED"))
        assertTrue(controllerText.contains("startupDefaultHandled"))
        assertTrue(controllerText.contains("DEBUG_OVERRIDE_DEFAULT_ACTIVE"))

        val runner = moduleRoot.resolve(Path.of("src", "main", "resources", "bat", "run-debug.ps1"))
        val runnerText = Files.readString(runner)
        assertTrue(runnerText.contains("-Dhs.script.debugrun.prearm=true"))
        assertTrue(runnerText.contains("deployment-manifest.json"))
        assertTrue(runnerText.contains("manifest.appJar"))
        assertTrue(runnerText.contains("manifest.appJarSha256"))
        assertTrue(runnerText.contains("Get-FileHash"))
        assertTrue(!runnerText.contains("\$jar = Get-ChildItem"))
        assertTrue(runnerText.contains("e71234fa-8-pirate-warrior-mcts-9b1f-4d29-8f4f"))
        assertTrue(runnerText.contains("HS_E2E_STRATEGY_ID"))
        assertTrue(runnerText.contains("HS_E2E_DECK_POSITION"))
        assertTrue(runnerText.contains("-Dhs.script.e2e.strategy-id=\$e2eStrategyId"))
        assertTrue(runnerText.contains("-Dhs.script.e2e.deck-position=\$e2eDeckPosition"))
        assertTrue(runnerText.contains("Get-PowerLogTerminalLinesAfter"))
        assertTrue(runnerText.contains("E2E_ROUND_RESULT"))
        assertTrue(runnerText.contains("E2E_GAME_RESULT_LOSS"))
        assertTrue(runnerText.contains("E2E_GAME_RESULT_CONCEDED"))
        assertTrue(runnerText.contains("\$roundsRequired = 3"))
        assertTrue(runnerText.contains("-Dhs.script.e2e.win-required=false"))

        val deploy = Files.readString(repositoryRoot.resolve("build-and-deploy.ps1"))
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
