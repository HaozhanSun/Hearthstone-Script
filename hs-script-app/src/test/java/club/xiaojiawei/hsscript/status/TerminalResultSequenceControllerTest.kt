package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.utils.TerminalPageCleanupCoordinator
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalResultSequenceControllerTest {
    @Test
    fun `v705 gold victory measurements authorize only the bounded unknown Continue path`() {
        val measurements = requireNotNull(
            javaClass.getResourceAsStream("/offline-ocr/result-flow/rc-v705-terminal-recovery/incident-evidence.txt"),
        ).bufferedReader().use { it.readLines().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap() }
        val fast = requireNotNull(ResultPageEvidencePolicy.fastVisualObservation(
            measurements.getValue("victory.continueGrayLightRatio").toDouble(),
            measurements.getValue("victory.bannerLowSaturationRatio").toDouble(),
            measurements.getValue("victory.centerDarkRatio").toDouble(),
            measurements.getValue("victory.bannerWarmRatio").toDouble(),
        ))
        assertEquals(null, fast.resultVisible)
        assertTrue(fast.continueControlVisible)
        assertFalse(fast.rankProgressVisible)

        val observerExecutor = Executors.newSingleThreadScheduledExecutor()
        val inputExecutor = Executors.newSingleThreadScheduledExecutor()
        val dispatched = CountDownLatch(1)
        val controller = TerminalResultSequenceController(
            observerExecutor = observerExecutor,
            inputExecutor = inputExecutor,
            initialInputCount = 0,
            initialRetryIntervalMillis = 10L,
            retryIntervalMillis = { 60_000L },
            observerIntervalMillis = 600L,
            monitorIntervalMillis = 10L,
            maxDurationMillis = 30_000L,
            observe = {
                ResultScreenObservation(
                    resultVisible = fast.resultVisible,
                    captureAuthorized = true,
                    continueControlVisible = fast.continueControlVisible,
                    capturedAtNanos = System.nanoTime(),
                )
            },
            terminalProofValid = { true },
            paused = { false },
            sameGame = { true },
            dispatchContinue = { _, _, _, markDispatch ->
                markDispatch()
                dispatched.countDown()
                TerminalResultSequenceController.DispatchOutcome(attempted = true, dispatchAccepted = true)
            },
            confirmDestination = { false },
            onStopped = { _, _ -> },
        )
        try {
            controller.start()
            assertTrue(dispatched.await(3, TimeUnit.SECONDS), "UNKNOWN with the exact visible band permits a bounded retry")
        } finally {
            controller.cancel("test-cleanup")
            observerExecutor.shutdownNow()
            inputExecutor.shutdownNow()
        }
    }

    @Test
    fun `authoritative terminal plus fresh visible continue band permits unknown victory retry`() {
        val incidentPower = requireNotNull(
            javaClass.getResourceAsStream("/offline-ocr/result-flow/rc-v4.16.703-victory-unknown/Power-final-gameover-excerpt.txt"),
        ).bufferedReader().use { it.readText() }
        assertTrue(incidentPower.contains("CREATE_GAME"))
        assertTrue(incidentPower.contains("laz#12793 tag=PLAYSTATE value=WON"))
        assertTrue(incidentPower.contains("Abel#13628 tag=PLAYSTATE value=LOST"))
        assertTrue(incidentPower.contains("tag=STEP value=FINAL_GAMEOVER"))
        assertTrue(incidentPower.contains("tag=STATE value=COMPLETE"))

        val observerExecutor = Executors.newSingleThreadScheduledExecutor()
        val inputExecutor = Executors.newSingleThreadScheduledExecutor()
        val dispatched = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val dispatchCount = AtomicInteger()
        val continueBandVisible = AtomicBoolean(false)
        val controller = TerminalResultSequenceController(
            observerExecutor = observerExecutor,
            inputExecutor = inputExecutor,
            initialInputCount = 0,
            initialRetryIntervalMillis = 10L,
            retryIntervalMillis = { 60_000L },
            observerIntervalMillis = 600L,
            monitorIntervalMillis = 10L,
            maxDurationMillis = 30_000L,
            observe = {
                ResultScreenObservation(
                    resultVisible = null,
                    captureAuthorized = true,
                    continueControlVisible = continueBandVisible.get(),
                )
            },
            terminalProofValid = { true },
            paused = { false },
            sameGame = { true },
            dispatchContinue = { _, _, _, markDispatch ->
                dispatchCount.incrementAndGet()
                markDispatch()
                dispatched.countDown()
                TerminalResultSequenceController.DispatchOutcome(attempted = true, dispatchAccepted = true)
            },
            confirmDestination = { false },
            onStopped = { _, _ -> stopped.countDown() },
        )
        try {
            controller.start()
            Thread.sleep(100L)
            assertEquals(0, dispatchCount.get(), "terminal proof and UNKNOWN pixels alone must not dispatch")
            continueBandVisible.set(true)
            assertTrue(dispatched.await(4, TimeUnit.SECONDS), "visible Continue band should recover an OCR-unknown victory page")
            assertEquals(1, dispatchCount.get())
        } finally {
            controller.cancel("test-cleanup")
            stopped.await(1, TimeUnit.SECONDS)
            observerExecutor.shutdownNow()
            inputExecutor.shutdownNow()
        }
    }

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
                ResultScreenObservation(resultVisible = true, captureAuthorized = true, continueControlVisible = true)
            },
            terminalProofValid = { true },
            paused = { false },
            sameGame = { true },
            dispatchContinue = { _, attempt, _, markDispatch ->
                assertEquals(attempt, coordinator.reserveInput(ticket))
                dispatchCount.incrementAndGet()
                markDispatch()
                if (attempt == 2) secondDispatch.countDown()
                TerminalResultSequenceController.DispatchOutcome(attempted = true, dispatchAccepted = true)
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
    fun `fast result observations reach confirmed deck destination within sixty seconds and gate queue until then`() {
        val observerExecutor = Executors.newSingleThreadScheduledExecutor()
        val inputExecutor = Executors.newSingleThreadScheduledExecutor()
        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = requireNotNull(coordinator.begin().ticket)
        val inputCount = AtomicInteger()
        val firstInput = CountDownLatch(1)
        val destinationConfirmed = CountDownLatch(1)
        val queueResumed = AtomicBoolean(false)
        val startedAt = System.nanoTime()
        val controller = TerminalResultSequenceController(
            observerExecutor = observerExecutor,
            inputExecutor = inputExecutor,
            initialInputCount = 0,
            initialRetryIntervalMillis = 10L,
            retryIntervalMillis = { 25L },
            observerIntervalMillis = 10L,
            monitorIntervalMillis = 10L,
            maxDurationMillis = 60_000L,
            observe = {
                coordinator.nextProbe(ticket)
                if (inputCount.get() >= 2) {
                    ResultScreenObservation(
                        resultVisible = false,
                        captureAuthorized = true,
                        destinationScreen = "DECK_SELECTION",
                        destinationConfidence = 95,
                    )
                } else {
                    ResultScreenObservation(
                        resultVisible = null,
                        captureAuthorized = true,
                        continueControlVisible = true,
                    )
                }
            },
            terminalProofValid = { true },
            paused = { false },
            sameGame = { true },
            dispatchContinue = { _, attempt, _, recordDispatch ->
                assertEquals(attempt, coordinator.reserveInput(ticket))
                inputCount.incrementAndGet()
                recordDispatch()
                firstInput.countDown()
                TerminalResultSequenceController.DispatchOutcome(attempted = true, dispatchAccepted = true)
            },
            confirmDestination = { observation -> coordinator.confirmDestination(ticket) &&
                observation.destinationScreen == "DECK_SELECTION"
            },
            onStopped = { _, confirmed ->
                if (confirmed) queueResumed.set(true)
                destinationConfirmed.countDown()
            },
        )
        try {
            controller.start()
            assertTrue(firstInput.await(3, TimeUnit.SECONDS), "fast visual Continue evidence must avoid a long OCR wait")
            assertFalse(queueResumed.get(), "queue flow stays fenced until fresh deck-selection confirmation")
            assertTrue(destinationConfirmed.await(8, TimeUnit.SECONDS), "the transition completes well inside the 60-second limit")
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) <= 60_000L)
            assertEquals(2, inputCount.get())
            assertTrue(queueResumed.get(), "confirmed deck selection releases the bounded queue continuation")
        } finally {
            controller.cancel("test-cleanup")
            observerExecutor.shutdownNow()
            inputExecutor.shutdownNow()
        }
    }

    @Test
    fun `fixture-backed second input waits for a post-click page proof and stops at destination`() {
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
                                // Simulate a delayed post-click capture. The input lane
                                // must not reuse the pre-click defeat frame as authority.
                                releaseObserver.await(20, TimeUnit.SECONDS)
                                observationForFixture(rankKind!!)
                            }
                            else -> if (synchronized(dispatches) { dispatches.size } < 2) {
                                observationForFixture(rankKind!!)
                            } else {
                                ResultScreenObservation(
                                    resultVisible = false,
                                    captureAuthorized = true,
                                    destinationScreen = "DECK_SELECTION",
                                    destinationConfidence = 95,
                                )
                            }
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
                    assertEquals(if (attempt == 1) 5_000L else 3_000L, plannedGapMs)
                    assertEquals(attempt, coordinator.reserveInput(ticket))
                    synchronized(dispatches) {
                        dispatches += page to System.nanoTime()
                    }
                    markDispatch()
                    TerminalResultSequenceController.DispatchOutcome(
                        attempted = true,
                        dispatchAccepted = true,
                    ) // Dispatch is recorded separately from the frame-confirmed page transition.
                },
                stopped = { _, _ -> done.countDown() },
            )
            controllerRef.set(controller)
            val tasks = controller.start()

            assertTrue(observerEnteredSlowCall.await(3, TimeUnit.SECONDS))
            val firstDispatchDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7)
            while (synchronized(dispatches) { dispatches.size } < 1 && System.nanoTime() < firstDispatchDeadline) {
                Thread.sleep(5)
            }
            Thread.sleep(250L)
            assertEquals(
                1,
                synchronized(dispatches) { dispatches.size },
                "a pre-click frame cannot authorize another input while fresh OCR is still blocked",
            )
            assertEquals(1L, releaseObserver.count)
            releaseObserver.countDown()
            val before = System.nanoTime()
            while (synchronized(dispatches) { dispatches.size } < 2 &&
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - before) < 10L
            ) Thread.sleep(5)
            val capturedDispatches = synchronized(dispatches) { dispatches.toList() }
            assertEquals(2, capturedDispatches.size, "the fresh rank-progress frame permits the next bounded input")
            assertEquals(TerminalResultSequenceController.Page.DEFEAT, capturedDispatches[0].first)
            assertEquals(TerminalResultSequenceController.Page.RANK_PROGRESS, capturedDispatches[1].first)
            val actualGapMs = TimeUnit.NANOSECONDS.toMillis(capturedDispatches[1].second - capturedDispatches[0].second)
            assertTrue(actualGapMs >= 2_000L, "actual dispatch gap=$actualGapMs ms; retries remain paced")
            println("TERMINAL_RESULT_SEQUENCE_DISPATCH_GAP_MS=$actualGapMs postClickFrameRequired=true intervalMs=2000..3000")
            assertEquals(1, maxObserversActive.get(), "observation remains serial while the input clock runs independently")

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
                        ResultScreenObservation(true, captureAuthorized = true, continueControlVisible = true)
                    },
                    terminalProofValid = { proofValid.get() },
                    paused = { paused.get() },
                    sameGame = { currentWar.get() == 4 },
                    dispatchContinue = { _, attempt, _, markDispatch ->
                        assertEquals(attempt, coordinator.reserveInput(ticket))
                        dispatchCount.incrementAndGet()
                        markDispatch()
                        firstDispatch.countDown()
                        TerminalResultSequenceController.DispatchOutcome(attempted = true, dispatchAccepted = true)
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
        dispatch: (TerminalResultSequenceController.Page, Int, Long, () -> Long?) -> TerminalResultSequenceController.DispatchOutcome,
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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02X".format(it) }

    private fun observationForFixture(kind: String?): ResultScreenObservation = when (kind) {
        "RESULT" -> ResultScreenObservation(resultVisible = true, captureAuthorized = true, continueControlVisible = true)
        "RANK_PROGRESS_CONTINUATION" -> ResultScreenObservation(
            resultVisible = true,
            captureAuthorized = true,
            rankProgressVisible = true,
            continueControlVisible = true,
        )
        else -> ResultScreenObservation(resultVisible = null, captureAuthorized = true)
    }

    @Test
    fun `live rank-ten screenshot permits proof-backed rank-progress retries and destination stops cleanup`() {
        val screenshot = requireNotNull(
            javaClass.getResourceAsStream("/fixtures/screen-recovery/rank10-result-v698-20261026-093304.png"),
        ).use { it.readBytes() }
        assertEquals(
            "41C4694A4DBDE847368F96CE62D771ADCBC857A6D85C7EBFE8E73C3FEAD7CB47",
            sha256(screenshot),
        )
        val frame = requireNotNull(ImageIO.read(ByteArrayInputStream(screenshot)))
        val rankKind = ScreenStateRecovery.classifyImageForResultFixture(frame)
        assertEquals("RANK_PROGRESS_CONTINUATION", rankKind)
        assertEquals("RANK_PROGRESS_CONTINUATION", UpstreamScreenStateRecovery.classifyImageForResultFixture(frame))

        val observerExecutor = Executors.newSingleThreadScheduledExecutor()
        val inputExecutor = Executors.newSingleThreadScheduledExecutor()
        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = requireNotNull(coordinator.begin().ticket)
        assertEquals(1, coordinator.reserveInput(ticket))
        assertEquals(2, coordinator.reserveInput(ticket))
        val frameIndex = AtomicInteger()
        val dispatched = mutableListOf<Pair<Int, Boolean>>()
        val done = CountDownLatch(1)
        val controllerRef = AtomicReference<TerminalResultSequenceController?>()
        try {
            val controller = TerminalResultSequenceController(
                observerExecutor = observerExecutor,
                inputExecutor = inputExecutor,
                initialInputCount = 2,
                initialRetryIntervalMillis = 25L,
                retryIntervalMillis = { 50L },
                observerIntervalMillis = 600L,
                monitorIntervalMillis = 10L,
                maxDurationMillis = 15_000L,
                observe = {
                    val index = frameIndex.incrementAndGet()
                    val inputsSoFar = synchronized(dispatched) { dispatched.size }
                    when {
                        inputsSoFar >= 2 -> ResultScreenObservation(
                            resultVisible = false,
                            captureAuthorized = true,
                            destinationScreen = "DECK_SELECTION",
                            destinationConfidence = 95,
                        )
                        index % 2 == 1 -> ResultScreenObservation(resultVisible = null, captureAuthorized = true)
                        else -> observationForFixture(rankKind)
                    }
                },
                terminalProofValid = { true },
                paused = { false },
                sameGame = { true },
                dispatchContinue = { page, attempt, _, markDispatch ->
                    assertEquals(TerminalResultSequenceController.Page.RANK_PROGRESS, page)
                    assertEquals(attempt, coordinator.reserveInput(ticket))
                    markDispatch()
                    synchronized(dispatched) {
                        // The first OS dispatch is rejected; this must not be
                        // mistaken for UI acceptance, nor prevent a later
                        // retry after another fresh rank-page frame.
                        dispatched += attempt to (attempt != 3)
                    }
                    TerminalResultSequenceController.DispatchOutcome(
                        attempted = true,
                        dispatchAccepted = attempt != 3,
                    )
                },
                confirmDestination = { observation ->
                    assertEquals("DECK_SELECTION", observation.destinationScreen)
                    coordinator.confirmDestination(ticket)
                },
                onStopped = { reason, confirmed ->
                    assertEquals("destination-confirmed", reason)
                    assertEquals(true, confirmed)
                    done.countDown()
                },
            )
            controllerRef.set(controller)
            val tasks = controller.start()
            assertTrue(done.await(12, TimeUnit.SECONDS), "fresh destination frame must confirm and stop cleanup")
            val attempts = synchronized(dispatched) { dispatched.toList() }
            assertEquals(listOf(3 to false, 4 to true), attempts)
            assertEquals(true, controller.snapshot().lastDispatchAccepted)
            assertEquals(TerminalPageCleanupCoordinator.State.COMPLETED, coordinator.snapshot().state)
            Thread.sleep(100)
            assertEquals(2, synchronized(dispatched) { dispatched.size }, "no input follows the confirmed destination")
            tasks.forEach { it.cancel(true) }
        } finally {
            controllerRef.get()?.cancel("test-cleanup")
            observerExecutor.shutdownNow()
            inputExecutor.shutdownNow()
        }
    }
}
