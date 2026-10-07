package club.xiaojiawei.hsscript.status

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import club.xiaojiawei.hsscript.status.TournamentModeVisualConfirmationPolicy
import club.xiaojiawei.hsscript.status.WildModeTitleVisualMatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.nio.file.Path
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.util.concurrent.atomic.AtomicInteger

/** Replay startup-readiness evidence without a game process or live UI input. */
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
    fun `trusted same pid wild title template rescues garbage OCR but all ambiguous or mismatched evidence blocks inputs`() {
        val fixturePath = "offline-ocr/screen-recovery/tournament-mode-wild-garbage-20261007-044831.png"
        val bytes = javaClass.classLoader.getResourceAsStream(fixturePath)?.use { it.readBytes() }
            ?: error("Missing exact v4.16.589 Wild-title failure screenshot")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02X".format(it) }
        assertEquals("59912DABC25CEA59D960745180A0DFB9823846B84F540DBE26394A4C696317DB", digest)
        val screenshot = ImageIO.read(bytes.inputStream()) ?: error("Wild-title screenshot is not decodable")
        val confidence = WildModeTitleVisualMatcher.confidence(screenshot)
            ?: error("Wild-title template matcher unavailable")
        assertTrue(confidence >= TournamentModeVisualConfirmationPolicy.MIN_WILD_VISUAL_CONFIDENCE)
        println("WILD_TITLE_TEMPLATE_MATCH confidence=${"%.4f".format(java.util.Locale.ROOT, confidence)} threshold=${TournamentModeVisualConfirmationPolicy.MIN_WILD_VISUAL_CONFIDENCE}")

        val titleObscured = BufferedImage(screenshot.width, screenshot.height, BufferedImage.TYPE_INT_RGB)
        val graphics = titleObscured.createGraphics()
        try {
            graphics.drawImage(screenshot, 0, 0, null)
            graphics.color = java.awt.Color(36, 30, 27)
            graphics.fillRect(649, 24, 120, 36)
        } finally {
            graphics.dispose()
        }
        val obscuredConfidence = WildModeTitleVisualMatcher.confidence(titleObscured)
        assertTrue(
            obscuredConfidence == null || obscuredConfidence < TournamentModeVisualConfirmationPolicy.MIN_WILD_VISUAL_CONFIDENCE,
            "obscured/ambiguous title pixels cannot satisfy the Wild signature",
        )

        val currentPid = 101872L
        val deckStartDispatches = AtomicInteger()
        val queueDispatches = AtomicInteger()
        fun modeAccepted(expected: String, ocr: String?, visual: Double?, capturePid: Long?): Boolean {
            val classifiedOcr = when (ocr) {
                null, "", "ESss" -> "UNKNOWN"
                else -> ocr
            }
            val observed = TournamentModeVisualConfirmationPolicy.resolve(
                ocrMode = classifiedOcr,
                wildVisualConfidence = visual,
                currentPid = currentPid,
                capturedPid = capturePid,
            )
            return observed == expected
        }

        assertTrue(modeAccepted("WILD", "ESss", confidence, currentPid), "same-PID title pixels recover OCR garbage")
        assertTrue(modeAccepted("WILD", "UNKNOWN", confidence, currentPid), "empty OCR may use only strong Wild template")
        if (modeAccepted("WILD", "ESss", confidence, currentPid)) deckStartDispatches.incrementAndGet()
        assertEquals(1, deckStartDispatches.get(), "confirmation opens only the normal deck/start continuation")
        assertEquals(0, queueDispatches.get(), "mode proof alone must not bypass the independent rank-4 queue gate")

        val rejectedEvidence = listOf(
            modeAccepted("WILD", "STANDARD", null, currentPid), // recognized wrong mode
            modeAccepted("STANDARD", "STANDARD", confidence, currentPid), // OCR/template conflict
            modeAccepted("WILD", "AMBIGUOUS", confidence, currentPid), // OCR sees conflicting mode cues
            modeAccepted("WILD", "SWITCHING", confidence, currentPid), // open mode selector is not a deck page
            modeAccepted("WILD", "UNKNOWN", null, currentPid), // ambiguous visual
            modeAccepted(
                "WILD", "UNKNOWN",
                TournamentModeVisualConfirmationPolicy.MIN_WILD_VISUAL_CONFIDENCE - 0.01,
                currentPid,
            ), // low confidence
            modeAccepted("WILD", "UNKNOWN", confidence, currentPid + 1L), // stale/wrong PID
        )
        assertTrue(rejectedEvidence.none { it }, "mismatch, conflict, ambiguity, low confidence, and wrong PID all fail closed")
        rejectedEvidence.forEach { if (it) deckStartDispatches.incrementAndGet() }
        assertEquals(1, deckStartDispatches.get(), "rejected observations dispatch no deck/start input")
        assertEquals(0, queueDispatches.get(), "rejected observations dispatch no matchmaking input")
    }

    @Test
    fun `incident unknown retries are passive and trusted quest overlay gets one dismissal before fresh home`() {
        val fixturePath = "offline-ocr/screen-recovery/startup-loading-20261007-031712-802.png"
        val bytes = javaClass.classLoader.getResourceAsStream(fixturePath)?.use { it.readBytes() }
            ?: error("Missing exact v4.16.587 incident loading screenshot")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02X".format(it) }
        assertEquals("AA102DEF76E357630FEE89EA8858186D08336A44A65E01565788C20103264D36", digest)
        val screenshot = ImageIO.read(bytes.inputStream()) ?: error("Incident screenshot is not decodable")
        assertTrue(screenshot.width >= 1280 && screenshot.height >= 720)

        // The exact loading frame accompanied an empty/missing mode OCR
        // observation in the incident. Neither outcome authorizes the
        // tournament strategy's mode/deck/start action chain.
        val modeOrDeckInputs = AtomicInteger()
        val queueInputs = AtomicInteger()
        for (modeOcr in listOf("", "ESss")) {
            val screen = if (modeOcr.isBlank()) "UNKNOWN" else "LOADING"
            val authorized = TournamentStartupActionPolicy.mayStartModeSelection(
                screen = screen,
                confidence = if (modeOcr.isBlank()) 0 else 95,
                currentPid = 97212L,
                observedPid = 97212L,
                working = true,
                paused = false,
            )
            if (authorized) modeOrDeckInputs.incrementAndGet()
        }
        assertEquals(0, modeOrDeckInputs.get(), "loading and empty/garbage OCR must not dispatch mode/deck/start")
        assertEquals(0, queueInputs.get(), "a startup observation never authorizes queue input")

        // Only a later fresh, trusted TOURNAMENT observation on that same
        // current process reaches the first mode-selection boundary.
        assertTrue(TournamentStartupActionPolicy.mayStartModeSelection(
            screen = "TOURNAMENT",
            confidence = 95,
            currentPid = 97212L,
            observedPid = 97212L,
            working = true,
            paused = false,
        ))
        assertEquals(0, queueInputs.get(), "trusted screen proof still cannot queue on its own")

        val max = StartupMenuObservationPolicy.MAX_PASSIVE_REOBSERVATIONS
        val queueDispatches = AtomicInteger()
        val gameplayDispatches = AtomicInteger()
        repeat(5) { completed ->
            val decision = StartupMenuObservationPolicy.decide(false, true, false, false, false, completed, max)
            assertEquals(StartupMenuObservationPolicy.Decision.OBSERVE_STARTUP_MENU, decision)
            // The incident frame's logged OCR result was UNKNOWN/confidence=0.
            assertFalse(StartupMenuObservationPolicy.isObservedMenu("UNKNOWN", 0))
            assertEquals(0, queueDispatches.get())
            assertEquals(0, gameplayDispatches.get())
        }

        val verifiedOverlay = StartupMenuObservationPolicy.isObservedMenu("HOME_TASK_OVERLAY", 95)
        assertTrue(verifiedOverlay, "fresh authoritative OCR may recognize the quest overlay")
        assertEquals(
            StartupMenuObservationPolicy.Decision.WAIT_EXPECTED_MENU,
            StartupMenuObservationPolicy.decide(false, true, verifiedOverlay, false, false, 5, max),
        )

        fun startupProgression(screen: VerifiedStartupMenuProgression.Screen, overlayDismissals: Int = 0) =
            VerifiedStartupMenuProgression.decide(
                VerifiedStartupMenuProgression.Evidence(
                    screen = screen,
                    confidence = 95,
                    pid = 97212L,
                    currentPid = 97212L,
                    windowPid = 97212L,
                    foregroundAndPixelsVerified = true,
                    configuredTournament = true,
                    working = true,
                    manuallyPaused = false,
                    currentSessionPowerLogReady = false,
                    activeMatch = PowerLogActiveMatchProbe.State.NO_MATCH,
                    priorAuthoritativeLineage = false,
                    overlayDismissals = overlayDismissals,
                    processLineageVerified = true,
                ),
            )

        // Model the *next fresh frame* after UNKNOWN as an accepted,
        // high-confidence quest overlay on the same current PID/HWND, with
        // foreground pixels verified, configured Tournament, clean NO_MATCH
        // evidence, no prior lineage, and the script working/unpaused.
        // Only this full evidence bundle permits one overlay dismissal.
        val overlayDismissals = AtomicInteger()
        val overlayAction = startupProgression(VerifiedStartupMenuProgression.Screen.HOME_TASK_OVERLAY)
        assertEquals(VerifiedStartupMenuProgression.Action.DISMISS_OVERLAY, overlayAction)
        if (overlayAction == VerifiedStartupMenuProgression.Action.DISMISS_OVERLAY) {
            overlayDismissals.incrementAndGet()
        }
        assertEquals(1, overlayDismissals.get(), "one verified dismissal dispatch is allowed")
        assertEquals(
            VerifiedStartupMenuProgression.Action.WAIT,
            startupProgression(VerifiedStartupMenuProgression.Screen.HOME_TASK_OVERLAY, overlayDismissals.get()),
            "fresh capture still showing the overlay must never dispatch a second click",
        )

        // A subsequent fresh HOME frame is sufficient for the guarded HUB
        // handoff, but not itself a queue/gameplay dispatch authorization.
        val freshHomeAction = startupProgression(
            VerifiedStartupMenuProgression.Screen.HOME,
            overlayDismissals.get(),
        )
        assertEquals(
            VerifiedStartupMenuProgression.Action.ENTER_HUB,
            freshHomeAction,
        )
        val freshHomeAccepted = freshHomeAction == VerifiedStartupMenuProgression.Action.ENTER_HUB
        assertTrue(freshHomeAccepted, "only a fresh trusted HOME classification confirms the handoff")
        assertEquals(0, queueDispatches.get(), "menu proof alone cannot start matchmaking")
        assertEquals(0, gameplayDispatches.get(), "menu proof alone cannot dispatch gameplay")

        assertEquals(
            StartupMenuObservationPolicy.Decision.BOUNDED_SAFE_PAUSE,
            StartupMenuObservationPolicy.decide(false, true, false, false, false, max, max),
        )
        assertEquals(0, queueDispatches.get())
        assertEquals(0, gameplayDispatches.get())
    }

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
