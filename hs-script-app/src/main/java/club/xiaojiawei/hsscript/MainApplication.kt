package club.xiaojiawei.hsscript

import club.xiaojiawei.hsscript.bean.CommonCardAction.Companion.DEFAULT
import club.xiaojiawei.hsscript.bean.Release
import club.xiaojiawei.hsscript.config.InitializerConfig
import club.xiaojiawei.hsscript.config.ShutdownHookConfig
import club.xiaojiawei.hsscript.consts.*
import club.xiaojiawei.hsscript.core.Core
import club.xiaojiawei.hsscript.dll.CSystemDll
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.enums.SoftProtectedModeEnum
import club.xiaojiawei.hsscript.enums.WindowEnum
import club.xiaojiawei.hsscript.listener.GlobalHotkeyListener
import club.xiaojiawei.hsscript.listener.StatisticsListener
import club.xiaojiawei.hsscript.listener.VersionListener
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.RuntimeSafety
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.status.TaskManager
import club.xiaojiawei.hsscript.status.LifecycleTrace
import club.xiaojiawei.hsscript.utils.*
import club.xiaojiawei.hsscript.utils.SystemUtil.addTray
import club.xiaojiawei.hsscript.utils.SystemUtil.shutdownSoft
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.config.submitExtra
import club.xiaojiawei.hsscriptbase.const.BuildInfo
import club.xiaojiawei.hsscriptbase.const.SoftRunMode
import club.xiaojiawei.hsscriptbase.util.isFalse
import club.xiaojiawei.hsscriptbase.util.isTrue
import club.xiaojiawei.hsscriptcardsdk.CardAction
import com.sun.jna.Memory
import com.sun.jna.WString
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.stage.Screen
import javafx.stage.Stage
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.awt.MenuItem
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.io.File
import java.net.URLClassLoader
import java.util.Locale.getDefault
import java.util.function.Consumer
import java.util.function.Supplier
import java.util.prefs.Preferences
import javax.swing.AbstractAction
import kotlin.system.exitProcess

/**
 * javaFX启动器
 * @author 肖嘉威
 * @date 2023/7/6 9:46
 */
class MainApplication : Application() {
    private var stageShowingListener: ChangeListener<Boolean?>? = null

    fun testJava() {
        try {
            val classManager = DynamicClassManager()

            // 加载外部Java文件
            val testUtilClass =
                classManager.loadJavaFile("S:\\IdeaProjects\\fs32\\src\\main\\java\\com\\fs\\TestUtil1.java")
            println("成功加载类：${testUtilClass.name}")

            // 创建实例
            val testUtil = classManager.instantiate(testUtilClass)
            println("成功创建示例： $testUtil")

            // 调用方法 (使用反射)
            val getWarMethod = testUtilClass.getMethod("getWar")
            val result = getWarMethod.invoke(testUtil)
            println("调用getWar方法结果： $result")
        } catch (e: Exception) {
            println("发生错误: ${e.message}")
            e.printStackTrace()
        }
    }

