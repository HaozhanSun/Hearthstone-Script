package club.xiaojiawei.hsscript.status

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import javax.imageio.ImageIO
import java.util.concurrent.atomic.AtomicInteger

/** Replay the v4.16.586 log sequence without a game process or UI input. */
class OfflineStartupReadinessRecoveryE2ETest {
    @Serializable
    private data class Fixture(
        val scenario: String,
        val deploymentId: String,
        val scriptPid: Long,
        val gamePid: Long,
        val screenEvidence: String,
        val events: List<String>,
        val expected: String,
    )

    @Test
    fun `startup empty-log home flow only hands off on no-match and never queues from menu proof`() {
        // This checked-in screenshot is only an available harness fixture; the affected 21:53 run had no persisted pixels.
        val screenshot = javaClass.classLoader.getResourceAsStream(
            "offline-ocr/screen-recovery/deck-selection-screen.png",
        )?.use(ImageIO::read) ?: error("Missing checked-in Home screenshot fixture")
        assertTrue(screenshot.width > 400 && screenshot.height > 300)

        val emptyLog = PowerLogActiveMatchProbe.assess(emptySequence())
        val dispatches = AtomicInteger()
        fun decide(active: PowerLogActiveMatchProbe.State, lineage: Boolean = false) =
            VerifiedStartupMenuProgression.decide(
                VerifiedStartupMenuProgression.Evidence(
                    screen = VerifiedStartupMenuProgression.Screen.HOME,
                    confidence = 95,
                    pid = 97212L,
                    currentPid = 97212L,
                    windowPid = 97212L,
                    foregroundAndPixelsVerified = true,
                    configuredTournament = true,
                    working = true,
                    manuallyPaused = false,
                    currentSessionPowerLogReady = false,
                    activeMatch = active,
                    priorAuthoritativeLineage = lineage,
                ),
            )

        assertEquals(PowerLogActiveMatchProbe.State.NO_MATCH, emptyLog.state)
        assertEquals(VerifiedStartupMenuProgression.Action.ENTER_HUB, decide(emptyLog.state))
        // The action adapter here counts only queue/gameplay dispatch; HOME->HUB is not one.
        assertEquals(0, dispatches.get())
        assertEquals(VerifiedStartupMenuProgression.Action.WAIT, decide(PowerLogActiveMatchProbe.State.ACTIVE_MATCH))
        assertEquals(VerifiedStartupMenuProgression.Action.WAIT, decide(PowerLogActiveMatchProbe.State.TERMINAL))
        assertEquals(VerifiedStartupMenuProgression.Action.WAIT, decide(PowerLogActiveMatchProbe.State.UNREADABLE))
        assertEquals(VerifiedStartupMenuProgression.Action.WAIT, decide(PowerLogActiveMatchProbe.State.NO_MATCH, lineage = true))
        assertEquals(
            PowerLogActiveMatchProbe.State.ACTIVE_MATCH,
            PowerLogActiveMatchProbe.assess(sequenceOf("CREATE_GAME", "tag=MULLIGAN_STATE value=INPUT")).state,
        )
        assertEquals(
            PowerLogActiveMatchProbe.State.TERMINAL,
            PowerLogActiveMatchProbe.assess(sequenceOf("CREATE_GAME", "tag=PLAYSTATE value=LOST")).state,
        )
        assertEquals(
            PowerLogActiveMatchProbe.State.UNREADABLE,
            PowerLogActiveMatchProbe.assess(sequenceOf("CREATE_GAME", "tag=STEP value=BEGIN_MULLIGAN")).state,
        )

        // No matchmaking capability exists in this policy. Queue input remains behind
        // the separate current-session + fresh-rank authorization boundary.
        assertEquals(0, dispatches.get())
    }

