package club.xiaojiawei.hsscript

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.spi.FilterReply
import club.xiaojiawei.hsscript.consts.ARG_AOT
import club.xiaojiawei.hsscript.consts.PROGRAM_NAME
import club.xiaojiawei.hsscript.enums.MouseControlModeEnum
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.status.E2ETrace
import club.xiaojiawei.hsscript.status.LifecycleTrace
import club.xiaojiawei.hsscript.status.RuntimeContractTrace
import club.xiaojiawei.hsscript.utils.ConfigExUtil
import club.xiaojiawei.hsscript.utils.ExistingInstanceSignal
import club.xiaojiawei.hsscript.utils.WindowUtil
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinError
import javafx.application.Application
import javafx.application.Platform
import org.slf4j.LoggerFactory
import java.io.File


/**
 * @author 肖嘉威
 * @date 2024/10/14 17:42
 */
fun main(args: Array<String>) {
    System.setProperty("jna.library.path", "lib")
    // MESSAGE mode has a safe Java input path and must not load the legacy
    // interception bridge by default. DRIVE mode remains opt-in and keeps
    // the native driver path unless the caller explicitly overrides it.
    if (System.getProperty("hs.script.safe-native") == null &&
        runCatching { ConfigExUtil.getMouseControlMode() === MouseControlModeEnum.MESSAGE }.getOrDefault(false)
    ) {
        System.setProperty("hs.script.safe-native", "true")
        System.err.println("SAFE_NATIVE_DEFAULT enabled for MESSAGE mouse mode")
    }
    ScriptStatus.aotMode = args.any { it.startsWith(ARG_AOT) }

    val e2eRun = System.getProperty("hs.script.e2e") == "true"
    val hasProgramLock = if (e2eRun) {
        // The E2E runner already owns the process lifetime.  Avoid the one-shot
        // JNA CreateMutex call so the stability test can prove whether later
        // native calls are responsible for an external ExitProcess(0).
        System.err.println("E2E_NATIVE_SKIP CreateMutex")
        true
    } else {
        createProgramLock()
    }

    if (!hasProgramLock && !ScriptStatus.aotMode){
        ExistingInstanceSignal.requestShowMain()
        WindowUtil.hideLaunchPage()
        return
    }

    setLogPath()
    RuntimeContractTrace.emit()

    // Keep failures that happen outside the application's normal LRunnable
    // wrapper visible.  In particular, a JavaFX/JNA/native failure can make
    // the GUI disappear while leaving no useful application-level message.
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            val logger = LoggerFactory.getLogger("UncaughtException")
            logger.error("线程异常退出: ${thread.name}", throwable)
        } catch (_: Throwable) {
            throwable.printStackTrace()
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            System.err.println(
                "E2E_SHUTDOWN_HOOK pid=${ProcessHandle.current().pid()} result=${E2ETrace.resultRecorded}"
            )
            LoggerFactory.getLogger("ShutdownTrace").warn(
                "JVM 正常执行关闭钩子，进程即将退出，pid=${ProcessHandle.current().pid()}"
            )
        } catch (_: Throwable) {
            // Logging may already be unavailable during JVM teardown.
        }
    })

    ScriptStatus.programArgs = args.toList()

    // The application manages its windows by hiding them and keeps the
    // background script/services alive after the main stage is hidden. JavaFX
    // otherwise exits the toolkit when the last stage is no longer showing,
    // which makes the process disappear cleanly (exit code 0) without an
    // exception or an explicit shutdown request.
    Platform.setImplicitExit(false)
    LifecycleTrace.mark("javafx-implicit-exit-disabled")

    if (System.getProperty("hs.script.e2e") == "true") {
        Thread {
            while (!E2ETrace.resultRecorded) {
                try {
                    Thread.sleep(10_000)
                    System.err.println(
                        "E2E_KEEPALIVE ts=${System.currentTimeMillis()} pid=${ProcessHandle.current().pid()} " +
                            "thread=${Thread.currentThread().name} state=${Thread.currentThread().state} " +
                            "result=${E2ETrace.resultRecorded}"
                    )
                } catch (error: InterruptedException) {
                    System.err.println("E2E_KEEPALIVE_INTERRUPTED pid=${ProcessHandle.current().pid()}")
                    return@Thread
                } catch (error: Throwable) {
                    System.err.println(
                        "E2E_KEEPALIVE_FAILED pid=${ProcessHandle.current().pid()} " +
                            "error=${error.javaClass.name}:${error.message}"
                    )
                    throw error
                }
            }
        }.apply {
            name = "E2E Keepalive"
            isDaemon = false
            start()
        }
    }
    System.err.println("E2E_LAUNCH entering JavaFX Application.launch pid=${ProcessHandle.current().pid()}")
    try {
        Application.launch(MainApplication::class.java, *args)
    } finally {
        LifecycleTrace.mark("Application.launch-returned")
        System.err.println("E2E_LAUNCH Application.launch returned pid=${ProcessHandle.current().pid()}")
    }

    // JavaFX can return after an unexpected Platform.exit/window teardown even
    // though the log listeners still have useful work to do.  Keep the E2E
    // process anchored until the script itself records a completed game; this
    // prevents the external watchdog from restarting Java in the middle of a
    // real match and attaching a second listener to the same Power.log.
    if (e2eRun) {
        while (!E2ETrace.resultRecorded) {
            try {
                Thread.sleep(1_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        System.err.println("E2E_LAUNCH E2E result recorded; allowing process exit pid=${ProcessHandle.current().pid()}")
    }
}

private fun setLogPath() {
    try {
        val context = LoggerFactory.getILoggerFactory()
        if (context is LoggerContext) {
            val logbackConfigFile = File("logback.xml")
            if (logbackConfigFile.exists()) {
                val configurator = JoranConfigurator()
                configurator.context = context
                context.reset()
                configurator.doConfigure(logbackConfigFile)
            }

            val appender = context.getLogger("ROOT").getAppender("file_async")
            if (appender is AsyncAppender) {
                for (iteratorForAppender in appender.iteratorForAppenders()) {
                    if (iteratorForAppender.name == "file") {
                        iteratorForAppender.addFilter(object : ThresholdFilter() {
                            override fun decide(iLoggingEvent: ILoggingEvent): FilterReply {
                                return if (iLoggingEvent.level.toInt() >= ScriptStatus.fileLogLevel) FilterReply.ACCEPT else FilterReply.DENY
                            }
                        })
                        break
                    }
                }
            }
        }

    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun createProgramLock(): Boolean {
    val name = "${PROGRAM_NAME}.lock"

    val h = Kernel32.INSTANCE.CreateMutex(null, true, name)

    return when (Kernel32.INSTANCE.GetLastError()) {
        WinError.ERROR_ALREADY_EXISTS -> false
        else -> true
    }
}
