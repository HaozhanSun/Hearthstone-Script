package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.consts.ROOT_PATH
import club.xiaojiawei.hsscriptbase.config.log
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches the runtime root for an atomic strategy-refresh request written by
 * refresh-strategy-plugin.ps1. The request only queues a refresh; the
 * coordinator applies it at the next safe turn boundary.
 */
object StrategyRefreshCommandWatcher {

    const val REQUEST_FILE_NAME = "strategy-refresh.request"

    private val started = AtomicBoolean(false)
    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "Strategy Refresh Command Watcher").apply { isDaemon = true }
        }.also { service ->
            service.scheduleWithFixedDelay(::poll, 1, 1, TimeUnit.SECONDS)
        }
        log.info { "STRATEGY_REFRESH_WATCHER_STARTED path=${requestPath()}" }
    }

    internal fun requestPath(rootPath: String = ROOT_PATH): Path =
        Path.of(rootPath, REQUEST_FILE_NAME)

    internal fun parseReason(content: String): String =
        content.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.take(240)
            ?.ifBlank { "external-strategy-refresh" }
            ?: "external-strategy-refresh"

    internal fun consumeRequest(
        path: Path = requestPath(),
        enqueue: (String) -> Long = { reason ->
            DeckStrategyManager.requestStrategyRefresh("external:$reason")
        },
    ): Long? {
        if (!Files.isRegularFile(path)) return null
        try {
            val content = Files.readString(path, StandardCharsets.UTF_8)
            Files.deleteIfExists(path)
            val reason = parseReason(content)
            val requestId = enqueue(reason)
            log.info {
                "STRATEGY_REFRESH_COMMAND_CONSUMED requestId=$requestId path=$path reason=$reason"
            }
            return requestId
        } catch (error: Throwable) {
            log.warn(error) {
                "STRATEGY_REFRESH_COMMAND_FAILED path=$path error=${error.javaClass.simpleName}"
            }
            return null
        }
    }

    private fun poll() {
        consumeRequest()
    }
}
