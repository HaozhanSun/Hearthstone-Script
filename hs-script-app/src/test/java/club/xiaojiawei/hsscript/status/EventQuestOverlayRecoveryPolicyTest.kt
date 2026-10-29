package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.awt.Rectangle
import java.io.File
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventQuestOverlayRecoveryPolicyTest {
    @Test
    fun `exact Event Quest screenshot alone routes to three top left clicks then minute recheck`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/event-quest-overlay-deck-selection-20261029.png",
        )))
        assertTrue(StartupQuestOverlayVisualClassifier.isQuestOverlay(image),
            "screen structure alone is authoritative; no client-log event is required")
        val screenKind = UpstreamScreenStateRecovery.classifyImageForTest("", image)
        assertEquals("EVENT_QUEST_OVERLAY", screenKind)
        val safePoint = StartupQuestOverlayDismissPolicy.topLeftPixel(Rectangle(0, 0, image.width, image.height))
        assertEquals(Point(0, 0), safePoint)
        assertTrue(UpstreamScreenStateRecovery.dispatchEventQuestRecovery(screenKind) {
            val policy = EventQuestOverlayRecoveryPolicy()
            val points = mutableListOf<Point?>()
            repeat(3) { index ->
                val decision = policy.observe(evidence(nowMs = 60_000L + index * 10L))
                assertEquals(EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT, decision.action)
                points += safePoint
            }
            assertEquals(listOf(Point(0, 0), Point(0, 0), Point(0, 0)), points)
            assertEquals(EventQuestOverlayRecoveryPolicy.Action.WAIT_AND_REPROBE,
                policy.observe(evidence(nowMs = 120_019L)).action)
            assertEquals(EventQuestOverlayRecoveryPolicy.Action.RESTART_CLIENT,
                policy.observe(evidence(nowMs = 120_020L)).action)
            true
        })
    }

    @Test
    fun `live event quest overlay fixture is recognized by the upstream Beta classifier`() {
        val image = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/event-quest-overlay-deck-selection-20261029.png",
        )))
        assertTrue(StartupQuestOverlayVisualClassifier.isQuestOverlay(image))
        assertEquals("EVENT_QUEST_OVERLAY", UpstreamScreenStateRecovery.classifyImageForTest("", image))
        var recoveryStarted = false
        val dispatched = UpstreamScreenStateRecovery.dispatchEventQuestRecovery(
            UpstreamScreenStateRecovery.classifyImageForTest("", image),
        ) {
            recoveryStarted = true
            true
        }
        assertTrue(dispatched, "the stale-probe classification must enter the event-quest recovery callsite")
        assertTrue(recoveryStarted, "the recovery start callback must be invoked")
        assertFalse(UpstreamScreenStateRecovery.dispatchEventQuestRecovery("UNKNOWN") { error("must not dispatch") })

        val liveCapture = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/event-quest-overlay-live-005412.png",
        )))
        val liveEvidence = StartupQuestOverlayVisualClassifier.inspect(liveCapture)
        assertTrue(liveEvidence.isQuestOverlay, "live saved capture should match classifier; evidence=$liveEvidence")
        assertEquals("EVENT_QUEST_OVERLAY", UpstreamScreenStateRecovery.classifyImageForTest("", liveCapture))

        val attachedQuestScreenshot = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/event-quest-overlay-live-clipboard-20261029.png",
        )))
        assertTrue(StartupQuestOverlayVisualClassifier.isQuestOverlay(attachedQuestScreenshot))
        val attachedClassification = UpstreamScreenStateRecovery.classifyImageForTest("", attachedQuestScreenshot)
        assertEquals("EVENT_QUEST_OVERLAY", attachedClassification)
        var attachedRecoveryStarted = false
        assertTrue(UpstreamScreenStateRecovery.dispatchEventQuestRecovery(attachedClassification) {
            attachedRecoveryStarted = true
            true
        })
        assertTrue(attachedRecoveryStarted)

        val upstreamSource = File("src/main/java/club/xiaojiawei/hsscript/status/UpstreamScreenStateRecovery.kt").readText()
        val upstreamResultProbe = upstreamSource.substring(
            upstreamSource.indexOf("internal fun observeResultScreenForRecovery(): ResultScreenObservation"),
        )
        assertTrue(upstreamResultProbe.indexOf("if (visual.startupQuestOverlayVisual)") <
            upstreamResultProbe.indexOf("val ocrText = runOCR"),
            "the exact live overlay classifier must short-circuit the 15s result OCR route")
        val legacySource = File("src/main/java/club/xiaojiawei/hsscript/status/ScreenStateRecovery.kt").readText()
        val legacyResultProbe = legacySource.substring(
            legacySource.indexOf("internal fun observeResultScreenForRecovery(): ResultScreenObservation"),
        )
        assertTrue(legacyResultProbe.indexOf("if (capture.visual.startupQuestOverlayVisual)") <
            legacyResultProbe.indexOf("val ocrEvidence = runOCR"),
            "the fallback result observer must use the same visual-first short circuit")

        val laterReconnect = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/reconnect-failure-dialog-over-deck-selection.png",
        )))
        val laterReconnectClassification = UpstreamScreenStateRecovery.classifyImageForTest("", laterReconnect)
        assertNotEquals("EVENT_QUEST_OVERLAY", laterReconnectClassification,
            "the later reconnect frame must be freshly classified, never inherit the quest-overlay label")
        assertFalse(UpstreamScreenStateRecovery.dispatchEventQuestRecovery(laterReconnectClassification) {
            error("reconnect classification must not dispatch stale quest-overlay recovery")
        })
    }

    @Test
    fun `exactly three top-left click attempts precede the 60 second restart stage`() {
        val policy = EventQuestOverlayRecoveryPolicy()
        repeat(3) { index ->
            val decision = policy.observe(evidence(nowMs = 1_000L + index * 500L))
            assertEquals(EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT, decision.action)
            assertEquals(index + 1, decision.clickNumber)
        }
        assertEquals(
            EventQuestOverlayRecoveryPolicy.Action.WAIT_AND_REPROBE,
            policy.observe(evidence(nowMs = 61_999L)).action,
        )
        val restart = policy.observe(evidence(nowMs = 62_000L))
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.RESTART_CLIENT, restart.action)
        assertEquals("exact-overlay-persisted-after-second-stage", restart.reason)
        policy.confirmRestartIssued()
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.STOP, policy.observe(evidence(nowMs = 63_000L)).action)
    }

    @Test
    fun `exact quest overlay waits until one minute stuck before first click`() {
        val policy = EventQuestOverlayRecoveryPolicy()
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.WAIT_AND_REPROBE,
            policy.observe(evidence(nowMs = 100_000L, stableForMs = 59_999L)).action)
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.CLICK_TOP_LEFT,
            policy.observe(evidence(nowMs = 100_001L, stableForMs = 60_000L)).action)
    }

    @Test
    fun `fresh unblocked deck screen releases the fence after any click`() {
        val policy = EventQuestOverlayRecoveryPolicy()
        policy.observe(evidence(nowMs = 1_000L))
        val decision = policy.observe(evidence(nowMs = 1_500L, exact = false, safeTransition = true))
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.UNBLOCK_SAFE_TRANSITION, decision.action)
        assertEquals(0, policy.clicksAttempted())
    }

    @Test
    fun `unknown and stale captures never authorize a click or restart`() {
        val policy = EventQuestOverlayRecoveryPolicy()
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.STOP,
            policy.observe(evidence(nowMs = 1_000L, exact = false)).action)
        assertEquals(EventQuestOverlayRecoveryPolicy.Action.STOP,
            policy.observe(evidence(nowMs = 2_000L, trusted = false)).action)
        assertEquals(0, policy.clicksAttempted())
    }

    @Test
    fun `pause active game queue and result evidence fence the top-left action`() {
        val cases = listOf(
            evidence(nowMs = 1_000L, paused = true),
            evidence(nowMs = 1_000L, activeGame = true),
            evidence(nowMs = 1_000L, activeQueue = true),
            evidence(nowMs = 1_000L, terminal = true),
        )
        cases.forEach { evidence ->
            val decision = EventQuestOverlayRecoveryPolicy().observe(evidence)
            assertTrue(decision.action in setOf(
                EventQuestOverlayRecoveryPolicy.Action.DEFER,
                EventQuestOverlayRecoveryPolicy.Action.STOP,
            ))
            assertEquals(0, decision.clickNumber)
        }
    }

    @Test
    fun `recovery context permits fill deck with historical intent but fences current queue evidence`() {
        fun allows(
            mode: String = "TOURNAMENT",
            phase: String = "FILL_DECK",
            session: Boolean = true,
            game: Boolean = false,
            queueScreen: Boolean = false,
            recentIntent: Boolean = false,
            shortRace: Boolean = false,
            queueVisual: Boolean = false,
            terminal: Boolean = false,
            paused: Boolean = false,
        ) = EventQuestOverlayRecoveryContextPolicy.allowsDismiss(
            mode = mode,
            phase = phase,
            currentSessionReady = session,
            nextModeGameplay = false,
            activeGame = game,
            activeQueueScreen = queueScreen,
            recentInputIntent = recentIntent,
            shortRaceWindow = shortRace,
            matchmakingSearchVisual = queueVisual,
            terminalOrResult = terminal,
            working = true,
            paused = paused,
        )

        assertTrue(allows())
        assertTrue(allows(recentIntent = true), "15-minute historical input intent alone must not veto current FILL_DECK")
        assertFalse(allows(recentIntent = true, shortRace = true), "the short input race window remains fenced")
        assertTrue(allows(mode = "HUB", phase = "NONE", session = false))
        assertFalse(allows(phase = "MULLIGAN"))
        assertTrue(allows(session = false), "exact Tournament/FILL_DECK underlay does not require a current Power.log")
        assertFalse(allows(mode = "GAMEPLAY"))
        assertFalse(allows(game = true))
        assertFalse(allows(queueScreen = true), "a currently observed matchmaking screen remains fenced")
        assertFalse(allows(queueVisual = true), "fresh matchmaking-search pixels remain fenced")
        assertFalse(allows(terminal = true))
        assertFalse(allows(paused = true))
    }

    @Test
    fun `15 minute matchmaking intent alone does not veto fill deck but active screen does`() {
        val now = System.currentTimeMillis()
        LifecycleTrace.resetMatchmakingEvidenceForTest()
        try {
            LifecycleTrace.markMatchmakingInputDispatched(dispatchAccepted = true, now = now - 90_000L)
            assertTrue(LifecycleTrace.hasRecentMatchmakingInputIntent(now))
            assertFalse(LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard(now))
            assertTrue(EventQuestOverlayRecoveryContextPolicy.allowsDismiss(
                mode = "TOURNAMENT", phase = "FILL_DECK", currentSessionReady = true,
                nextModeGameplay = false, activeGame = false,
                activeQueueScreen = LifecycleTrace.hasRecentActiveMatchmakingScreen(now),
                recentInputIntent = LifecycleTrace.hasRecentMatchmakingInputIntent(now),
                shortRaceWindow = LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard(now),
                matchmakingSearchVisual = false, terminalOrResult = false, working = true, paused = false,
            ))

            LifecycleTrace.markActiveMatchmakingScreenObserved(now)
            assertFalse(EventQuestOverlayRecoveryContextPolicy.allowsDismiss(
                mode = "TOURNAMENT", phase = "FILL_DECK", currentSessionReady = true,
                nextModeGameplay = false, activeGame = false,
                activeQueueScreen = LifecycleTrace.hasRecentActiveMatchmakingScreen(now),
                recentInputIntent = LifecycleTrace.hasRecentMatchmakingInputIntent(now),
                shortRaceWindow = LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard(now),
                matchmakingSearchVisual = false, terminalOrResult = false, working = true, paused = false,
            ))
        } finally {
            LifecycleTrace.resetMatchmakingEvidenceForTest()
        }
    }

    @Test
    fun `top-left target is the exact captured client origin`() {
        assertEquals(Point(120, 220), StartupQuestOverlayDismissPolicy.topLeftPixel(Rectangle(120, 220, 1920, 1080)))
        assertEquals(null, StartupQuestOverlayDismissPolicy.topLeftPixel(Rectangle(0, 0, 0, 1080)))
    }

    private fun evidence(
        nowMs: Long,
        stableForMs: Long = EventQuestOverlayRecoveryPolicy.FIRST_STAGE_DELAY_MS,
        exact: Boolean = true,
        safeTransition: Boolean = false,
        trusted: Boolean = true,
        paused: Boolean = false,
        activeGame: Boolean = false,
        activeQueue: Boolean = false,
        terminal: Boolean = false,
    ) = EventQuestOverlayRecoveryPolicy.Evidence(
        nowMs = nowMs,
        captureTrusted = trusted,
        exactQuestOverlay = exact,
        safeMenuOrDeckState = true,
        verifiedSafeTransition = safeTransition,
        working = true,
        paused = paused,
        activeGame = activeGame,
        activeQueue = activeQueue,
        terminalOrResult = terminal,
        stableForMs = stableForMs,
    )
}
