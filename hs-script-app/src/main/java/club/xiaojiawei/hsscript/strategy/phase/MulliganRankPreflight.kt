package club.xiaojiawei.hsscript.strategy.phase

import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.status.surrender.SurrenderRuleResult
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

internal enum class MulliganRankPreflightState {
    IDLE,
    WAITING_FOR_RANK,
    RETRYING,
    RESOLVED,
    EXHAUSTED,
    CANCELLED,
    SURRENDER_REQUESTED,
}

internal data class MulliganRankPreflightConfig(
    val initialDelayMs: Long = property("hs.script.mulligan.rank.initial-delay-ms", 7_000L),
    val retryIntervalMs: Long = property("hs.script.mulligan.rank.retry-interval-ms", 7_000L),
    val maxAttempts: Int = property("hs.script.mulligan.rank.max-attempts", 3).coerceAtLeast(1),
    val attemptTimeoutMs: Long = property("hs.script.mulligan.rank.attempt-timeout-ms", 5_000L),
) {
    init {
        require(initialDelayMs >= 0)
        require(retryIntervalMs >= 0)
        require(attemptTimeoutMs > 0)
    }

    /**
     * PaddleX may perform two serialized rank probes, each with a bounded
     * queue wait and provider request. Do not let the older 5s task timeout
     * cancel a valid cold start before the provider's own deadline.
     */
    internal fun effectiveAttemptTimeoutMs(provider: String, paddleXRequestTimeoutMs: Long): Long {
        if (!provider.equals("PADDLEX", ignoreCase = true)) return attemptTimeoutMs
        val requestBudget = paddleXRequestTimeoutMs.coerceAtLeast(1L)
        val multiplier = 4L // two probes × (coordinator queue + provider request)
        val safetyMarginMs = 1_000L
        val paddlexBudget = if (requestBudget > (Long.MAX_VALUE - safetyMarginMs) / multiplier) {
            Long.MAX_VALUE
        } else {
            requestBudget * multiplier + safetyMarginMs
        }
        return maxOf(attemptTimeoutMs, paddlexBudget)
    }

    private companion object {
        fun property(name: String, default: Long): Long =
            System.getProperty(name)?.toLongOrNull()?.coerceAtLeast(0L) ?: default

        fun property(name: String, default: Int): Int =
            System.getProperty(name)?.toIntOrNull()?.coerceAtLeast(1) ?: default
    }
}

internal interface MulliganRankPreflightScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): ScheduledFuture<*>

    fun scheduleTimeout(delayMs: Long, task: () -> Unit): ScheduledFuture<*> = schedule(delayMs, task)

    fun submit(task: () -> Unit): Future<*>
}

internal object ProductionMulliganRankPreflightScheduler : MulliganRankPreflightScheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): ScheduledFuture<*> =
        EXTRA_THREAD_POOL.schedule({ task() }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    override fun scheduleTimeout(delayMs: Long, task: () -> Unit): ScheduledFuture<*> = schedule(delayMs, task)

    override fun submit(task: () -> Unit): Future<*> = EXTRA_THREAD_POOL.submit { task() }
}

/**
 * Runs the pre-mulligan rank policy away from the Power.log listener.
 *
 * A slow sidecar must never hold the log stream hostage or prevent the
 * independent mulligan action from reaching its seven-second grace point.
 * Each read has its own cancellation deadline; retries are finite and every
 * callback rechecks the live phase before dispatching a surrender.
 */
