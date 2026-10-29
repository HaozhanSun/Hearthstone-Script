package club.xiaojiawei.hsscript.status

import java.awt.Point
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupInputReadinessTest {
    @AfterEach
    fun reset() = StartupInputReadiness.resetForTest()

    @Test
    fun `online readiness is bound to the exact live process and window`() {
        StartupInputReadiness.recordOnlineMenu(101, "hwnd-a", "TOURNAMENT", modalVisible = false)

        assertTrue(StartupInputReadiness.isMenuReady(101, "hwnd-a", "TOURNAMENT"))
        assertFalse(StartupInputReadiness.isMenuReady(101, "hwnd-a", "LOGIN"))
        assertFalse(StartupInputReadiness.isMenuReady(102, "hwnd-a", "TOURNAMENT"))
        assertFalse(StartupInputReadiness.isMenuReady(101, "hwnd-b", "TOURNAMENT"))

        StartupInputReadiness.recordOnlineMenu(101, "hwnd-a", "HOME", modalVisible = true)
        assertFalse(StartupInputReadiness.isMenuReady(101, "hwnd-a", "TOURNAMENT"))
    }

    @Test
    fun `fresh enabled deck selection proof grants only matchmaking start with empty Power log`() {
        assertTrue(
            StartupInputReadiness.recordDeckSelectionStart(
                pid = 101,
                hwnd = "hwnd-a",
                confidence = 100,
                evidence = "deck-selection-title-roi",
                expectedRunMode = "WILD",
                observedRunMode = "WILD",
                enabledStartButtonVisible = true,
                modalVisible = false,
                now = 10_000L,
            ),
        )
        val proof = StartupInputReadiness.isDeckSelectionStartReady(101, "hwnd-a", 10_001L)
        assertTrue(proof)
        assertFalse(StartupInputReadiness.isMenuReady(101, "hwnd-a", "TOURNAMENT"))

        assertTrue(
            StartupInputAuthorizationPolicy.allows(
                action = "matchmaking.start",
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                freshDeckSelectionStartReady = proof,
            ),
            "an authorized deck-selection capture can start a match before Power.log has bytes",
        )
        listOf("mouse.left", "strategy.card.play", "surrender.request").forEach { action ->
            assertFalse(
                StartupInputAuthorizationPolicy.allows(
                    action = action,
                    currentSessionReady = false,
                    freshOnlineMenuReady = false,
                    freshDeckSelectionStartReady = proof,
                ),
                "deck-selection readiness must not authorize $action",
            )
        }
        assertFalse(StartupInputReadiness.isDeckSelectionStartReady(102, "hwnd-a", 10_001L))
        assertFalse(StartupInputReadiness.isDeckSelectionStartReady(101, "hwnd-b", 10_001L))
        assertFalse(
            StartupInputReadiness.isDeckSelectionStartReady(
                101,
                "hwnd-a",
                10_000L + StartupInputReadiness.DECK_SELECTION_START_PROOF_MAX_AGE_MS + 1,
            ),
        )
    }

    @Test
    fun `deck start proof survives stale game phase and is independent of pre-match rank`() {
        assertTrue(
            StartupInputReadiness.recordDeckSelectionStart(
                pid = 50992,
                hwnd = "exact-game-window",
                confidence = 96,
                evidence = "deck-selection-title-roi",
                expectedRunMode = "WILD",
                observedRunMode = "WILD",
                enabledStartButtonVisible = true,
                modalVisible = false,
                now = 50_000L,
            ),
        )
        val proof = StartupInputReadiness.isDeckSelectionStartReady(50992, "exact-game-window", 50_100L)

        // Rank 9 and an unrecognized pre-match rank are both irrelevant until
        // CREATE_GAME reaches the in-game mulligan preflight.
        listOf("rank-9", "unknown-rank").forEach { rankObservation ->
            assertTrue(
                StartupInputAuthorizationPolicy.allowsDeckSelectionStart(
                    action = "matchmaking.start",
                    processId = 50992,
                    hwnd = "exact-game-window",
                    exactWindowVerified = true,
                    freshDeckSelectionStartReady = proof,
                ),
                "$rankObservation cannot veto the exact, fresh deck-selection Start proof",
            )
        }
        assertFalse(
            StartupInputAuthorizationPolicy.allowsDeckSelectionStart(
                action = "mouse.left",
                processId = 50992,
                hwnd = "exact-game-window",
                exactWindowVerified = true,
                freshDeckSelectionStartReady = proof,
            ),
            "the proof is exclusive to queue Start",
        )
        assertFalse(
            StartupInputAuthorizationPolicy.allowsDeckSelectionStart(
                action = "matchmaking.start",
                processId = 50992,
                hwnd = "other-window",
                exactWindowVerified = false,
                freshDeckSelectionStartReady = proof,
            ),
            "a different or unverified game window cannot use the proof",
        )
    }

    @Test
    fun `empty current Power log permits mode input only after fresh online HUB proof`() {
        val startupPurpose = ScreenRecoveryCapturePurposePolicy.purposeForStateRecovery(
            tournamentMode = false,
            activeGame = false,
            mulligan = false,
            terminal = false,
            recentInputIntent = false,
            warModelAuthoritative = false,
            startupProbe = true,
            noActiveQueueEvidence = true,
        )
        assertEquals(ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE, startupPurpose)
        val startupCaptureAuthorized = ScreenRecoveryCapturePurposePolicy
            .allowsPreSessionStartupClientProbe(startupPurpose, currentSessionReady = false)
        assertTrue(startupCaptureAuthorized)
        assertNull(
            ScreenRecoveryCapturePurposePolicy.preflightRejectionReason(
                currentSessionReady = false,
                preSessionQueueModalAuthorized = false,
                preSessionMatchmakingAuthorized = false,
                preSessionStartupClientProbeAuthorized = startupCaptureAuthorized,
            ),
        )
        assertFalse(
            club.xiaojiawei.hsscript.status.CurrentGameScreenReadinessPolicy.isReady(
                gameWindowVerified = true,
                attachedPowerLogPath = "D:/Logs/session/Power.log",
                currentSessionPowerLogPath = "D:/Logs/session/Power.log",
                powerLogLength = 0L,
            ),
        )

        // This proof is only recorded by the startup-probe classifier after an
        // exact current-PID/HWND foreground capture; it is independent of log readiness.
        StartupInputReadiness.recordOnlineMenu(101, "hwnd-a", "HOME", modalVisible = false)
        val hubProof = StartupInputReadiness.isMenuReady(101, "hwnd-a", "HUB")
        assertTrue(hubProof)
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "mouse.left",
                paused = false,
                working = true,
                currentSessionReady = false,
                freshOnlineMenuReady = hubProof,
            ),
            "a fresh positive online HUB observation breaks the Power.log-before-start cycle",
        )
    }

    @Test
    fun `reconnect and unknown observations never create online HUB dispatch proof`() {
        StartupInputReadiness.recordOnlineMenu(101, "hwnd-a", "RECONNECT", modalVisible = false)
        assertFalse(StartupInputReadiness.isMenuReady(101, "hwnd-a", "HUB"))
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "mouse.left",
                paused = false,
                working = true,
                currentSessionReady = false,
                freshOnlineMenuReady = StartupInputReadiness.isMenuReady(101, "hwnd-a", "HUB"),
            ),
            "reconnect prompts need their separate exact one-shot permit",
        )

        StartupInputReadiness.recordOnlineMenu(101, "hwnd-a", "UNKNOWN", modalVisible = false)
        assertFalse(StartupInputReadiness.isMenuReady(101, "hwnd-a", "HUB"))
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "mouse.left",
                paused = false,
                working = true,
                currentSessionReady = false,
                freshOnlineMenuReady = StartupInputReadiness.isMenuReady(101, "hwnd-a", "HUB"),
            ),
        )
    }

    @Test
    fun `fresh exact HUB chooser proof dispatches only Traditional Battle selection`() {
        val now = 50_000L
        val actionCount = java.util.concurrent.atomic.AtomicInteger()
        StartupInputReadiness.recordHubModeChooser(101, "hwnd-a", now)
        val exactProof = StartupInputReadiness.isHubTraditionalBattleSelectionReady(101, "hwnd-a", "HUB", now + 1)

        if (ActionDispatchGate.allowForState(
                action = StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION,
                paused = false,
                working = true,
                currentSessionReady = false,
                startupTraditionalBattleSelectionReady = exactProof,
            )
        ) actionCount.incrementAndGet()
        assertEquals(1, actionCount.get(), "the verified intended action reaches the dispatch boundary")
        assertFalse(ActionDispatchGate.allowForState(
            action = "mouse.left",
            paused = false,
            working = true,
            currentSessionReady = false,
            startupTraditionalBattleSelectionReady = exactProof,
        ), "the proof cannot authorize generic input")
        assertFalse(ActionDispatchGate.allowForState(
            action = "matchmaking.start",
            paused = false,
            working = true,
            currentSessionReady = false,
            startupTraditionalBattleSelectionReady = exactProof,
        ), "the chooser proof cannot authorize matchmaking")
        assertFalse(ActionDispatchGate.allowForState(
            action = "surrender.request",
            paused = false,
            working = true,
            currentSessionReady = false,
            startupTraditionalBattleSelectionReady = exactProof,
        ), "the startup exception does not bypass rank/surrender capabilities")
        assertFalse(StartupInputReadiness.isHubTraditionalBattleSelectionReady(102, "hwnd-a", "HUB", now + 1))
        assertFalse(StartupInputReadiness.isHubTraditionalBattleSelectionReady(101, "hwnd-b", "HUB", now + 1))
        assertFalse(StartupInputReadiness.isHubTraditionalBattleSelectionReady(101, "hwnd-a", "TOURNAMENT", now + 1))
        assertFalse(StartupInputReadiness.isHubTraditionalBattleSelectionReady(
            101, "hwnd-a", "HUB", now + StartupInputReadiness.HUB_MODE_CHOOSER_PROOF_MAX_AGE_MS + 1,
        ))
        StartupInputReadiness.recordOnlineMenu(101, "hwnd-a", "UNKNOWN", modalVisible = false, now = now + 2)
        assertFalse(StartupInputReadiness.isHubTraditionalBattleSelectionReady(101, "hwnd-a", "HUB", now + 3))
    }

    @Test
    fun `HUB chooser proof remains valid through 30 seconds and expires after`() {
        val observedAt = 80_000L
        StartupInputReadiness.recordHubModeChooser(101, "hwnd-a", observedAt)

        assertEquals(30_000L, StartupInputReadiness.HUB_MODE_CHOOSER_PROOF_MAX_AGE_MS)
        assertTrue(StartupInputReadiness.isHubTraditionalBattleSelectionReady(
            101, "hwnd-a", "HUB", observedAt + 30_000L,
        ), "the bounded proof remains usable at its maximum age")
        assertFalse(StartupInputReadiness.isHubTraditionalBattleSelectionReady(
            101, "hwnd-a", "HUB", observedAt + 30_001L,
        ), "expired chooser proof must not reach the scheduled callback")
    }

    @Test
    fun `pre-session reconnect permission is one exact short-lived control for one process`() {
        val point = Point(960, 650)
        assertTrue(StartupInputReadiness.issueReconnectPermit(101, "hwnd-a", point, now = 1_000L))
        assertTrue(StartupInputReadiness.reconnectPermitMatches(101, "hwnd-a", point, now = 1_001L))
        assertFalse(StartupInputReadiness.reconnectPermitMatches(102, "hwnd-a", point, now = 1_001L))
        assertFalse(StartupInputReadiness.reconnectPermitMatches(101, "hwnd-a", Point(960, 651), now = 1_001L))
        assertFalse(
            StartupInputReadiness.reconnectPermitMatches(
                101,
                "hwnd-a",
                point,
                now = 1_000L + StartupInputReadiness.RECONNECT_PERMIT_LIFETIME_MS + 1,
            ),
        )
        assertTrue(StartupInputReadiness.consumeReconnectPermit(101, "hwnd-a", point, now = 1_001L))
        assertFalse(StartupInputReadiness.hasReconnectPermit(now = 1_002L))
    }

    @Test
    fun `only a fresh exact reconnect prompt can issue recovery input permission`() {
        assertTrue(
            StartupInputAuthorizationPolicy.mayIssuePreSessionReconnectPermit(
                screenKind = "RECONNECT",
                currentSessionReady = false,
                activeGame = false,
                activeMatchmaking = false,
                freshCapture = true,
                exactWindowAndForeground = true,
            ),
        )
        assertFalse(
            StartupInputAuthorizationPolicy.mayIssuePreSessionReconnectPermit(
                screenKind = "SERVER_UNAVAILABLE",
                currentSessionReady = false,
                activeGame = false,
                activeMatchmaking = false,
                freshCapture = true,
                exactWindowAndForeground = true,
            ),
            "Yogg is a non-game server outage and never grants reconnect input",
        )
        assertFalse(
            StartupInputAuthorizationPolicy.mayIssuePreSessionReconnectPermit(
                screenKind = "RECONNECT",
                currentSessionReady = false,
                activeGame = false,
                activeMatchmaking = true,
                freshCapture = true,
                exactWindowAndForeground = true,
            ),
        )
        assertFalse(
            StartupInputAuthorizationPolicy.mayIssuePreSessionReconnectPermit(
                screenKind = "RECONNECT",
                currentSessionReady = false,
                activeGame = false,
                activeMatchmaking = false,
                freshCapture = true,
                exactWindowAndForeground = false,
            ),
        )
    }
}
