package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class StartupModeEntryDispatchPolicyTest {
    @Test
    fun `missing exact HUB proof requests bounded read-only reprobe but never click`() {
        StartupModeEntryReprobePolicy.resetForTest()
        try {
            fun request(
                now: Long,
                working: Boolean = true,
                paused: Boolean = false,
                mode: String? = "HUB",
                session: Boolean = false,
                activeGame: Boolean = false,
                activeQueue: Boolean = false,
                terminal: Boolean = false,
                proof: Boolean = false,
                pid: Int = 33512,
                hwnd: String = "game-hwnd",
            ) = StartupModeEntryReprobePolicy.shouldRequestFreshProbe(
                pid = pid,
                hwnd = hwnd,
                currentMode = mode,
                currentSessionReady = session,
                nowMs = now,
                working = working,
                paused = paused,
                chooserProofFresh = proof,
                activeGame = activeGame,
                activeQueue = activeQueue,
                terminal = terminal,
            )

            assertTrue(request(100_000L), "the exact HUB runtime state requests an initial fresh capture")
            assertFalse(request(100_001L), "same HWND is throttled")
            assertFalse(request(99_000L), "clock rollback cannot bypass the throttle")
            assertFalse(request(100_000L + StartupModeEntryReprobePolicy.MIN_INTERVAL_MS, paused = true))
            assertFalse(request(100_000L + StartupModeEntryReprobePolicy.MIN_INTERVAL_MS, working = false))
            assertFalse(request(100_000L + StartupModeEntryReprobePolicy.MIN_INTERVAL_MS, mode = "TOURNAMENT"))
            assertFalse(request(100_000L + StartupModeEntryReprobePolicy.MIN_INTERVAL_MS, session = true))
            assertFalse(request(100_000L + StartupModeEntryReprobePolicy.MIN_INTERVAL_MS, activeGame = true))
            assertFalse(request(100_000L + StartupModeEntryReprobePolicy.MIN_INTERVAL_MS, activeQueue = true))
            assertFalse(request(100_000L + StartupModeEntryReprobePolicy.MIN_INTERVAL_MS, terminal = true))
            assertFalse(request(100_000L + StartupModeEntryReprobePolicy.MIN_INTERVAL_MS, proof = true))

            assertTrue(request(100_000L + StartupModeEntryReprobePolicy.MIN_INTERVAL_MS))
            assertTrue(
                request(
                    now = 100_001L,
                    pid = 44120,
                    hwnd = "replacement-game-window",
                ),
                "a replacement PID/HWND is not delayed by an old window's throttle",
            )

            val actions = mutableListOf<String>()
            assertFalse(StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
                chooserProofFresh = false,
                paused = false,
                processId = 33512,
                hwnd = "game-hwnd",
            ) { actions += it; true })
            assertTrue(actions.isEmpty(), "reprobe request is not an observed transition or click permit")
        } finally {
            StartupModeEntryReprobePolicy.resetForTest()
        }
    }

    @Test
    fun `scheduled chooser callback dispatches only the named Traditional Battle action`() {
        val actions = mutableListOf<String>()

        val dispatched = StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
            chooserProofFresh = true,
            paused = false,
            processId = 33512,
            hwnd = "game-hwnd",
            dispatch = { action -> actions.add(action); true },
        )

        assertTrue(dispatched)
        assertEquals(listOf(StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION), actions)
    }

    @Test
    fun `scheduled chooser callback does not dispatch without fresh proof or while paused`() {
        val actions = mutableListOf<String>()

        assertFalse(StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
            chooserProofFresh = false,
            paused = false,
            processId = 33512,
            hwnd = "game-hwnd",
            dispatch = { action -> actions.add(action); true },
        ))
        assertFalse(StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
            chooserProofFresh = true,
            paused = true,
            processId = 33512,
            hwnd = "game-hwnd",
            dispatch = { action -> actions.add(action); true },
        ))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `mode click owns foreground transaction and concurrent recovery capture is deferred`() {
        val actions = mutableListOf<String>()
        var captureRan = false
        val dispatched = StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
            chooserProofFresh = true,
            paused = false,
            processId = 33512,
            hwnd = "game-hwnd",
        ) { action ->
            actions += action
            assertTrue(StartupModeEntryDispatchPolicy.isModeEntryInFlight(33512, "game-hwnd"))
            assertEquals(null, StartupModeEntryDispatchPolicy.withRecoveryCapture(33512, "game-hwnd") {
                captureRan = true
            }, "an idle probe must not steal focus while the authorized selector click is in flight")
            true
        }

        assertTrue(dispatched)
        assertEquals(listOf(StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION), actions)
        assertFalse(captureRan)
        assertFalse(StartupModeEntryDispatchPolicy.isModeEntryInFlight(33512, "game-hwnd"))
        assertEquals("captured", StartupModeEntryDispatchPolicy.withRecoveryCapture(33512, "game-hwnd") {
            "captured"
        })
    }

    @Test
    fun `bounded final proof capture waits for an in-flight mode transaction then captures`() {
        val entered = CountDownLatch(1)
        val modeThread = Thread {
            StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
                chooserProofFresh = true,
                paused = false,
                processId = 33512,
                hwnd = "game-hwnd",
            ) {
                entered.countDown()
                Thread.sleep(150L)
                true
            }
        }
        modeThread.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertEquals(
            "captured",
            StartupModeEntryDispatchPolicy.withRecoveryCapture(
                33512,
                "game-hwnd",
                waitMillis = 1_000L,
            ) { "captured" },
            "final Start proof may wait for the bounded mode transition, but must never capture concurrently",
        )
        modeThread.join(1_000L)
        assertFalse(modeThread.isAlive)
    }

    @Test
    fun `bounded final proof capture times out and interruption fail closed`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val modeThread = Thread {
            StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
                chooserProofFresh = true,
                paused = false,
                processId = 33512,
                hwnd = "game-hwnd",
            ) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                true
            }
        }
        modeThread.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        val started = System.nanoTime()
        assertEquals(
            null,
            StartupModeEntryDispatchPolicy.withRecoveryCapture(
                33512,
                "game-hwnd",
                waitMillis = 50L,
            ) { "must-not-capture" },
        )
        assertTrue(
            (System.nanoTime() - started) < TimeUnit.MILLISECONDS.toNanos(500L),
            "timeout must consume one bounded budget, not wait twice",
        )

        val interruptedResult = AtomicReference<String?>("unset")
        val captureThread = Thread {
            interruptedResult.set(
                StartupModeEntryDispatchPolicy.withRecoveryCapture(
                    33512,
                    "game-hwnd",
                    waitMillis = 1_000L,
                ) { "must-not-capture" },
            )
        }
        captureThread.start()
        Thread.sleep(50L)
        captureThread.interrupt()
        captureThread.join(1_000L)
        release.countDown()
        modeThread.join(1_000L)
        assertFalse(captureThread.isAlive)
        assertFalse(modeThread.isAlive)
        assertEquals(null, interruptedResult.get(), "interrupted proof refresh must fail closed")
    }

    @Test
    fun `rejected selector input is not reported as dispatched`() {
        val dispatched = StartupModeEntryDispatchPolicy.dispatchTraditionalBattleIfReady(
            chooserProofFresh = true,
            paused = false,
            processId = 33512,
            hwnd = "game-hwnd",
            dispatch = { false },
        )
        assertFalse(dispatched)
    }
}