internal class MulliganRankPreflight(
    private val config: MulliganRankPreflightConfig = MulliganRankPreflightConfig(),
    private val scheduler: MulliganRankPreflightScheduler = ProductionMulliganRankPreflightScheduler,
    private val paddleXRequestTimeoutMs: () -> Long = OcrRuntime::paddleXRequestTimeoutMs,
    private val isEligible: () -> Boolean,
    private val inspect: () -> SurrenderRuleResult?,
    private val isResolved: () -> Boolean = { false },
    private val provider: () -> String,
    private val onSurrender: (SurrenderRuleResult) -> Unit,
    private val onContinue: () -> Unit,
) {
    private val lock = Any()
    private var generation = 0L
    private var attempt = 0
    private var state = MulliganRankPreflightState.IDLE
    private var nextTask: Future<*>? = null
    private var activeAttempt: Attempt? = null

    fun start() {
        synchronized(lock) {
            if (state != MulliganRankPreflightState.IDLE) {
                log.debug {
                    "MULLIGAN_RANK_PREFLIGHT_START_IGNORED state=$state attempt=$attempt " +
                        "reason=already-started action=NO_DUPLICATE_SCHEDULE"
                }
                return
            }
            cancelLocked()
            generation++
            attempt = 0
            state = MulliganRankPreflightState.WAITING_FOR_RANK
            log.info {
                "MULLIGAN_RANK_PREFLIGHT_SCHEDULED delayMs=${config.initialDelayMs} " +
                    "retryIntervalMs=${config.retryIntervalMs} maxAttempts=${config.maxAttempts} " +
                    "attemptTimeoutMs=${config.attemptTimeoutMs} provider=${provider()} action=WAIT pause=false"
            }
            scheduleNextLocked(generation, config.initialDelayMs)
        }
    }

    fun cancel(reason: String) {
        synchronized(lock) {
            generation++
            cancelLocked()
            state = MulliganRankPreflightState.CANCELLED
            log.info {
                "MULLIGAN_RANK_PREFLIGHT_CANCELLED reason=$reason attempt=$attempt " +
                    "provider=${provider()} action=NO_ACTION pause=false"
            }
        }
    }

    internal fun snapshot(): MulliganRankPreflightSnapshot = synchronized(lock) {
        MulliganRankPreflightSnapshot(state, attempt)
    }

    private fun scheduleNextLocked(expectedGeneration: Long, delayMs: Long) {
        nextTask = scheduler.schedule(delayMs) {
            startAttempt(expectedGeneration)
        }
    }

    private fun startAttempt(expectedGeneration: Long) {
        val attemptNumber: Int
        lateinit var providerName: String
        var attemptTimeoutMs = config.attemptTimeoutMs
        synchronized(lock) {
            if (expectedGeneration != generation || state == MulliganRankPreflightState.CANCELLED) return
            if (!isEligible()) {
                state = MulliganRankPreflightState.CANCELLED
                log.info {
                    "MULLIGAN_RANK_PREFLIGHT_CANCELLED reason=phase-or-input-left " +
                        "attempt=$attempt provider=${provider()} action=NO_ACTION pause=false"
                }
                return
            }
            attemptNumber = ++attempt
            state = MulliganRankPreflightState.RETRYING
            nextTask = null
            providerName = provider()
            attemptTimeoutMs = config.effectiveAttemptTimeoutMs(
                providerName,
                if (providerName.equals("PADDLEX", ignoreCase = true)) paddleXRequestTimeoutMs() else 0L,
            )
            log.info {
                "MULLIGAN_RANK_PREFLIGHT_RETRY attempt=$attemptNumber maxAttempts=${config.maxAttempts} " +
                    "provider=$providerName timeoutMs=$attemptTimeoutMs action=INSPECT pause=false"
            }
        }

        val context = Attempt(expectedGeneration, attemptNumber, attemptTimeoutMs)
        synchronized(lock) {
            if (expectedGeneration != generation) return
            activeAttempt = context
            context.worker = scheduler.submit {
                if (!context.workerStarted.compareAndSet(false, true) || context.completed.get()) return@submit
                var result: SurrenderRuleResult? = null
                try {
                    result = inspect()
                } catch (error: Throwable) {
                    if (OcrRuntime.isCancellation(error)) {
                        log.info {
                            "MULLIGAN_RANK_PREFLIGHT_CANCELLED attempt=$attemptNumber " +
                                "provider=${provider()} reason=${error.javaClass.simpleName} action=NO_ACTION"
                        }
                    } else {
                        log.warn(error) {
                            "MULLIGAN_RANK_PREFLIGHT_ERROR attempt=$attemptNumber " +
                                "provider=${provider()} reason=${error.javaClass.simpleName} " +
                                "action=RETRY pause=false"
                        }
                    }
                } finally {
                    complete(context, result, timeout = context.timeoutRequested.get())
                }
            }
            context.timeout = scheduler.scheduleTimeout(attemptTimeoutMs) {
                if (context.completed.get()) return@scheduleTimeout
                context.timeoutRequested.set(true)
                context.worker?.cancel(true)
                // If cancellation won before the task entered its body there
                // will be no worker finally-block to finish this attempt.
                if (!context.workerStarted.get()) complete(context, null, timeout = true)
            }
        }
    }

    private fun complete(context: Attempt, result: SurrenderRuleResult?, timeout: Boolean) {
        synchronized(lock) {
            if (!context.completed.compareAndSet(false, true)) return
            if (context.expectedGeneration != generation || activeAttempt !== context) return
            context.timeout?.cancel(false)
            activeAttempt = null

            if (!isEligible()) {
                state = MulliganRankPreflightState.CANCELLED
                log.info {
                    "MULLIGAN_RANK_PREFLIGHT_CANCELLED reason=phase-or-input-left " +
                        "attempt=${context.attemptNumber} provider=${provider()} action=NO_ACTION pause=false"
                }
                return
            }

            if (timeout) {
                log.warn {
                    "MULLIGAN_RANK_PREFLIGHT_TIMEOUT attempt=${context.attemptNumber} " +
                    "timeoutMs=${context.timeoutMs} provider=${provider()} " +
                        "action=RETRY pause=false"
                }
            }

            if (!timeout && result?.ruleId == "rank-continue-authorized") {
                state = MulliganRankPreflightState.RESOLVED
                log.info {
                    "MULLIGAN_RANK_PREFLIGHT_RESOLVED attempt=${context.attemptNumber} " +
                        "provider=${provider()} action=CONTINUE_MULLIGAN pause=false"
                }
                onContinue()
                return
            }

            if (result?.shouldSurrender == true) {
                state = MulliganRankPreflightState.SURRENDER_REQUESTED
                log.warn {
                    "MULLIGAN_RANK_PREFLIGHT_DECISION attempt=${context.attemptNumber} " +
                        "provider=${provider()} action=SURRENDER pause=false rule=${result.ruleId}"
                }
                onSurrender(result)
                return
            }

            if (context.attemptNumber < config.maxAttempts) {
                state = MulliganRankPreflightState.WAITING_FOR_RANK
                log.info {
                    "MULLIGAN_RANK_PREFLIGHT_WAITING attempt=${context.attemptNumber} " +
                        "provider=${provider()} action=RETRY pause=false " +
                        "reason=${if (timeout) "timeout" else "empty-or-safe"}"
                }
                scheduleNextLocked(context.expectedGeneration, config.retryIntervalMs)
            } else {
                state = MulliganRankPreflightState.EXHAUSTED
                val failClosed = SurrenderRuleResult(
                    ruleId = "rank-ocr-unresolved",
                    matched = false,
                    shouldSurrender = true,
                    reason = "rank-ocr-unresolved attempts=${context.attemptNumber}",
                    blocksAutomaticSurrender = false,
                )
                log.warn {
                    "MULLIGAN_RANK_PREFLIGHT_EXHAUSTED attempt=${context.attemptNumber} " +
                        "maxAttempts=${config.maxAttempts} provider=${provider()} " +
                        "action=SURRENDER pause=false surrender=true rule=${failClosed.ruleId} " +
                        "reason=${failClosed.reason}"
                }
                state = MulliganRankPreflightState.SURRENDER_REQUESTED
                onSurrender(failClosed)
            }
        }
    }

    private fun cancelLocked() {
        nextTask?.cancel(true)
        nextTask = null
        activeAttempt?.let { context ->
            context.completed.set(true)
            context.timeout?.cancel(true)
            context.worker?.cancel(true)
        }
        activeAttempt = null
    }

    private class Attempt(
        val expectedGeneration: Long,
        val attemptNumber: Int,
        val timeoutMs: Long,
        val completed: AtomicBoolean = AtomicBoolean(false),
        val workerStarted: AtomicBoolean = AtomicBoolean(false),
        val timeoutRequested: AtomicBoolean = AtomicBoolean(false),
        var worker: Future<*>? = null,
        var timeout: ScheduledFuture<*>? = null,
    )
}

internal data class MulliganRankPreflightSnapshot(
    val state: MulliganRankPreflightState,
    val attempts: Int,
)
