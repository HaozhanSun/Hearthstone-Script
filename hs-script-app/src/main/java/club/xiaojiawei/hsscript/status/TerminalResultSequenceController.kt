package club.xiaojiawei.hsscript.status

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps terminal-page Continue inputs on an independent retry clock while one
 * serial observer classifies the known defeat -> rank-progress -> destination flow.
 */
internal class TerminalResultSequenceController(
    private val observerExecutor: ScheduledExecutorService,
    private val inputExecutor: ScheduledExecutorService,
    initialInputCount: Int,
    private val initialRetryIntervalMillis: Long,
    private val retryIntervalMillis: () -> Long,
    private val observerIntervalMillis: Long,
    private val monitorIntervalMillis: Long,
    private val maxDurationMillis: Long,
    private val observe: () -> ResultScreenObservation,
    private val terminalProofValid: () -> Boolean,
    private val paused: () -> Boolean,
    private val sameGame: () -> Boolean,
    private val dispatchContinue: (Page, Int, plannedIntervalMillis: Long, recordDispatch: () -> Long?) -> DispatchOutcome,
    private val confirmDestination: (ResultScreenObservation) -> Boolean,
    private val onStopped: (reason: String, destinationConfirmed: Boolean) -> Unit,
) {
    enum class Page { DEFEAT, RANK_PROGRESS }

    /** Separates an attempted input from the OS input API's dispatch result. */
    data class DispatchOutcome(val attempted: Boolean, val dispatchAccepted: Boolean)

    data class Snapshot(
        val stopped: Boolean,
        val page: Page,
        val inputs: Int,
        val probes: Int,
        val stopReason: String?,
        val lastDispatchAccepted: Boolean?,
    )

    companion object {
        const val MAX_CONTINUE_INPUTS = 4
        const val MAX_OBSERVATIONS = 20
        // Avoid burning the bounded 20-probe episode on repeated fast visual
        // frames before the independent Continue clock reaches its next retry.
        // The fast visual path still returns immediately, without OCR latency.
        const val MIN_OBSERVER_INTERVAL_MS = 1_500L
        const val MAX_AUTHORIZED_FRAME_AGE_MS = 25_000L
        const val DEFAULT_OBSERVER_INTERVAL_MS = MIN_OBSERVER_INTERVAL_MS
        const val DEFAULT_MONITOR_INTERVAL_MS = 50L
        const val DEFAULT_MAX_DURATION_MS = 120_000L
    }

    private val lock = Any()
    private val stopped = AtomicBoolean(false)
    private val destinationDetected = AtomicBoolean(false)
    private val inputCount = AtomicInteger(initialInputCount.coerceAtLeast(0))
    private val probeCount = AtomicInteger(0)
    private val latestObservation = java.util.concurrent.atomic.AtomicReference<ResultScreenObservation?>()
    private val latestObservationAtNanos = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    private val lastInputAtNanos = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    private val lastDispatchAccepted = java.util.concurrent.atomic.AtomicReference<Boolean?>()
    private val inputScheduled = AtomicBoolean(false)
    private val scheduledTasks = CopyOnWriteArrayList<ScheduledFuture<*>>()

    @Volatile private var page = Page.DEFEAT
    @Volatile private var stopReason: String? = null
    @Volatile private var plannedIntervalMillis = initialRetryIntervalMillis

    fun start(): List<ScheduledFuture<*>> {
        scheduleObserver()
        scheduleEligibilityMonitor()
        scheduledTasks += inputExecutor.schedule({ stop("episode-deadline", destinationConfirmed = false) }, maxDurationMillis, TimeUnit.MILLISECONDS)
        scheduleInput(initialRetryIntervalMillis)
        return scheduledTasks.toList()
    }

    fun snapshot(): Snapshot = Snapshot(stopped.get(), page, inputCount.get(), probeCount.get(), stopReason, lastDispatchAccepted.get())

    fun cancel(reason: String) = stop(reason, destinationConfirmed = false)

    private fun scheduleObserver() {
        scheduledTasks += observerExecutor.scheduleWithFixedDelay(
            { observeOnce() },
            25L,
            observerIntervalMillis.coerceAtLeast(MIN_OBSERVER_INTERVAL_MS),
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleEligibilityMonitor() {
        scheduledTasks += inputExecutor.scheduleWithFixedDelay(
            {
                when {
                    paused() -> stop("paused", destinationConfirmed = false)
                    !sameGame() -> stop("new-game-detected", destinationConfirmed = false)
                    !terminalProofValid() -> stop("terminal-proof-invalid", destinationConfirmed = false)
                }
            },
            10L,
            monitorIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun observeOnce() {
        if (stopped.get()) return
        when {
            paused() -> return stop("paused", destinationConfirmed = false)
            !sameGame() -> return stop("new-game-detected", destinationConfirmed = false)
            !terminalProofValid() -> return stop("terminal-proof-invalid", destinationConfirmed = false)
        }
        if (probeCount.incrementAndGet() > MAX_OBSERVATIONS) {
            return stop("observation-budget-exhausted", destinationConfirmed = false)
        }
        val observation = runCatching(observe).getOrElse { return }
        if (stopped.get()) return
        latestObservation.set(observation)
        latestObservationAtNanos.set(observation.capturedAtNanos)
        val destinationConfirmed = FreshPostResultDestinationPolicy.isConfirmed(
            observation.destinationScreen,
            observation.destinationConfidence,
            observation.captureAuthorized,
        )
        if (destinationConfirmed) {
            destinationDetected.set(true)
            val confirmed = runCatching { confirmDestination(observation) }.getOrDefault(false)
            return stop(
                if (confirmed) "destination-confirmed" else "destination-observed-unconfirmed",
                destinationConfirmed = confirmed,
            )
        }
        if (!observation.destinationScreen.isNullOrBlank()) {
            destinationDetected.set(true)
            return stop("positive-destination-unconfirmed", destinationConfirmed = false)
        }
        when {
            observation.rankProgressVisible -> page = Page.RANK_PROGRESS
            observation.resultVisible == true -> page = Page.DEFEAT
            // UNKNOWN keeps the known two-page sequence eligible. The timer is
            // authorized by terminal proof, not by OCR completing on time.
        }
    }

    private fun scheduleInput(delayMillis: Long, plannedMillis: Long = delayMillis) {
        if (stopped.get() || inputCount.get() >= MAX_CONTINUE_INPUTS || !inputScheduled.compareAndSet(false, true)) return
        plannedIntervalMillis = plannedMillis
        val task = inputExecutor.schedule({
            inputScheduled.set(false)
            dispatchNext()
        }, delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        scheduledTasks += task
        if (stopped.get()) task.cancel(false)
    }

    private fun dispatchNext() {
        if (stopped.get() || destinationDetected.get()) return
        when {
            paused() -> return stop("paused", destinationConfirmed = false)
            !sameGame() -> return stop("new-game-detected", destinationConfirmed = false)
            !terminalProofValid() -> return stop("terminal-proof-invalid", destinationConfirmed = false)
        }
        val attempt = synchronized(lock) {
            if (stopped.get() || destinationDetected.get()) return
            val next = inputCount.get() + 1
            if (next > MAX_CONTINUE_INPUTS) return
            next
        }
        val observed = latestObservation.get()
        val observedAt = latestObservationAtNanos.get()
        val lastInputAt = lastInputAtNanos.get()
        // After authoritative same-game terminal proof, a fresh authorized
        // frame with the calibrated Continue band is enough to retry the
        // result transition even when OCR cannot name a victory variant.
        // This is deliberately narrower than accepting UNKNOWN pixels alone:
        // the lower-center control must be visibly present in this frame.
        val visibleResultPage = observed?.captureAuthorized == true &&
            (observed.resultVisible == true || observed.rankProgressVisible || observed.continueControlVisible)
        val frameAgeNanos = System.nanoTime() - observedAt
        val frameIsCurrent = observedAt > 0L && frameAgeNanos in 0..TimeUnit.MILLISECONDS.toNanos(MAX_AUTHORIZED_FRAME_AGE_MS)
        val postInputObservation = lastInputAt == Long.MIN_VALUE || observedAt > lastInputAt
        if (!visibleResultPage || !frameIsCurrent || !postInputObservation) {
            // Never turn timer expiry, an UNKNOWN frame, or a prior pre-click
            // frame into permission to touch the client. Keep the bounded
            // worker alive until a fresh authorized result-page frame arrives.
            scheduleInput(MIN_OBSERVER_INTERVAL_MS, retryIntervalMillis().coerceAtLeast(1L))
            return
        }
        when {
            stopped.get() || destinationDetected.get() -> return
            paused() -> return stop("paused", destinationConfirmed = false)
            !sameGame() -> return stop("new-game-detected", destinationConfirmed = false)
            !terminalProofValid() -> return stop("terminal-proof-invalid", destinationConfirmed = false)
        }
        val targetPage = if (observed?.rankProgressVisible == true) Page.RANK_PROGRESS else Page.DEFEAT
        val dispatchStartedAtNanos = System.nanoTime()
        var markedDispatchAtNanos: Long? = null
        var dispatchGapMillis: Long? = null
        val outcome = runCatching {
            dispatchContinue(targetPage, attempt, plannedIntervalMillis) {
                val now = System.nanoTime()
                dispatchGapMillis = markedDispatchAtNanos?.let {
                    TimeUnit.NANOSECONDS.toMillis(now - it)
                }
                markedDispatchAtNanos = now
                dispatchGapMillis
            }
        }.getOrDefault(DispatchOutcome(attempted = false, dispatchAccepted = false))
        if (!outcome.attempted) return stop("continue-dispatch-failed", destinationConfirmed = false)
        val actualDispatchAt = markedDispatchAtNanos ?: dispatchStartedAtNanos
        lastInputAtNanos.set(actualDispatchAt)
        lastDispatchAccepted.set(outcome.dispatchAccepted)
        synchronized(lock) {
            if (stopped.get() || destinationDetected.get()) return
            inputCount.set(attempt)
            page = if (attempt == 1) Page.RANK_PROGRESS else targetPage
        }
        if (attempt < MAX_CONTINUE_INPUTS) {
            val interval = retryIntervalMillis().coerceAtLeast(1L)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - actualDispatchAt)
            scheduleInput(interval - elapsed, interval)
        }
    }

    private fun stop(reason: String, destinationConfirmed: Boolean) {
        if (!stopped.compareAndSet(false, true)) return
        stopReason = reason
        scheduledTasks.forEach { it.cancel(false) }
        inputExecutor.shutdownNow()
        runCatching { onStopped(reason, destinationConfirmed) }
    }
}
