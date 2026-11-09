package club.xiaojiawei.hsscript.e2e

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Prevents the DebugRun implementation from compiling while its main-screen
 * control is silently dropped from the packaged application resources.
 */
class DebugRunUiContractTest {
    @Test
    fun `deployment preflight rejects missing GUI hard cap scripts before runtime mutation`() {
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        val moduleRoot = sequenceOf(
            workingDirectory,
            workingDirectory.resolve("hs-script-app"),
            workingDirectory.parent?.resolve("hs-script-app"),
        ).filterNotNull().firstOrNull { Files.isDirectory(it.resolve("src")) }
            ?: error("hs-script-app module root was not found from $workingDirectory")
        val deploy = Files.readString(moduleRoot.parent.resolve("build-and-deploy.ps1"))
        val preflight = deploy.indexOf("foreach (\$requiredSource in @(")
        val runtimeGate = deploy.indexOf("Enter-HsScriptRuntimeGate")
        assertTrue(preflight >= 0, "deployment must have a required-source preflight")
        assertTrue(runtimeGate > preflight, "required-source preflight must precede runtime mutation")
        assertTrue(deploy.indexOf("\$debugRunnerHardCapWatchdogSource", preflight) in preflight until runtimeGate)
        assertTrue(deploy.indexOf("\$debugRunnerHardCapLauncherSource", preflight) in preflight until runtimeGate)
        assertTrue(
            deploy.indexOf("throw \"Required deployment source is missing:", preflight) in preflight until runtimeGate,
            "a missing watchdog/bootstrap must refuse the release before runtime mutation",
        )
        val runnerValidation = deploy.indexOf("foreach (\$runner in @(")
        assertTrue(runnerValidation > runtimeGate)
        assertTrue(deploy.indexOf("\$debugRunnerHardCapWatchdogSource", runnerValidation) > runnerValidation)
        assertTrue(deploy.indexOf("\$debugRunnerHardCapLauncherSource", runnerValidation) > runnerValidation)
    }

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
        assertTrue(mainControllerText.contains("DebugRunController.enable(\"ui-toggle-on\")"),
            "a refused/invalid prior lease must be replaced only by an explicit user toggle")
        assertTrue(mainControllerText.contains("startupDebugRun.state == DebugRunLease.State.ACTIVE"))
        assertTrue(mainControllerText.contains("ACTIVE ·"))