    @Test
    fun `empty Power log and stale startup state yield bounded menu observation without input`() {
        val fixture = readFixture()
        assertEquals("v4.16.586 startup readiness deadlock replay", fixture.scenario)
        assertEquals(88848L, fixture.scriptPid)
        assertEquals(97212L, fixture.gamePid)
        assertTrue(fixture.events.containsAll(listOf(
            "verified-game-window",
            "current-power-log-empty",
            "startup-probe-deferred",
            "NONE|NONE|FILL_DECK|NONE|0",
            "capture-rejected-current-game-session-not-ready",
            "unresolved-recovery-attempt-limit",
            "automatic-pause",
        )))
        assertTrue(fixture.screenEvidence.contains("No matching screenshot persisted"))

        val sessionReady = CurrentGameScreenReadinessPolicy.isReady(
            gameWindowVerified = true,
            attachedPowerLogPath = "D:/Hearthstone/Logs/current/Power.log",
            currentSessionPowerLogPath = "D:/Hearthstone/Logs/current/Power.log",
            powerLogLength = 0L,
        )
        assertFalse(sessionReady, "the empty current-session log must not authorize game input")

        val pid = fixture.gamePid
        assertTrue(
            StartupMenuObservationPolicy.isAuthorized(
                gameWindowVerified = true,
                currentPid = pid,
                windowPid = pid,
                mode = "NONE",
                working = true,
                paused = false,
                activeMatch = false,
                terminal = false,
            ),
        )
        val startupDecision = StartupMenuObservationPolicy.decide(
            currentSessionReady = false,
            startupObservationAuthorized = true,
            observedMenu = false,
            activeMatch = false,
            terminal = false,
            completedObservations = 0,
            maxObservations = 2,
        )
        assertEquals(StartupMenuObservationPolicy.Decision.OBSERVE_STARTUP_MENU, startupDecision)

        val actionDispatches = AtomicInteger()
        if (sessionReady) actionDispatches.incrementAndGet()
        val observationTimes = listOf(0L, 10_000L, 30_000L, 119_999L, 120_000L)
        var nextCaptureAt = 0L
        var captureCount = 0
        var observedMenu = false
        for (now in observationTimes) {
            if (!StartupMenuObservationPolicy.isCaptureDue(now, nextCaptureAt)) continue
            captureCount++
            nextCaptureAt = StartupMenuObservationPolicy.nextCaptureAt(now)
        // A fresh, high-confidence HOME_TASK_OVERLAY observation is purely
        // observational here; no persisted image exists for the historical run.
            observedMenu = true
            break
        }

        assertTrue(observedMenu, "the simulated valid menu observation ends startup probing")
        assertEquals(1, captureCount, "cooldown prevents repeated screen-capture retry loops")
        assertEquals(0, actionDispatches.get(), "menu observation must not click queue/gameplay/rank controls")
        assertFalse(sessionReady, "visual menu observation does not upgrade Power.log readiness")

        // A later in-game action remains blocked until the current session log
        // becomes non-empty and remains bound to this session.
        val laterSessionReady = CurrentGameScreenReadinessPolicy.isReady(
            gameWindowVerified = true,
            attachedPowerLogPath = "D:/Hearthstone/Logs/current/Power.log",
            currentSessionPowerLogPath = "D:/Hearthstone/Logs/current/Power.log",
            powerLogLength = 4096L,
        )
        assertTrue(laterSessionReady)
        if (laterSessionReady) actionDispatches.incrementAndGet()
        assertEquals(1, actionDispatches.get(), "only the later current-session-ready state reaches the action adapter")
        assertEquals(
            StartupMenuObservationPolicy.Decision.BOUNDED_SAFE_PAUSE,
            StartupMenuObservationPolicy.decide(
                currentSessionReady = false,
                startupObservationAuthorized = true,
                observedMenu = false,
                activeMatch = false,
                terminal = false,
                completedObservations = 2,
                maxObservations = 2,
            ),
            "unknown startup screens exhaust into bounded no-input handling, not relaunch loops",
        )
    }

    private fun readFixture(): Fixture = Json.decodeFromString(
        javaClass.classLoader.getResourceAsStream(
            Path.of("offline-ocr", "screen-recovery", "startup-empty-powerlog-live-window.json")
                .toString().replace('\\', '/'),
        )?.bufferedReader()?.use { it.readText() } ?: error("Missing startup readiness replay fixture"),
    )
}
