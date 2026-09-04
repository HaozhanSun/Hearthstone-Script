package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscriptbase.const.BuildChannel
import club.xiaojiawei.hsscriptbase.const.BuildInfo
import club.xiaojiawei.hsscript.enums.WindowEnum
import club.xiaojiawei.hsscript.status.LifecycleTrace
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread

/**
 * Small, dependency-free handoff between a second launcher invocation and the
 * already-running process.  The application intentionally keeps its process
 * alive after the main window is closed, so a second click must wake that
 * process instead of silently exiting behind the mutex check.
 */
object ExistingInstanceSignal {
    private val requestPath: Path by lazy {
        requestPathForChannel(BuildInfo.RELEASE_CHANNEL)
    }

    internal fun requestPathForChannel(channel: String?): Path =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "hs-script-show-main.${BuildChannel.identityToken(channel)}.request",
        )

    fun requestShowMain() {
        runCatching {
            Files.writeString(
                requestPath,
                "${System.currentTimeMillis()} pid=${ProcessHandle.current().pid()}",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            System.err.println("INSTANCE_SIGNAL request-show-main path=$requestPath")
        }.onFailure {
            System.err.println("INSTANCE_SIGNAL request-failed error=${it.javaClass.name}:${it.message}")
        }
    }

    fun startWatcher() {
        thread(
            start = true,
            isDaemon = true,
            name = "Existing-instance signal watcher",
        ) {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    if (Files.deleteIfExists(requestPath)) {
                        System.err.println("INSTANCE_SIGNAL received-show-main path=$requestPath")
                        javafx.application.Platform.runLater {
                            runCatching {
                                WindowUtil.showStage(WindowEnum.MAIN)
                                LifecycleTrace.mark("instance-signal-show-main")
                            }.onFailure {
                                System.err.println(
                                    "INSTANCE_SIGNAL show-failed error=${it.javaClass.name}:${it.message}"
                                )
                            }
                        }
                    }
                    Thread.sleep(350)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@thread
                } catch (error: Throwable) {
                    System.err.println(
                        "INSTANCE_SIGNAL watcher-failed error=${error.javaClass.name}:${error.message}"
                    )
                    Thread.sleep(1_000)
                }
            }
        }
    }
}