        val controllerText = Files.readString(moduleRoot.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "status", "DebugRunController.kt",
        )))
        assertTrue(controllerText.contains("PREARM_PROPERTY"))
        assertTrue(controllerText.contains("DEBUG_OVERRIDE_UI_PREARM_RETAINED"))
        assertTrue(controllerText.contains("startupDefaultHandled"))
        assertTrue(controllerText.contains("DEBUG_OVERRIDE_DEFAULT_ACTIVE"))
        assertTrue(controllerText.contains("DebugRunHardCapSupervisor.arm("))
        assertTrue(controllerText.contains("DebugRunHardCapSupervisor.cancel("))
        assertTrue(controllerText.contains("NeverSurrenderPolicy.enabled()"))
        assertTrue(controllerText.contains("ConfigUtil.addBooleanChangeListener"))
        assertTrue(controllerText.contains("never-surrender-enabled-during-debug-run"))
        assertTrue(controllerText.contains("refuseUncappedRun"))
        assertTrue(controllerText.contains("prearmBeforeScheduleChecks"))
        assertTrue(controllerText.contains("Hard-cap watchdog could not be armed"))
        assertTrue(mainControllerText.contains("REFUSED · \$it"))
        assertTrue(mainControllerText.contains("debugRunModeCheckBox.isSelected = snapshot.state == DebugRunLease.State.ACTIVE"))

        val mainApplicationText = Files.readString(moduleRoot.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "MainApplication.kt",
        )))
        assertTrue(mainApplicationText.contains("blockedByHardCapRefusal: Boolean = false"))
        assertTrue(mainApplicationText.contains("DebugRunController.hardCapRefusalReason()"))
        assertTrue(mainApplicationText.contains("resumeFromStartupAutoStart"))
        assertTrue(mainApplicationText.contains("AUTO_START_BLOCKED source=\$source reason=debug-run-hard-cap-refused"))
        assertEquals(
            2,
            Regex("blockedByHardCapRefusal = refusalReason != null").findAll(mainApplicationText).count(),
            "both independent startup auto-start callsites must honor a hard-cap refusal",
        )

        val runner = moduleRoot.resolve(Path.of("src", "main", "resources", "bat", "run-debug.ps1"))
        val runnerText = Files.readString(runner)
        assertTrue(runnerText.contains("-Dhs.script.debugrun.prearm=true"))
        assertTrue(runnerText.contains("HS_E2E_HARD_CAP_SECONDS"))
        assertTrue(runnerText.contains("\$hardCapSeconds = 900"))
        assertTrue(runnerText.contains("E2E_HARD_CAP_ARMED"))
        assertTrue(runnerText.contains("E2E_HARD_CAP_EXPIRED"))
        assertTrue(runnerText.contains("deadline-anchored-to-first-beta-jvm-start"))
        assertTrue(runnerText.contains("debug-run-hardcap.psm1"))
        assertTrue(runnerText.contains("Set-DebugRunHardCapLease"))
        assertTrue(runnerText.contains("-LeasePath \$hardCapLeasePath"))
        assertTrue(runnerText.contains("-StopMarkerPath \$hardCapStopMarkerPath"))
        assertTrue(runnerText.contains("-OwnershipLockPath \$hardCapOwnershipLockPath"))
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
        assertTrue(deploy.contains("debugRunnerHardCapSource"))
        assertTrue(deploy.contains("debugRunnerHardCapWatchdogSource"))
        assertTrue(deploy.contains("debugRunnerHardCapLauncherSource"))

        val hardCapModule = moduleRoot.resolve(Path.of(
            "src", "main", "resources", "bat", "debug-run-hardcap.psm1",
        ))
        assertTrue(Files.isRegularFile(hardCapModule), "DebugRun hard-cap module must remain packaged")
        val hardCapModuleText = Files.readString(hardCapModule)
        assertTrue(hardCapModuleText.contains("ProcessStartUtc"))
        assertTrue(hardCapModuleText.contains("Get-DebugRunHearthstoneDescendantIds"))
        assertTrue(hardCapModuleText.contains("STALE_OR_EXITED_TARGET"))
        assertTrue(hardCapModuleText.contains("ALREADY_ISSUED"))
        assertTrue(hardCapModuleText.contains("Test-DebugRunHardCapLeaseCurrent"))
        assertTrue(hardCapModuleText.contains("Set-DebugRunHardCapGuardianStopIntent"))
        assertTrue(hardCapModuleText.contains("CANCELLED_OR_REPLACED"))
        assertTrue(hardCapModuleText.contains("GUARDIAN_STATE_UNVERIFIED"))
        assertTrue(hardCapModuleText.contains("Enter-DebugRunHardCapOwnershipLock"))
        assertTrue(hardCapModuleText.contains("Exit-DebugRunHardCapOwnershipLock"))
        assertTrue(hardCapModuleText.contains("CANCELLED_OR_REPLACED_AFTER_PREPARE"))
        assertTrue(hardCapModuleText.contains("StopMarkerPath"))
        assertTrue(hardCapModuleText.contains("OWNERSHIP_LOCK_REQUIRED"))

        val watchdog = moduleRoot.resolve(Path.of(
            "src", "main", "resources", "bat", "debug-run-hardcap-watchdog.ps1",
        ))
        assertTrue(Files.isRegularFile(watchdog), "GUI Debug Run watchdog must remain packaged")
        val watchdogText = Files.readString(watchdog)
        assertTrue(watchdogText.contains("-LeasePath"))
        assertTrue(watchdogText.contains("-GuardianStatePath"))
        assertTrue(watchdogText.contains("-StopMarkerPath"))
        assertTrue(watchdogText.contains("-OwnershipLockPath"))
        assertTrue(watchdogText.contains("Invoke-DebugRunHardCapStop"))
        assertTrue(watchdogText.contains("Write-DebugRunHardCapJsonAtomically"))

        val hardCapLauncher = moduleRoot.resolve(Path.of(
            "src", "main", "resources", "bat", "debug-run-hardcap-launcher.ps1",
        ))
        assertTrue(Files.isRegularFile(hardCapLauncher), "hard-cap launcher must remain packaged")
        val hardCapLauncherText = Files.readString(hardCapLauncher)
        assertTrue(hardCapLauncherText.contains("Start-Process"))
        assertTrue(hardCapLauncherText.contains("-WindowStyle Hidden"))
        assertTrue(hardCapLauncherText.contains("\$WatchdogPath"))

        val lifecycle = Files.readString(moduleRoot.resolve(Path.of(
            "src", "main", "java", "club", "xiaojiawei", "hsscript", "status", "LifecycleTrace.kt",
        )))
        assertTrue(lifecycle.contains("DebugRunHardCapSupervisor.withGuardianStateLock(target)"))
        assertTrue(lifecycle.contains("GUARDIAN_STATE_HEARTBEAT_SUPPRESSED reason=hard-cap-stopped-tombstone"))

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
