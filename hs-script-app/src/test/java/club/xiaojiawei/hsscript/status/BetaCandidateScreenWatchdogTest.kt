package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BetaCandidateScreenWatchdogTest {

    @Test
    fun `repeated positive candidate reaches restart at exactly two minutes`() {
        val watchdog = BetaCandidateScreenWatchdog()

        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESET_IDENTITY,
            watchdog.observe(candidate(nowMs = 0L)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT,
            watchdog.observe(candidate(nowMs = 119_999L)).action,
        )
        val threshold = watchdog.observe(candidate(nowMs = 120_000L))
        assertEquals(BetaCandidateScreenWatchdog.Action.RESTART, threshold.action)
        assertEquals(120_000L, threshold.stableForMs)
        assertEquals("candidate-stable-threshold-reached", threshold.reason)
    }

    @Test
    fun `screen foreground pid and window transitions reset the candidate episode`() {
        val watchdog = BetaCandidateScreenWatchdog()
        watchdog.observe(candidate(nowMs = 0L, screen = "RECONNECT"))

        val screenChanged = watchdog.observe(candidate(nowMs = 120_000L, screen = "SERVER_UNAVAILABLE"))
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, screenChanged.action)

        val foregroundChanged = watchdog.observe(
            candidate(nowMs = 240_000L, screen = "SERVER_UNAVAILABLE", hwnd = "hwnd-2"),
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, foregroundChanged.action)

        val pidChanged = watchdog.observe(
            candidate(nowMs = 360_000L, screen = "SERVER_UNAVAILABLE", pid = 43L),
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, pidChanged.action)
    }

    @Test
    fun `capture or OCR uncertainty clears evidence and never restarts`() {
        val watchdog = BetaCandidateScreenWatchdog()
        watchdog.observe(candidate(nowMs = 0L))

        val uncertain = watchdog.observe(
            candidate(nowMs = 120_000L, confidence = 0, captureConfirmed = false, screen = "UNKNOWN"),
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN, uncertain.action)

        val fresh = watchdog.observe(candidate(nowMs = 240_000L))
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, fresh.action)
    }

    @Test
    fun `unknown OCR only becomes a candidate when a centered modal visual is repeated`() {
        val watchdog = BetaCandidateScreenWatchdog()
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN,
            watchdog.observe(candidate(nowMs = 0L, screen = "UNKNOWN", confidence = 0)).action,
        )

        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESET_IDENTITY,
            watchdog.observe(
                candidate(
                    nowMs = 1L,
                    screen = "UNKNOWN",
                    confidence = 0,
                    visualModalConfirmed = true,
                    visualModalFingerprint = 0x1234L,
                ),
            ).action,
        )
        val threshold = watchdog.observe(
            candidate(
                nowMs = 120_001L,
                screen = "UNKNOWN",
                confidence = 0,
                visualModalConfirmed = true,
                visualModalFingerprint = 0x1234L,
            ),
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESTART, threshold.action)

        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESET_IDENTITY,
            watchdog.observe(
                candidate(
                    nowMs = 240_002L,
                    screen = "UNKNOWN",
                    confidence = 0,
                    visualModalConfirmed = true,
                    visualModalFingerprint = 0x5678L,
                ),
            ).action,
            "a changed visual fingerprint must start a new episode",
        )
    }

    @Test
    fun `recognized dialog outranks actionable underlay while healthy screens never restart`() {
        val watchdog = BetaCandidateScreenWatchdog()
        watchdog.observe(
            candidate(
                nowMs = 0L,
                screen = "DECK_SELECTION",
                dialogScreen = "START_GAME_ERROR_DIALOG",
            ),
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(
                candidate(
                    nowMs = 120_000L,
                    screen = "DECK_SELECTION",
                    dialogScreen = "START_GAME_ERROR_DIALOG",
                ),
            ).action,
            "the recognized modal must be the stable candidate, not the actionable underlay",
        )

        val healthy = BetaCandidateScreenWatchdog()
        healthy.observe(candidate(nowMs = 0L, screen = "MATCHMAKING"))
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_HEALTHY_SCREEN,
            healthy.observe(candidate(nowMs = 120_000L, screen = "MATCHMAKING")).action,
        )
    }

    @Test
    fun `opponent disconnect dialog uses exact 119999 and 120000 second-stage boundary`() {
        val watchdog = BetaCandidateScreenWatchdog()
        val dialog = candidate(
            nowMs = 0L,
            screen = "START_GAME_ERROR_DIALOG",
            dialogScreen = "START_GAME_ERROR_DIALOG",
            currentSessionReady = false,
            noActiveMatchmakingEvidence = true,
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(dialog).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT,
            watchdog.observe(dialog.copy(nowMs = 119_999L)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(dialog.copy(nowMs = 120_000L)).action,
        )
    }

    @Test
    fun `season rank reward overlay is restartable only with terminal proof and current session`() {
        val watchdog = BetaCandidateScreenWatchdog()
        val reward = candidate(
            nowMs = 0L,
            screen = "SEASON_RANK_AWARD_REWARDS",
            dialogScreen = "SEASON_RANK_AWARD_REWARDS",
            terminal = true,
            currentSessionReady = true,
            noActiveMatchmakingEvidence = true,
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(reward).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT,
            watchdog.observe(reward.copy(nowMs = 119_999L)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(reward.copy(nowMs = 120_000L)).action,
        )

        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_TERMINAL,
            BetaCandidateScreenWatchdog().observe(reward.copy(terminal = false)).action,
            "reward-looking screen without terminal proof must not restart",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_MATCHMAKING,
            BetaCandidateScreenWatchdog().observe(reward.copy(noActiveMatchmakingEvidence = false)).action,
            "a fresh queue visual must fence reward-overlay restart",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_MANUAL_PAUSE,
            BetaCandidateScreenWatchdog().observe(reward.copy(paused = true)).action,
            "manual pause must fence reward-overlay restart",
        )
    }

    @Test
    fun `empty-session constructed deck screen is restartable only as an explicitly authorized stalled candidate`() {
        val watchdog = BetaCandidateScreenWatchdog()
        val stuckDeck = candidate(
            nowMs = 0L,
            screen = "DECK_SELECTION",
            currentSessionReady = false,
            allowHealthyScreenRecovery = true,
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(stuckDeck).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(stuckDeck.copy(nowMs = BetaCandidateScreenWatchdog.DEFAULT_STABLE_THRESHOLD_MS)).action,
            "a fresh no-session deck frame may restart only after the full two-minute unchanged-screen threshold",
        )

        val currentSession = BetaCandidateScreenWatchdog()
        val currentDeck = stuckDeck.copy(currentSessionReady = true)
        assertEquals(BetaCandidateScreenWatchdog.Action.DEFER_HEALTHY_SCREEN, currentSession.observe(currentDeck).action)
    }

    @Test
    fun `terminal result screen reaches hard restart only after current session proof and two minutes`() {
        val watchdog = BetaCandidateScreenWatchdog()
        val result = candidate(
            nowMs = 0L,
            screen = "RANK_PROGRESS_CONTINUATION",
            terminal = true,
            currentSessionReady = true,
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(result).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT,
            watchdog.observe(result.copy(nowMs = 119_999L)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(result.copy(nowMs = 120_000L)).action,
            "a persistent result page must use the existing full close-and-starter-chain restart",
        )

        val unproven = BetaCandidateScreenWatchdog()
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_TERMINAL,
            unproven.observe(result.copy(terminal = false)).action,
            "a result-looking frame without current terminal proof must not restart",
        )
    }

    @Test
    fun `fresh active matchmaking evidence always fences even a repeated unknown modal`() {
        val watchdog = BetaCandidateScreenWatchdog()
        val queued = candidate(
            nowMs = 0L,
            screen = "UNKNOWN",
            visualModalConfirmed = true,
            visualModalFingerprint = 0xCAFE,
            noActiveMatchmakingEvidence = false,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_MATCHMAKING,
            watchdog.observe(queued).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_MATCHMAKING,
            watchdog.observe(queued.copy(nowMs = 120_000L)).action,
        )

        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_MATCHMAKING,
            BetaCandidateScreenWatchdog().observe(
                candidate(nowMs = 0L, screen = "MATCHMAKING", noActiveMatchmakingEvidence = false),
            ).action,
            "the real SEARCHING/MATCHMAKING visual must override any stale-screen label",
        )
    }

    @Test
    fun `manual pause live game mulligan and terminal guards reset without restart`() {
        val guarded = listOf(
            BetaCandidateScreenWatchdog.Action.DEFER_MANUAL_PAUSE to candidate(nowMs = 0L, paused = true),
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_GAME to candidate(nowMs = 0L, inWar = true),
            BetaCandidateScreenWatchdog.Action.DEFER_MULLIGAN to candidate(nowMs = 0L, mulligan = true),
            BetaCandidateScreenWatchdog.Action.DEFER_TERMINAL to candidate(nowMs = 0L, terminal = true),
        )

        guarded.forEach { (expected, snapshot) ->
            val watchdog = BetaCandidateScreenWatchdog()
            watchdog.observe(candidate(nowMs = 0L))
            val decision = watchdog.observe(snapshot.copy(nowMs = 120_000L))
            assertEquals(expected, decision.action)
            assertEquals(0, decision.restartCount)
        }
    }

    @Test
    fun `restart enters bounded backoff then rearms and exhausts repeated same-identity restarts`() {
        val watchdog = BetaCandidateScreenWatchdog()
        watchdog.observe(candidate(nowMs = 0L))
        assertEquals(BetaCandidateScreenWatchdog.Action.RESTART, watchdog.observe(candidate(nowMs = 120_000L)).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.BACKOFF, watchdog.observe(candidate(nowMs = 120_001L)).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.REARM, watchdog.observe(candidate(nowMs = 240_000L)).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.RESTART, watchdog.observe(candidate(nowMs = 360_000L)).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.BACKOFF, watchdog.observe(candidate(nowMs = 360_001L)).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.REARM, watchdog.observe(candidate(nowMs = 480_000L)).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.EXHAUSTED, watchdog.observe(candidate(nowMs = 600_000L)).action)
        assertTrue(watchdog.observe(candidate(nowMs = 720_000L)).action == BetaCandidateScreenWatchdog.Action.EXHAUSTED)
    }

    private fun candidate(
        nowMs: Long,
        screen: String = "RECONNECT",
        dialogScreen: String? = null,
        visualModalConfirmed: Boolean = false,
        visualModalFingerprint: Long? = null,
        confidence: Int = 100,
        captureConfirmed: Boolean = true,
        foregroundConfirmed: Boolean = true,
        pid: Long = 42L,
        hwnd: String = "hwnd-1",
        working: Boolean = true,
        paused: Boolean = false,
        inWar: Boolean = false,
        mulligan: Boolean = false,
        terminal: Boolean = false,
        currentSessionReady: Boolean = true,
        noActiveMatchmakingEvidence: Boolean = true,
        allowHealthyScreenRecovery: Boolean = false,
    ) = BetaCandidateScreenWatchdog.Snapshot(
        nowMs = nowMs,
        screen = screen,
        dialogScreen = dialogScreen,
        visualModalConfirmed = visualModalConfirmed,
        visualModalFingerprint = visualModalFingerprint,
        confidence = confidence,
        captureConfirmed = captureConfirmed,
        foregroundConfirmed = foregroundConfirmed,
        pid = pid,
        hwnd = hwnd,
        foregroundIdentity = hwnd,
        working = working,
        paused = paused,
        inWar = inWar,
        mulligan = mulligan,
        terminal = terminal,
        currentSessionReady = currentSessionReady,
        noActiveMatchmakingEvidence = noActiveMatchmakingEvidence,
        allowHealthyScreenRecovery = allowHealthyScreenRecovery,
    )
}
