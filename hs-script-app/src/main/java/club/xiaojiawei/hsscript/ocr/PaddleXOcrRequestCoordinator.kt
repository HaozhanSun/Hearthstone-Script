package club.xiaojiawei.hsscript.ocr

import club.xiaojiawei.hsscriptbase.config.log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * The single JVM gate for PaddleX requests.  Screen recovery, lifecycle
 * probes, and rank preflight all reach PaddleX through this coordinator.
 */
internal object PaddleXOcrRequestCoordinator {
    private val lock = ReentrantLock()
    private val requestSequence = AtomicLong(0L)

    fun <T> execute(
        desc: String,
        roi: String?,
        timeoutMs: Long,
        action: (requestId: Long) -> T,
    ): T {
        val requestId = requestSequence.incrementAndGet()
        val queuedAt = System.nanoTime()
        val acquired = try {
            lock.tryLock(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PaddleXOcrCancelledException("PaddleX OCR request was cancelled while queued", error)
        }
        if (!acquired) {
            val queueWaitMs = elapsedMs(queuedAt)
            log.warn {
                "PADDLEX_OCR_QUEUE_TIMEOUT requestId=$requestId queueWaitMs=$queueWaitMs " +
                    "timeoutMs=$timeoutMs desc=${desc.ifBlank { "<none>" }} roi=${roi ?: "none"} " +
                    "provider=PADDLEX action=REJECT"
            }
            throw PaddleXOcrException("PaddleX OCR request queue timed out after ${timeoutMs}ms")
        }

        val queueWaitMs = elapsedMs(queuedAt)
        val startedAt = System.nanoTime()
        log.info {
            "PADDLEX_OCR_REQUEST_STARTED requestId=$requestId queueWaitMs=$queueWaitMs " +
                "desc=${desc.ifBlank { "<none>" }} roi=${roi ?: "none"} provider=PADDLEX action=OCR"
        }
        try {
            return action(requestId).also {
                log.info {
                    "PADDLEX_OCR_REQUEST_COMPLETED requestId=$requestId queueWaitMs=$queueWaitMs " +
                        "durationMs=${elapsedMs(startedAt)} desc=${desc.ifBlank { "<none>" }} " +
                        "roi=${roi ?: "none"} provider=PADDLEX action=OCR"
                }
            }
        } catch (error: Throwable) {
            log.warn(error) {
                "PADDLEX_OCR_REQUEST_FAILED requestId=$requestId queueWaitMs=$queueWaitMs " +
                    "durationMs=${elapsedMs(startedAt)} desc=${desc.ifBlank { "<none>" }} " +
                    "roi=${roi ?: "none"} provider=PADDLEX action=OCR " +
                    "reason=${error.javaClass.simpleName}"
            }
            throw error
        } finally {
            lock.unlock()
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).coerceAtLeast(0L)
}
