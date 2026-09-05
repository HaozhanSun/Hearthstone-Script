package club.xiaojiawei.hsscript.strategy.phase

import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.surrender.SurrenderRuleResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Delayed
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MulliganRankPreflightTest {

    @BeforeEach
    fun resetPauseState() {
        PauseStatus.isPause = false
    }

    @Test
    fun `retries after seven second grace without another Power log line`() {
        val scheduler = ManualScheduler()
        val attempts = mutableListOf<Long>()
        var continueCount = 0
        var logLines = listOf("MULLIGAN_STATE=INPUT", "MULLIGAN_STATE=INPUT")
        val gate = MulliganActionGate()
        var changeCardSchedules = 0

        logLines.forEach {
            if (gate.tryReserve { true }) changeCardSchedules++
        }
        assertEquals(1, changeCardSchedules, "duplicate INPUT must schedule changeCard once")

        val preflight = MulliganRankPreflight(
            config = MulliganRankPreflightConfig(
                initialDelayMs = 7_000,
                retryIntervalMs = 7_000,
                maxAttempts = 3,
                attemptTimeoutMs = 5_000,
            ),
            scheduler = scheduler,
            isEligible = { true },
            inspect = {
                attempts += scheduler.now
                null
            },
            provider = { "PADDLEX" },
            onSurrender = { result -> throw AssertionError("safe fixture must not surrender: $result") },
            onContinue = { continueCount++ },
        )

        preflight.start()
        scheduler.runScheduledAfter(7_000)
        scheduler.runWorker()
        scheduler.runScheduledAfter(7_000)
        scheduler.runWorker()
        scheduler.runScheduledAfter(7_000)
        scheduler.runWorker()

        assertEquals(listOf(7_000L, 14_000L, 21_000L), attempts)
        assertEquals(1, continueCount)
        assertEquals(MulliganRankPreflightState.EXHAUSTED, preflight.snapshot().state)
        assertFalse(PauseStatus.isPause)
        logLines = emptyList()
        assertTrue(logLines.isEmpty(), "the retry schedule must not depend on a new log line")
    }

    @Test
    fun `timeout is bounded and fails soft without pausing`() {
        val scheduler = ManualScheduler()
        var continueCount = 0
        val preflight = MulliganRankPreflight(
            config = MulliganRankPreflightConfig(
                initialDelayMs = 7_000,
                retryIntervalMs = 7_000,
                maxAttempts = 1,
                attemptTimeoutMs = 5_000,
            ),
            scheduler = scheduler,
            isEligible = { true },
            inspect = { error("slow OCR should be cancelled before returning") },
            provider = { "PADDLEX" },
            onSurrender = { result -> throw AssertionError("timeout must not surrender: $result") },
            onContinue = { continueCount++ },
        )

        preflight.start()
        scheduler.runScheduledAfter(7_000)
        scheduler.runTimeout()

        assertEquals(1, continueCount)
        assertEquals(MulliganRankPreflightState.EXHAUSTED, preflight.snapshot().state)
        assertFalse(PauseStatus.isPause)
    }

    @Test
    fun `empty unknown and exception reads exhaust into continue mulligan`() {
        val outcomes = listOf("empty", "UNKNOWN", "PaddleOCR exception")
        outcomes.forEach { outcome ->
            val scheduler = ManualScheduler()
            var attempts = 0
            var continueCount = 0
            val preflight = MulliganRankPreflight(
                config = MulliganRankPreflightConfig(
                    initialDelayMs = 7_000,
                    retryIntervalMs = 7_000,
                    maxAttempts = 3,
                    attemptTimeoutMs = 5_000,
                ),
                scheduler = scheduler,
                isEligible = { true },
                inspect = {
                    attempts++
                    if (outcome == "PaddleOCR exception") throw IllegalStateException(outcome)
                    null
                },
                provider = { "PADDLEX" },
                onSurrender = { result -> throw AssertionError("$outcome must not surrender: $result") },
                onContinue = { continueCount++ },
            )

            preflight.start()
            repeat(3) {
                scheduler.runScheduledAfter(7_000)
                scheduler.runWorker()
            }

            assertEquals(3, attempts, outcome)
            assertEquals(1, continueCount, outcome)
            assertEquals(MulliganRankPreflightState.EXHAUSTED, preflight.snapshot().state, outcome)
            assertFalse(PauseStatus.isPause, outcome)
        }
    }

    @Test
    fun `unsafe rank requests surrender once and closes the action gate`() {
        val scheduler = ManualScheduler()
        val surrenderRequested = AtomicBoolean(false)
        val gate = MulliganActionGate()
        var changeCardSchedules = 0
        assertTrue(gate.tryReserve { true }.also { if (it) changeCardSchedules++ })

        val preflight = MulliganRankPreflight(
            config = MulliganRankPreflightConfig(initialDelayMs = 7_000, maxAttempts = 3),
            scheduler = scheduler,
            isEligible = { !surrenderRequested.get() },
            inspect = {
                SurrenderRuleResult(
                    ruleId = "current-rank-is-not-target",
                    matched = false,
                    shouldSurrender = true,
                    reason = "current-rank=8 target-ranks=5,10",
                )
            },
            provider = { "PADDLEX" },
            onSurrender = { surrenderRequested.set(true) },
            onContinue = { error("unsafe rank must not continue") },
        )

        preflight.start()
        scheduler.runScheduledAfter(7_000)
        scheduler.runWorker()

        assertEquals(1, changeCardSchedules)
        assertTrue(surrenderRequested.get())
        assertEquals(MulliganRankPreflightState.SURRENDER_REQUESTED, preflight.snapshot().state)
        assertFalse(gate.tryReserve { !surrenderRequested.get() })
        assertFalse(PauseStatus.isPause)
    }

    @Test
    fun `phase transition cancels pending retry and historical screenshot is retained`() {
        val screenshot = Path.of(
            "C:/Users/yzjsh/Documents/Codex/2026-08-15/for-all-these-delay-short-are-2/" +
                "outputs/Hearthstone Script/log/mulligan/" +
                "game-0001-before-selection-20260901-054413-930.png",
        )
        assertTrue(Files.isRegularFile(screenshot), "retained screenshot fixture must exist")
        assertNotNull(javax.imageio.ImageIO.read(screenshot.toFile()))

        val scheduler = ManualScheduler()
        var phaseActive = true
        var continueCount = 0
        val preflight = MulliganRankPreflight(
            config = MulliganRankPreflightConfig(initialDelayMs = 7_000, maxAttempts = 3),
            scheduler = scheduler,
            isEligible = { phaseActive },
            inspect = { null },
            provider = { "PADDLEX" },
            onSurrender = { result -> throw AssertionError("phase transition must not surrender: $result") },
            onContinue = { continueCount++ },
        )

        preflight.start()
        phaseActive = false
        scheduler.runScheduledAfter(7_000)
        preflight.cancel("phase-transition")

        assertEquals(0, continueCount)
        assertEquals(MulliganRankPreflightState.CANCELLED, preflight.snapshot().state)
        assertFalse(PauseStatus.isPause)
    }

    private class ManualScheduler : MulliganRankPreflightScheduler {
        private data class Entry(
            val dueAt: Long,
            val kind: Kind,
            val task: () -> Unit,
            val future: ManualFuture,
        )

        private enum class Kind { SCHEDULED, WORKER, TIMEOUT }

        private val entries = mutableListOf<Entry>()
        var now: Long = 0
            private set

        override fun schedule(delayMs: Long, task: () -> Unit): ScheduledFuture<*> =
            add(delayMs, if (delayMs == 5_000L) Kind.TIMEOUT else Kind.SCHEDULED, task)

        override fun submit(task: () -> Unit): Future<*> = add(0, Kind.WORKER, task)

        fun runScheduledAfter(delayMs: Long) {
            val target = now + delayMs
            val entry = entries.firstOrNull { it.kind == Kind.SCHEDULED && it.dueAt == target }
                ?: error("no scheduled task at +$delayMs ms; entries=${entries.map { it.kind to it.dueAt }}")
            run(entry)
        }

        fun runWorker() {
            val entry = entries.firstOrNull {
                it.kind == Kind.WORKER && !it.future.isCancelled && !it.future.isDone
            }
                ?: error("no worker task; entries=${entries.map { it.kind to it.dueAt }}")
            run(entry)
        }

        fun runTimeout() {
            val entry = entries.firstOrNull {
                it.kind == Kind.TIMEOUT && !it.future.isCancelled && !it.future.isDone
            }
                ?: error("no timeout task; entries=${entries.map { it.kind to it.dueAt }}")
            run(entry)
        }

        private fun add(delayMs: Long, kind: Kind, task: () -> Unit): ManualFuture {
            val future = ManualFuture()
            entries += Entry(now + delayMs, kind, task, future)
            return future
        }

        private fun run(entry: Entry) {
            now = maxOf(now, entry.dueAt)
            if (entry.future.isCancelled) return
            entry.task()
            entry.future.complete()
        }
    }

    private class ManualFuture : ScheduledFuture<Unit> {
        private var cancelled = false
        private var done = false

        fun complete() {
            done = true
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelled = true
            done = true
            return true
        }

        override fun isCancelled(): Boolean = cancelled
        override fun isDone(): Boolean = done
        override fun get(): Unit = Unit
        override fun get(timeout: Long, unit: TimeUnit): Unit = Unit
        override fun getDelay(unit: TimeUnit): Long = 0
        override fun compareTo(other: Delayed): Int = 0
    }
}