    fun testKt() {
        try {
            val manager = DynamicClassManager()

            // 动态加载外部 Kotlin 文件
            val loadedClass = manager.loadKotlinFile("S:\\IdeaProjects\\fs32\\src\\main\\java\\com\\fs\\TestUtil.kt")

            // 创建实例
            val instance = loadedClass.getDeclaredConstructor().newInstance()

            // 调用方法 sayHello()
            val method = loadedClass.getMethod("getWar")
            method.invoke(instance)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun compileAndRunExternalKtFiles(
        filePaths: List<String>,
        classpath: String,
    ) {
        val ktFiles =
            filePaths.map { File(it) }.filter {
                val res = it.exists()
                res.isFalse {
                    println(it.absolutePath + "不存在")
                }
                res
            }
        if (ktFiles.isEmpty()) return

        val tempDir = File(System.getProperty("java.io.tmpdir"), "kotlin-temp")
        tempDir.mkdirs()

        val classDir = File(tempDir, "classes")
        classDir.mkdirs()

        val compiler = K2JVMCompiler()
        val args = mutableListOf<String>()
        args.addAll(ktFiles.map { it.absolutePath }) // 添加所有文件路径
        args.addAll(
            arrayOf(
                "-d",
                classDir.absolutePath,
                "-classpath",
                classpath,
            ),
        )

        val exitCode = compiler.exec(System.out, *args.toTypedArray())
        if (exitCode.code != 0) {
            println("Compilation failed")
            return
        }

        val classLoader = URLClassLoader(arrayOf(classDir.toURI().toURL()))
        val className =
            ktFiles.first().nameWithoutExtension.replaceFirstChar { if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString() } // 使用第一个文件的类名
        try {
            val tempClass = classLoader.loadClass(className)
            val newInstance = tempClass.getDeclaredConstructor().newInstance()
            val mainMethod = tempClass.getDeclaredMethod("getWar")
            println(mainMethod.invoke(newInstance))
        } catch (e: ClassNotFoundException) {
            println("Class not found: $className")
        } catch (e: NoSuchMethodException) {
            println("Main method not found in class: $className")
        } catch (e: Exception) {
            println("Error running compiled code: ${e.message}")
        }
    }

    override fun start(stage: Stage?) {
        runCatching {
            for (string in ScriptStatus.programArgs) {
                if (string.startsWith("--window=")) {
                    val windowEnum = WindowEnum.fromString(string.split("=")[1]) ?: break
                    WindowUtil.showStage(windowEnum)
                    return
                }
            }
            preInit()
            InitializerConfig.initializer.init()
        }.onFailure {
            log.error { it }
        }
        showMainPage()
//        testJava()
//        testKt()
//        compileAndRunExternalKtFiles(listOf("S:\\IdeaProjects\\fs32\\src\\main\\java\\com\\fs\\TestUtil.kt"), System.getProperty("java.class.path"))
    }

    private fun preInit() {
        log.info {
            "构建版本=${BuildInfo.VERSION}, 上游基线=${BuildInfo.UPSTREAM_BASELINE_VERSION}, " +
                "本地构建时间(Pacific)=${BuildInfo.BUILD_TIMESTAMP_PACIFIC}"
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log.error(throwable) { "未捕获线程异常：${thread.name}" }
        }
        CardAction.commonActionFactory = Supplier { DEFAULT.createNewInstance() }
        Platform.setImplicitExit(false)
        launchService()
        LifecycleTrace.start()
        CardGroupUtil.applyEnabledCardGroups()
        ShutdownHookConfig.init
    }

    private fun showMainPage() {
        if (ScriptStatus.programArgs.stream().anyMatch {
                if (it.startsWith(ARG_PAGE)) {
                    WindowEnum.fromString(it.removePrefix(ARG_PAGE).uppercase())?.let { windowEnum ->
                        WindowUtil.showStage(windowEnum)
                        WindowUtil.hideLaunchPage()
                        return@anyMatch true
                    }
                }
                return@anyMatch false
            }
        ) {
            return
        }
        val stage = WindowUtil.buildStage(WindowEnum.MAIN)
        stageShowingListener =
            ChangeListener { _, aBoolean: Boolean?, t1: Boolean? ->
                if (t1 != null && t1) {
                    LifecycleTrace.markMainWindow(true, "showing-property")
                    stage.showingProperty().removeListener(stageShowingListener)
                    stageShowingListener = null
                    afterShowing()
                }
            }
        stage.showingProperty().addListener(stageShowingListener)
        stage.setOnCloseRequest { event ->
            event.consume()
            LifecycleTrace.markMainWindow(false, "close-request-consumed-hide")
            log.warn { "主窗口收到关闭请求：仅隐藏窗口，脚本和托盘继续运行；使用托盘退出或 Alt+P 才会结束进程" }
            stage.hide()
        }
        stage.show()
        ExistingInstanceSignal.startWatcher()
        val explicitAutoStart = System.getProperty("hs.script.autostart") == "true"
        val configuredAutoStart = ConfigUtil.getBoolean(ConfigEnum.START_ON_OPEN)
        if (explicitAutoStart || configuredAutoStart) {
            go {
                Thread.sleep(1000)
                LifecycleTrace.mark("auto-start-requested source=${if (explicitAutoStart) "system-property" else "config"}")
                PauseStatus.isPause = false
            }
        }
    }

    @Deprecated("")
    private fun setSystemTray() {
        val isPauseItem = MenuItem("开始")
        isPauseItem.addActionListener(
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    PauseStatus.asyncSetPause(!PauseStatus.isPause)
                }
            },
        )
        PauseStatus.addChangeListener { observableValue: ObservableValue<out Boolean?>?, aBoolean: Boolean?, isPause: Boolean ->
            if (isPause) {
                isPauseItem.label = "开始"
            } else {
                isPauseItem.label = "暂停"
            }
        }

        val settingsItem = MenuItem("设置")
        settingsItem.addActionListener(
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    WindowUtil.showStage(WindowEnum.SETTINGS, WindowUtil.getStage(WindowEnum.MAIN))
                }
            },
        )

        val quitItem = MenuItem("退出")
        quitItem.addActionListener(
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    shutdownSoft()
                }
            },
        )

        addTray(
            Consumer { e: MouseEvent? ->
//            左键点击
                if (e?.button == 1) {
                    (WindowUtil.getStage(WindowEnum.MAIN)?.isShowing ?: false)
                        .isTrue {
                            WindowUtil.hideAllStage(true)
                        }.isFalse {
                            WindowUtil.showStage(WindowEnum.MAIN)
                        }
                }
            },
            isPauseItem,
            settingsItem,
            quitItem,
        )
    }

    private var trayItemArr: CSystemDll.TrayItem.Reference? = null

    private val initTrayMenu: CSystemDll.TrayMenu.Reference by lazy {
        val textMemorySize = 50L
        val iconPathMemorySize = 255L
        CSystemDll.TrayMenu.Reference().apply {
            text =
                Memory(textMemorySize).apply {
                    setWideString(0, PROGRAM_NAME)
                }
            iconPath = WString(SystemUtil.getTrayIconFile().absolutePath)
            clickCallback =
                object : CSystemDll.TrayCallback {
                    override fun invoke() {
                        val mainStage = WindowUtil.getStage(WindowEnum.MAIN)
                        if (mainStage == null || !mainStage.isShowing || mainStage.isIconified) {
                            WindowUtil.showStage(WindowEnum.MAIN)
                        } else {
                            WindowUtil.hideAllStage()
                        }
                    }
                }
            itemCount = 5
            trayItem = CSystemDll.TrayItem.Reference()
            trayItemArr = trayItem
            val trayItemArr = trayItem!!.toArray(itemCount) as Array<CSystemDll.TrayItem>
            trayItemArr[0].apply {
                id = 1000
                type = CSystemDll.MF_STRING
                text =
                    Memory(textMemorySize).apply {
                        setWideString(0, "开始")
                    }
                iconPath =
                    Memory(iconPathMemorySize).apply {
                        setWideString(0, SystemUtil.getResourcesImgFile(TRAY_START_IMG_NAME).absolutePath)
                    }
                callback =
                    object : CSystemDll.TrayCallback {
                        override fun invoke() {
                            PauseStatus.asyncSetPause(!PauseStatus.isPause)
                        }
                    }
            }
            trayItemArr[1].apply {
                id = 1001
                type = CSystemDll.MF_STRING
                text =
                    Memory(textMemorySize).apply {
                        setWideString(0, "设置")
                    }
                iconPath =
                    Memory(iconPathMemorySize).apply {
                        setWideString(0, SystemUtil.getResourcesImgFile(TRAY_SETTINGS_IMG_NAME).absolutePath)
                    }
                callback =
                    object : CSystemDll.TrayCallback {
                        override fun invoke() {
                            WindowUtil.showStage(WindowEnum.SETTINGS, WindowUtil.getStage(WindowEnum.MAIN))
                        }
                    }
            }
            trayItemArr[2].apply {
                id = 1002
                type = CSystemDll.MF_STRING
                text =
                    Memory(textMemorySize).apply {
                        setWideString(0, "统计")
                    }
                iconPath =
                    Memory(iconPathMemorySize).apply {
                        setWideString(0, SystemUtil.getResourcesImgFile(TRAY_STATISTICS_IMG_NAME).absolutePath)
                    }
                callback =
                    object : CSystemDll.TrayCallback {
                        override fun invoke() {
                            WindowUtil.showStage(WindowEnum.STATISTICS, WindowUtil.getStage(WindowEnum.MAIN))
                        }
                    }
            }
            trayItemArr[3].apply {
                id = 1003
                type = CSystemDll.MF_SEPARATOR
            }
            trayItemArr[4].apply {
                id = 1004
                type = CSystemDll.MF_STRING
                text =
                    Memory(textMemorySize).apply {
                        setWideString(0, "退出")
                    }
                iconPath =
                    Memory(iconPathMemorySize).apply {
                        setWideString(0, SystemUtil.getResourcesImgFile(TRAY_EXIT_IMG_NAME).absolutePath)
                    }
                callback =
                    object : CSystemDll.TrayCallback {
                        override fun invoke() {
                            shutdownSoft()
                        }
                    }
            }
            PauseStatus.addChangeListener { _, _, isPause: Boolean ->
                if (isPause) {
                    trayItemArr[0].text?.setWideString(0, "开始")
                    trayItemArr[0].iconPath?.setWideString(
                        0,
                        SystemUtil.getResourcesImgFile(TRAY_START_IMG_NAME).absolutePath,
                    )
                } else {
                    trayItemArr[0].text?.setWideString(0, "暂停")
                    trayItemArr[0].iconPath?.setWideString(
                        0,
                        SystemUtil.getResourcesImgFile(TRAY_PAUSE_IMG_NAME).absolutePath,
                    )
                }
            }
            if (!RuntimeSafety.safeNative) {
                CSystemDll.INSTANCE.addSystemTray(this).isFalse {
                    log.warn { "系统托盘创建失败" }
                }
            } else {
                log.info { "E2E运行：跳过原生系统托盘" }
            }
        }
    }

    private fun launchService() {
        TaskManager.launch
        Core.launch
        val enableE2eHotkeys = System.getProperty("hs.script.e2e.enable-hotkeys") == "true"
        val e2eRun = System.getProperty("hs.script.e2e") == "true"
        if (e2eRun && !enableE2eHotkeys) {
            // Keep exit/console/configurable hotkeys out of the stability run,
            // but retain the fixed F1/F2 controls so the run can be paused or
            // resumed safely from the real desktop.
            log.info { "E2E运行：仅初始化固定 F1/F2 热键，跳过其它全局热键" }
            go { GlobalHotkeyListener.fixedControlsLaunch }
        } else {
            if (enableE2eHotkeys) {
                log.info { "E2E运行：启用全局热键验证" }
            }
            go { GlobalHotkeyListener.launch }
        }
        VersionListener.launch
        WorkTimeListener.launch
        StatisticsListener.launch
    }

    private var aoting = false

    private fun checkArg() {
        val args = this.parameters.raw
        var pause: String? = ""
        for (arg in args) {
            if (arg.startsWith(ARG_AOT)) {
                aoting = true
            } else if (arg.startsWith(ARG_PAUSE)) {
                val split: Array<String?> = arg.split("=".toRegex(), limit = 2).toTypedArray()
                if (split.size > 1) {
                    pause = split[1]
                }
            }
        }
        val namedPause = this.parameters.named["pause"]
        val systemAutoStart = System.getProperty("hs.script.autostart") == "true"
        val startOnOpen = ConfigUtil.getBoolean(ConfigEnum.START_ON_OPEN)
        if ("false" == pause || "false" == namedPause || systemAutoStart || startOnOpen) {
            log.info { "接收到开始参数，开始脚本" }
            Thread.sleep(1000)
            PauseStatus.isPause = false
        } else {
            val preferences = Preferences.userNodeForPackage(this::class.java)
            val key = "used"
            val version = ConfigUtil.getString(ConfigEnum.CURRENT_VERSION)
            if (Release.compareVersion(BuildInfo.VERSION, version) > 0) {
                runUI {
                    WindowUtil.showStage(WindowEnum.ABOUT)
                    WindowUtil.showStage(WindowEnum.VERSION_MSG, WindowUtil.getStage(WindowEnum.MAIN))
                    ConfigUtil.putString(ConfigEnum.CURRENT_VERSION, BuildInfo.VERSION)
                }
            } else {
                if (preferences.get(key, "").isNullOrBlank()) {
                    WindowUtil.showStage(WindowEnum.ABOUT)
                }
            }
            preferences.put(key, "true")
        }
    }

    private fun checkSystem() {
        if (RuntimeSafety.safeNative) {
            log.info { "E2E运行：跳过原生权限检查" }
        } else CSystemDll.INSTANCE.isRunAsAdministrator().isFalse {
            val text = "当前进程不是以管理员启动，功能可能受限"
            log.warn { text }
            SystemUtil.notice(text)
        }

        Screen.getScreens()?.let {
            if (it.size > 1) {
                log.info { "检测到多台显示器，开始运行后${GAME_CN_NAME}窗口不要移动到其他显示器" }
            }
        }
        if (!ConfigUtil.getBoolean(ConfigEnum.INIT_CREATE_AOT_CACHE) && BuildInfo.SOFT_RUN_MODE === SoftRunMode.JAR && !File(
                AOT_FILE_PATH
            ).exists()
        ) {
            runUI {
                AOTUtil.buildAOTAlert().getOrNull()?.show()
            }
            ConfigUtil.putBoolean(ConfigEnum.INIT_CREATE_AOT_CACHE, true)
        }
    }

    private fun afterShowing() {
        submitExtra {
            // Start decisions must not depend on native tray initialization or
            // AOT checks completing first. This also makes explicit E2E starts
            // deterministic while the normal UI remains paused by default.
            checkArg()
            if (RuntimeSafety.safeNative) {
                // Do not even construct the native tray callback structures in
                // the stability harness. Normal desktop runs still initialize
                // the tray exactly as before.
                log.info { "E2E运行：跳过系统托盘结构初始化" }
            } else {
                initTrayMenu
            }
            checkSystem()
            val softProtectedMode = ConfigExUtil.getSoftProtectedMode()
            log.info { "软件保护模式: ${softProtectedMode.comment}" }
            if (RuntimeSafety.safeNative) {
                log.info { "E2E运行：跳过原生目录保护调用" }
            } else {
                when (softProtectedMode) {
                    SoftProtectedModeEnum.NORMAL -> {
                        CSystemDll.INSTANCE.protectDirectory(PROTECT_PATH, false)
                    }

                    SoftProtectedModeEnum.STRONG -> {
                        CSystemDll.INSTANCE.protectDirectory(PROTECT_PATH, true)
                    }

                    SoftProtectedModeEnum.NONE -> {
                        CSystemDll.INSTANCE.unprotectDirectory(PROTECT_PATH)
                    }
                }
            }
            go {
                Thread.sleep(1000)
                WindowUtil.hideLaunchPage()
            }
            if (aoting && !RuntimeSafety.safeNative) {
                runUI {
                    WindowUtil.showStage(WindowEnum.SETTINGS, WindowUtil.getStage(WindowEnum.MAIN))
                }
                go {
                    Thread.sleep(5000)
                    exitProcess(0)
                }
            } else if (aoting) {
                log.warn { "E2E运行：忽略AOT模式的定时退出，避免测试进程在对局中自行关闭" }
            }
        }
    }
}
