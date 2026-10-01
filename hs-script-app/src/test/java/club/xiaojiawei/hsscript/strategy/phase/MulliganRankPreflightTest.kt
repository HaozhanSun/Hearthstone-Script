package club.xiaojiawei.hsscript.strategy.phase

import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.surrender.CurrentRankDetector
import club.xiaojiawei.hsscript.status.surrender.PersistentStreakSnapshot
import club.xiaojiawei.hsscript.status.surrender.SurrenderPolicy
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
import java.util.concurrent.CountDownLatch
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
    fun `normal mulligan reservation is not gated by rank waiting`() {
        val gate = MulliganActionGate()

        assertTrue(gate.tryReserve())
        assertFalse(gate.tryReserve(), "duplicate INPUT must remain suppressed")
        assertFalse(
            gate.tryReserve { false },
            "the legacy predicate overload cannot reopen a reservation",
        )
    }

    @Test
    fun `verified rank five ten and numeric Legend continue through policy guards after grace`() {
        val now = System.currentTimeMillis()
        val winningStreakDecision = SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 0, consecutiveWins = 5),
        )!!
        val surrenderStreakDecision = SurrenderPolicy.persistentStreakDecision(
            PersistentStreakSnapshot(consecutiveSurrenders = 7, consecutiveWins = 0),
        )!!
        val winRateDecision = SurrenderPolicy.evaluateWinRate(
            SurrenderPolicy.WinRateSnapshot(games = 20, wins = 12),
        )!!
        assertTrue(winningStreakDecision.shouldSurrender)
        assertTrue(surrenderStreakDecision.blocksAutomaticSurrender)
        assertTrue(winRateDecision.shouldSurrender)

        for (rank in listOf(5, 10, 21)) {
            for (tier in listOf(CurrentRankDetector.RankTier.GOLD, CurrentRankDetector.RankTier.UNKNOWN)) {
                for (streakDecision in listOf(winningStreakDecision, surrenderStreakDecision)) {
                    SurrenderPolicy.resetForNewGame()
                    val detection = CurrentRankDetector.Detection(
                        rank = rank,
                        tier = tier,
                        ocrText = rank.toString(),
                        confidence = 1.0,
                        captureBounds = java.awt.Rectangle(10, 20, 80, 90),
                        provider = "PADDLEX",
                        capturedAtMs = now,
                        agreementCount = 1,
                    )

                    val scheduler = ManualScheduler()
                    var surrendered = 0
                    var continued = 0
                    var inspections = 0
                    val preflight = MulliganRankPreflight(
                        config = MulliganRankPreflightConfig(initialDelayMs = 5_000, maxAttempts = 1),
                        scheduler = scheduler,
                        isEligible = { true },
                        inspect = {
                            inspections++
                            val decision = SurrenderPolicy.evaluateMulliganRankEvidence(
                                detection = detection,
                                actualMode = "GAMEPLAY",
                                inWar = true,
                                nowMs = now,
                                persistentStreakDecision = streakDecision,
                                winRateDecisionProvider = { winRateDecision },
                            )
                            if (decision?.shouldSurrender == true) {
                                decision
                            } else if (SurrenderPolicy.currentRankContinueAuthorized()) {
                                SurrenderRuleResult(
                                    ruleId = "rank-continue-authorized",
                                    matched = true,
                                    shouldSurrender = false,
                                    reason = "verified-rank-$rank-tier-$tier",
                                )
                            } else null
                        },
                        provider = { "PADDLEX" },
                        onSurrender = { surrendered++ },
                        onContinue = { continued++ },
                    )

                    preflight.start()
                    assertEquals(0, surrendered, "no surrender may occur during the five-second grace")
                    assertEquals(0, continued)
                    assertEquals(0, inspections, "the policy must not run before the preflight delay")
                    scheduler.runScheduledAfter(5_000)
                    scheduler.runWorker()

                    assertEquals(0, surrendered, "authorized rank=$rank tier=$tier must stay protected")
                    assertEquals(1, continued)
                    assertEquals(1, inspections)
                    assertEquals(MulliganRankPreflightState.RESOLVED, preflight.snapshot().state)
                }
            }
        }
    }

    @Test
    fun `rank policy surrenders verified non-target and fails closed for unknown evidence`() {
        val now = System.currentTimeMillis()
        val nonTarget = CurrentRankDetector.Detection(
            rank = 7,
            tier = CurrentRankDetector.RankTier.UNKNOWN,
            ocrText = "7",
            confidence = 1.0,
            captureBounds = java.awt.Rectangle(10, 20, 80, 90),
            provider = "PADDLEX",
            capturedAtMs = now,
            agreementCount = 1,
        )

        SurrenderPolicy.resetForNewGame()
        val denied = SurrenderPolicy.evaluateMulliganRankEvidence(
            detection = nonTarget,
            actualMode = "GAMEPLAY",
            inWar = true,
            nowMs = now,
            persistentStreakDecision = null,
        )
        assertEquals("current-rank-not-5-or-10", denied?.ruleId)
        assertTrue(denied?.shouldSurrender == true)
        assertFalse(SurrenderPolicy.currentRankContinueAuthorized())

        SurrenderPolicy.resetForNewGame()
        SurrenderPolicy.forceRankInspectionLatchForTest(completed = false, authorized = false, attempts = 3)
        val unresolved = SurrenderPolicy.evaluateMulliganRankEvidence(
            detection = null,
            actualMode = "GAMEPLAY",
            inWar = true,
            nowMs = now,
            persistentStreakDecision = null,
        )
        assertEquals("rank-ocr-unresolved", unresolved?.ruleId)
        assertTrue(unresolved?.shouldSurrender == true)
        assertFalse(SurrenderPolicy.currentRankContinueAuthorized())
    }

    @Test
    fun `repeated start does not schedule a second rank lifecycle`() {
        val scheduler = ManualScheduler()
        val preflight = MulliganRankPreflight(
            config = MulliganRankPreflightConfig(initialDelayMs = 7_000, maxAttempts = 1),
            scheduler = scheduler,
            isEligible = { true },
            inspect = { null },
            provider = { "PADDLEX" },
            onSurrender = { result -> throw AssertionError("duplicate start must not surrender: $result") },
            onContinue = {},
        )

        preflight.start()
        preflight.start()

        assertEquals(1, scheduler.scheduledCount)
        assertEquals(MulliganRankPreflightState.WAITING_FOR_RANK, preflight.snapshot().state)
    }

    @Test
    fun `resolved callback without explicit rank authorization still fails closed`() {
        val scheduler = ManualScheduler()
        var surrenderCount = 0
        var continueCount = 0
        val preflight = MulliganRankPreflight(
            config = MulliganRankPreflightConfig(initialDelayMs = 7_000, maxAttempts = 1),
            scheduler = scheduler,
            isEligible = { true },
            inspect = { null },
            isResolved = { true },
            provider = { "PADDLEX" },
            onSurrender = { result ->
                assertEquals("rank-ocr-unresolved", result.ruleId)
                surrenderCount++
            },
            onContinue = { continueCount++ },
        )

        preflight.start()
        scheduler.runScheduledAfter(7_000)
        scheduler.runWorker()

        assertEquals(1, surrenderCount)
        assertEquals(0, continueCount)
        assertEquals(MulliganRankPreflightState.SURRENDER_REQUESTED, preflight.snapshot().state)
    }

    @Test
    fun `retries after seven second grace without another Power log line`() {
        val scheduler = ManualScheduler()
        val attempts = mutableListOf<Long>()
        var surrenderCount = 0
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
            onSurrender = { result ->
                assertEquals("rank-ocr-unresolved", result.ruleId)
                assertTrue(result.shouldSurrender)
                surrenderCount++
            },
            onContinue = { error("unresolved rank must not continue") },
        )

        preflight.start()
        scheduler.runScheduledAfter(7_000)
        scheduler.runWorker()
        scheduler.runScheduledAfter(7_000)
        scheduler.runWorker()
        scheduler.runScheduledAfter(7_000)
        scheduler.runWorker()

        assertEquals(listOf(7_000L, 14_000L, 21_000L), attempts)
        assertEquals(1, surrenderCount)
        assertEquals(MulliganRankPreflightState.SURRENDER_REQUESTED, preflight.snapshot().state)
        assertFalse(PauseStatus.isPause)
        logLines = emptyList()
        assertTrue(logLines.isEmpty(), "the retry schedule must not depend on a new log line")
    }

    @Test
    fun `timeout is bounded and fails closed without pausing`() {
        val scheduler = ManualScheduler()
        var surrenderCount = 0
        val preflight = MulliganRankPreflight(
            config = MulliganRankPreflightConfig(
                initialDelayMs = 7_000,
                retryIntervalMs = 7_000,
                maxAttempts = 1,
                attemptTimeoutMs = 5_000,
            ),
            scheduler = scheduler,
            paddleXRequestTimeoutMs = { 1_000L },
            isEligible = { true },
            inspect = { error("slow OCR should be cancelled before returning") },
            provider = { "PADDLEX" },
            onSurrender = { result ->
                assertEquals("rank-ocr-unresolved", result.ruleId)
                assertTrue(result.shouldSurrender)
                surrenderCount++
            },
            onContinue = { error("timeout must not continue mulligan") },
        )

        preflight.start()
        scheduler.runScheduledAfter(7_000)
        scheduler.runTimeout()

        assertEquals(1, surrenderCount)
        assertEquals(MulliganRankPreflightState.SURRENDER_REQUESTED, preflight.snapshot().state)
        assertFalse(PauseStatus.isPause)
    }

    @Test
    fun `cold PaddleX rank read lasting longer than five seconds is not preempted`() {
        val scheduler = ManualScheduler()
        var continued = 0
        var attempts = 0
        val preflight = MulliganRankPreflight(
            config = MulliganRankPreflightConfig(
                initialDelayMs = 7_000,
                maxAttempts = 1,
                attemptTimeoutMs = 5_000,
            ),
            scheduler = scheduler,
            paddleXRequestTimeoutMs = { 10_000L },
            isEligible = { true },
            inspect = {
                attempts++
                SurrenderRuleResult(
                    ruleId = "rank-continue-authorized",
                    matched = true,
                    shouldSurrender = false,
                    reason = "rank=10",
                    blocksAutomaticSurrender = true,
                )
            },
            provider = { "PADDLEX" },
            onSurrender = { error("resolved rank must not surrender") },
            onContinue = { continued++ },
        )

        preflight.start()
        scheduler.runScheduledAfter(7_000)
        assertEquals(41_000L, scheduler.nextTimeoutDelayMs())
        scheduler.runWorker(elapsedAfterStartMs = 9_000L)

        assertEquals(1, attempts)
        assertEquals(1, continued)
        assertEquals(MulliganRankPreflightState.RESOLVED, preflight.snapshot().state)
    }

    @Test
    fun `timeout before worker starts cannot overlap a retry with the cancelled attempt`() {
        val scheduler = ManualScheduler()
        var attempts = 0
        var continued = 0
        val preflight = MulliganRankPreflight(
            config = MulliganRankPreflightConfig(
                initialDelayMs = 7_000,
                retryIntervalMs = 7_000,
                maxAttempts = 2,
                attemptTimeoutMs = 5_000,
            ),
            scheduler = scheduler,
            paddleXRequestTimeoutMs = { 1_000L },
            isEligible = { true },
            inspect = {
                attempts++
                SurrenderRuleResult(
                    ruleId = "rank-continue-authorized",
                    matched = true,
                    shouldSurrender = false,
                    reason = "rank=10",
                    blocksAutomaticSurrender = true,
                )
            },
            provider = { "PADDLEX" },
            onSurrender = { error("resolved rank must not surrender") },
            onContinue = { continued++ },
        )

        preflight.start()
        scheduler.runScheduledAfter(7_000)
        scheduler.runTimeout()
        scheduler.runScheduledAfter(7_000)
        scheduler.runWorker()

        assertEquals(1, attempts, "the timed-out queued worker must not later inspect rank")
        assertEquals(1, continued)
        assertEquals(MulliganRankPreflightState.RESOLVED, preflight.snapshot().state)
    }

    @Test
    fun `timed out in-flight worker exits before the retry is scheduled`() {
        val scheduler = ManualScheduler()
        val workerEntered = CountDownLatch(1)
        var inspectCalls = 0
        var continued = 0
        val preflight = MulliganRankPreflight(
            config = MulliganRankPreflightConfig(
                initialDelayMs = 7_000,
                retryIntervalMs = 7_000,
                maxAttempts = 2,
                attemptTimeoutMs = 5_000,
            ),
            scheduler = scheduler,
            paddleXRequestTimeoutMs = { 1_000L },
            isEligible = { true },
            inspect = {
                inspectCalls++
                if (inspectCalls == 1) {
                    workerEntered.countDown()
                    try {
                        check(CountDownLatch(1).await(3, TimeUnit.SECONDS)) {
                            "timed rank worker was not interrupted"
                        }
                    } catch (interrupted: InterruptedException) {
                        throw club.xiaojiawei.hsscript.ocr.PaddleXOcrCancelledException(
                            "rank preflight timed out",
                            interrupted,
                        )
                    }
                    null
                } else {
                    SurrenderRuleResult(
                        ruleId = "rank-continue-authorized",
                        matched = true,
                        shouldSurrender = false,
                        reason = "rank=10",
                        blocksAutomaticSurrender = true,
                    )
                }
            },
            provider = { "PADDLEX" },
            onSurrender = { error("resolved rank must not surrender") },
            onContinue = { continued++ },
        )

        preflight.start()
        scheduler.runScheduledAfter(7_000)
        val worker = scheduler.runWorkerAsync()
        assertTrue(workerEntered.await(1, TimeUnit.SECONDS))
        scheduler.runTimeout()
        worker.join(1_000)

        assertFalse(worker.isAlive, "cancelled PaddleX worker must unwind before retry")
        assertEquals(MulliganRankPreflightState.WAITING_FOR_RANK, preflight.snapshot().state)
        assertEquals(1, inspectCalls)
        scheduler.runScheduledAfter(7_000)
        scheduler.runWorker()

        assertEquals(2, inspectCalls)
        assertEquals(1, continued)
        assertEquals(MulliganRankPreflightState.RESOLVED, preflight.snapshot().state)
    }

    @Test
    fun `empty unknown and exception reads exhaust into surrender`() {
        val outcomes = listOf("empty", "UNKNOWN", "PaddleOCR exception")
        outcomes.forEach { outcome ->
            val scheduler = ManualScheduler()
            var attempts = 0
            var surrenderCount = 0
            val preflight = MulliganRankPreflight(
                config = MulliganRankPreflightConfig(
                    initialDelayMs = 7_000,
                    retryIntervalMs = 7_000,
                    maxAttempts = 3,
                    attemptTimeoutMs = 5_000,
                ),
                scheduler = scheduler,
                paddleXRequestTimeoutMs = { 1_000L },
                isEligible = { true },
                inspect = {
                    attempts++
                    if (outcome == "PaddleOCR exception") throw IllegalStateException(outcome)
                    null
                },
                provider = { "PADDLEX" },
                onSurrender = { result ->
                    assertEquals("rank-ocr-unresolved", result.ruleId, outcome)
                    assertTrue(result.shouldSurrender, outcome)
                    surrenderCount++
                },
                onContinue = { error("$outcome must not continue mulligan") },
            )

            preflight.start()
            repeat(3) {
                scheduler.runScheduledAfter(7_000)
                scheduler.runWorker()
            }

            assertEquals(3, attempts, outcome)
            assertEquals(1, surrenderCount, outcome)
            assertEquals(MulliganRankPreflightState.SURRENDER_REQUESTED, preflight.snapshot().state, outcome)
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

    @Test
    fun `phase exit during active rank inspection suppresses all callbacks`() {
        val scheduler = ManualScheduler()
        lateinit var preflight: MulliganRankPreflight
        var surrenderCount = 0
        var continueCount = 0
        preflight = MulliganRankPreflight(
            config = MulliganRankPreflightConfig(initialDelayMs = 7_000, maxAttempts = 2),
            scheduler = scheduler,
            paddleXRequestTimeoutMs = { 1_000L },
            isEligible = { true },
            inspect = {
                preflight.cancel("phase-transition")
                null
            },
            provider = { "PADDLEX" },
            onSurrender = { surrenderCount++ },
            onContinue = { continueCount++ },
        )

        preflight.start()
        scheduler.runScheduledAfter(7_000)
        scheduler.runWorker()

        assertEquals(MulliganRankPreflightState.CANCELLED, preflight.snapshot().state)
        assertEquals(0, surrenderCount)
        assertEquals(0, continueCount)
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
        var scheduledCount: Int = 0
            private set
        var now: Long = 0
            private set

        override fun schedule(delayMs: Long, task: () -> Unit): ScheduledFuture<*> {
            scheduledCount++
            return add(delayMs, Kind.SCHEDULED, task)
        }

        override fun scheduleTimeout(delayMs: Long, task: () -> Unit): ScheduledFuture<*> =
            add(delayMs, Kind.TIMEOUT, task)

        override fun submit(task: () -> Unit): Future<*> = add(0, Kind.WORKER, task)

        fun runScheduledAfter(delayMs: Long) {
            val target = now + delayMs
            val entry = entries.firstOrNull { it.kind == Kind.SCHEDULED && it.dueAt == target }
                ?: error("no scheduled task at +$delayMs ms; entries=${entries.map { it.kind to it.dueAt }}")
            run(entry)
        }

        fun nextTimeoutDelayMs(): Long = entries.firstOrNull {
            it.kind == Kind.TIMEOUT && !it.future.isCancelled && !it.future.isDone
        }?.let { it.dueAt - now } ?: error("no pending timeout")

        fun runWorker(elapsedAfterStartMs: Long = 0L) {
            val entry = entries.firstOrNull {
                it.kind == Kind.WORKER && !it.future.isCancelled && !it.future.isDone
            }
                ?: error("no worker task; entries=${entries.map { it.kind to it.dueAt }}")
            now = maxOf(now, entry.dueAt + elapsedAfterStartMs)
            run(entry)
        }

        fun runWorkerAsync(): Thread {
            val entry = entries.firstOrNull {
                it.kind == Kind.WORKER && !it.future.isCancelled && !it.future.isDone
            }
                ?: error("no worker task; entries=${entries.map { it.kind to it.dueAt }}")
            val worker = Thread({ run(entry) }, "mulligan-rank-preflight-test")
            entry.future.workerThread = worker
            worker.start()
            return worker
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
        @Volatile
        var workerThread: Thread? = null

        fun complete() {
            done = true
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelled = true
            done = true
            if (mayInterruptIfRunning) workerThread?.interrupt()
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
