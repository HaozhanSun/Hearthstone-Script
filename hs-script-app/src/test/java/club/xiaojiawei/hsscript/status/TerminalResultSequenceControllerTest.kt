package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.utils.TerminalPageCleanupCoordinator
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalResultSequenceControllerTest {
    @Test
    fun `fast unknown probes cannot consume coordinator budget before second continue`() {
        val observerExecutor = Executors.newSingleThreadScheduledExecutor()
        val inputExecutor = Executors.newSingleThreadScheduledExecutor()
        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = requireNotNull(coordinator.begin().ticket)
        val dispatchCount = AtomicInteger()
        val secondDispatch = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val controller = TerminalResultSequenceController(
            observerExecutor = observerExecutor,
            inputExecutor = inputExecutor,
            initialInputCount = 0,
            initialRetryIntervalMillis = 5_000L,
            retryIntervalMillis = { 5_000L },
            // Deliberately request a fast cadence; production clamps it so the
            // shared 20-probe coordinator budget cannot expire before attempt 2.
            observerIntervalMillis = 1L,
            monitorIntervalMillis = 10L,
            maxDurationMillis = 30_000L,
            observe = {
                coordinator.nextProbe(ticket)
                ResultScreenObservation(resultVisible = null, captureAuthorized = false)
            },
            terminalProofValid = { true },
            paused = { false },
            sameGame = { true },
            dispatchContinue = { _, attempt, _, markDispatch ->
                assertEquals(attempt, coordinator.reserveInput(ticket))
                dispatchCount.incrementAndGet()
                markDispatch()
                if (attempt == 2) secondDispatch.countDown()
                true
            },
            confirmDestination = { false },
            onStopped = { _, _ -> stopped.countDown() },
        )
        try {
            controller.start()
            assertTrue(secondDispatch.await(12, TimeUnit.SECONDS), "second input must beat the coordinator probe cap")
            assertTrue(coordinator.snapshot().probes < 20, "probe cadence must leave coordinator budget for the input sequence")
            assertEquals(2, dispatchCount.get())
        } finally {
            controller.cancel("test-cleanup")
            stopped.await(1, TimeUnit.SECONDS)
            observerExecutor.shutdownNow()
            inputExecutor.shutdownNow()
        }
    }

    @Test
    fun `fixture-backed dispatch clock continues while observer is delayed and stops at destination`() {
        val defeatFrame = loadImage("/offline-ocr/result-flow/incident-defeat-20261021-124129.png")
        val rankFrame = loadImage("/offline-ocr/result-flow/incident-rank-progress-1-20261021-124134.png")
        val defeatKind = ScreenStateRecovery.classifyImageForResultFixture(defeatFrame)
        val rankKind = ScreenStateRecovery.classifyImageForResultFixture(rankFrame)
        assertEquals("RESULT", defeatKind)
        assertEquals("RANK_PROGRESS_CONTINUATION", rankKind)

        val observerExecutor = Executors.newSingleThreadScheduledExecutor()
        val inputExecutor = Executors.newSingleThreadScheduledExecutor()
        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = requireNotNull(coordinator.begin().ticket)
        val proofValid = AtomicBoolean(true)
        val currentWar = AtomicInteger(9)
        val done = CountDownLatch(1)
        val observerEnteredSlowCall = CountDownLatch(1)
        val releaseObserver = CountDownLatch(1)
        val dispatches = mutableListOf<Pair<TerminalResultSequenceController.Page, Long>>()
        val observersActive = AtomicInteger()
        val maxObserversActive = AtomicInteger()
        val observedFrames = AtomicInteger()
        val controllerRef = AtomicReference<TerminalResultSequenceController?>()
        val terminalConfirms = AtomicInteger()
        lateinit var controller: TerminalResultSequenceController

        try {
            controller = newController(
                observerExecutor = observerExecutor,
                inputExecutor = inputExecutor,
                coordinator = coordinator,
                ticket = ticket,
                proofValid = proofValid,
                currentWar = currentWar,
                startingWar = 9,
                retryIntervalMs = 5_000L,
                observe = {
                    val active = observersActive.incrementAndGet()
                    maxObserversActive.updateAndGet { maxOf(it, active) }
                    try {
                        when (observedFrames.incrementAndGet()) {
                            1 -> observationForFixture(defeatKind!!)
                            2 -> {
                                observerEnteredSlowCall.countDown()
                                // Simulate a 20-second OCR/capture stall. The independent
                                // input lane must send attempt two before this is released.
                                releaseObserver.await(20, TimeUnit.SECONDS)
                                observationForFixture(rankKind!!)
                            }
                            else -> ResultScreenObservation(
                                resultVisible = false,
                                captureAuthorized = true,
                                destinationScreen = "DECK_SELECTION",
                                destinationConfidence = 95,
                            )
                        }
                    } finally {
                        observersActive.decrementAndGet()
                    }
                },
                confirmDestination = {
                    terminalConfirms.incrementAndGet()
                    coordinator.confirmDestination(ticket)
                },
                dispatch = { page, attempt, plannedGapMs, markDispatch ->
                    assertEquals(5_000L, plannedGapMs)
                    assertEquals(attempt, coordinator.reserveInput(ticket))
                    synchronized(dispatches) {
                        dispatches += page to System.nanoTime()
                    }
                    markDispatch()
                    true // OS dispatch attempt made; not interpreted as UI acceptance.
                },
                stopped = { _, _ -> done.countDown() },
            )
            controllerRef.set(controller)
            val tasks = controller.start()

            assertTrue(observerEnteredSlowCall.await(1, TimeUnit.SECONDS))
            val before = System.nanoTime()
            while (synchronized(dispatches) { dispatches.size } < 2 &&
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - before) < 15L
            ) Thread.sleep(5)
            val capturedDispatches = synchronized(dispatches) { dispatches.toList() }
            assertEquals(2, capturedDispatches.size, "two fixed Continue attempts proceed while OCR is still blocked")
            assertEquals(TerminalResultSequenceController.Page.DEFEAT, capturedDispatches[0].first)
            assertEquals(TerminalResultSequenceController.Page.RANK_PROGRESS, capturedDispatches[1].first)
            assertEquals(1L, releaseObserver.count, "the observer must still be blocked when the second click is sent")
            val actualGapMs = TimeUnit.NANOSECONDS.toMillis(capturedDispatches[1].second - capturedDispatches[0].second)
            assertTrue(actualGapMs in 4_900L..7_999L, "actual dispatch gap=$actualGapMs ms; expected 5–7s and <8s")
            println("TERMINAL_RESULT_SEQUENCE_DISPATCH_GAP_MS=$actualGapMs observerBlocked=true intervalMs=5000")
            assertEquals(1, maxObserversActive.get(), "observation remains serial while the input clock runs independently")

            releaseObserver.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS), "fresh deck selection must stop the sequence")
            assertEquals(1, terminalConfirms.get())
            assertEquals(TerminalPageCleanupCoordinator.State.COMPLETED, coordinator.snapshot().state)
            assertTrue(coordinator.snapshot().probes >= 3, "the replay advanced the real cleanup coordinator for all fixture observations")
            Thread.sleep(120)
            assertEquals(2, synchronized(dispatches) { dispatches.size }, "no click is sent after positive destination evidence")
            assertEquals(true, controllerRef.get()?.snapshot()?.stopped)
            assertEquals(9, currentWar.get(), "the Continue-only controller does not start a new game")
            tasks.forEach { it.cancel(true) }
        } finally {
            releaseObserver.countDown()
            controllerRef.get()?.cancel("test-cleanup")
            observerExecutor.shutdownNow()
            inputExecutor.shutdownNow()
        }
    }

    @Test
    fun `proof expiry pause and new game identity each cancel a pending second action`() {
        listOf("proof", "pause", "new-game").forEach { invalidation ->
            val observerExecutor = Executors.newSingleThreadScheduledExecutor()
            val inputExecutor = Executors.newSingleThreadScheduledExecutor()
            val coordinator = TerminalPageCleanupCoordinator()
            val ticket = requireNotNull(coordinator.begin().ticket)
            val proofValid = AtomicBoolean(true)
            val paused = AtomicBoolean(false)
            val currentWar = AtomicInteger(4)
            val firstDispatch = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            val dispatchCount = AtomicInteger()
            val controllerRef = AtomicReference<TerminalResultSequenceController?>()
            try {
                val controller = TerminalResultSequenceController(
                    observerExecutor = observerExecutor,
                    inputExecutor = inputExecutor,
                    initialInputCount = 0,
                    initialRetryIntervalMillis = 100L,
                    retryIntervalMillis = { 100L },
                    observerIntervalMillis = 10L,
                    monitorIntervalMillis = 5L,
                    maxDurationMillis = 30_000L,
                    observe = {
                        coordinator.nextProbe(ticket)
                        ResultScreenObservation(true, captureAuthorized = true)
                    },
                    terminalProofValid = { proofValid.get() },
                    paused = { paused.get() },
                    sameGame = { currentWar.get() == 4 },
                    dispatchContinue = { _, attempt, _, markDispatch ->
                        assertEquals(attempt, coordinator.reserveInput(ticket))
                        dispatchCount.incrementAndGet()
                        markDispatch()
                        firstDispatch.countDown()
                        true
                    },
                    confirmDestination = { false },
                    onStopped = { _, _ -> stopped.countDown() },
                )
                controllerRef.set(controller)
                val tasks = controller.start()
                assertTrue(firstDispatch.await(1, TimeUnit.SECONDS))
                when (invalidation) {
                    "proof" -> proofValid.set(false)
                    "pause" -> paused.set(true)
                    "new-game" -> currentWar.incrementAndGet()
                }
                assertTrue(stopped.await(1, TimeUnit.SECONDS), "$invalidation should cancel promptly")
                Thread.sleep(120)
                assertEquals(1, dispatchCount.get(), "$invalidation must suppress the pending retry")
                assertEquals(true, controller.snapshot().stopped)
                tasks.forEach { it.cancel(true) }
            } finally {
                controllerRef.get()?.cancel("test-cleanup")
                observerExecutor.shutdownNow()
                inputExecutor.shutdownNow()
            }
        }
    }

    private fun newController(
        observerExecutor: ScheduledExecutorService,
        inputExecutor: ScheduledExecutorService,
        coordinator: TerminalPageCleanupCoordinator,
        ticket: TerminalPageCleanupCoordinator.Ticket,
        proofValid: AtomicBoolean,
        currentWar: AtomicInteger,
        startingWar: Int,
        retryIntervalMs: Long,
        observe: () -> ResultScreenObservation,
        confirmDestination: (ResultScreenObservation) -> Boolean,
        dispatch: (TerminalResultSequenceController.Page, Int, Long, () -> Long?) -> Boolean,
        stopped: (String, Boolean) -> Unit,
    ) = TerminalResultSequenceController(
        observerExecutor = observerExecutor,
        inputExecutor = inputExecutor,
        initialInputCount = coordinator.snapshot().inputs,
        initialRetryIntervalMillis = retryIntervalMs,
        retryIntervalMillis = { retryIntervalMs },
        observerIntervalMillis = 10L,
        monitorIntervalMillis = 5L,
        maxDurationMillis = 30_000L,
        observe = {
            if (coordinator.nextProbe(ticket) == null) ResultScreenObservation(null, captureAuthorized = false)
            else observe()
        },
        terminalProofValid = { proofValid.get() },
        paused = { false },
        sameGame = { currentWar.get() == startingWar },
        dispatchContinue = dispatch,
        confirmDestination = confirmDestination,
        onStopped = stopped,
    )

    private fun loadImage(path: String) = requireNotNull(
        ImageIO.read(ByteArrayInputStream(requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() })),
    )

    private fun observationForFixture(kind: String?): ResultScreenObservation = when (kind) {
        "RESULT" -> ResultScreenObservation(resultVisible = true, captureAuthorized = true)
        "RANK_PROGRESS_CONTINUATION" -> ResultScreenObservation(
            resultVisible = true,
            captureAuthorized = true,
            rankProgressVisible = true,
        )
        else -> ResultScreenObservation(resultVisible = null, captureAuthorized = true)
    }
}
