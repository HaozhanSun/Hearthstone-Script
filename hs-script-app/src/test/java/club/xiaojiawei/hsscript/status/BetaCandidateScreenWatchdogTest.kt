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
    fun `positively classified pre-session FILL_DECK holds at 119999 then restarts at 120000`() {
        val deck = candidate(
            nowMs = 0L,
            screen = "DECK_SELECTION",
            currentSessionReady = false,
            noActiveMatchmakingEvidence = true,
            allowHealthyScreenRecovery = true,
        )
        val watchdog = BetaCandidateScreenWatchdog()
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(deck).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT,
            watchdog.observe(deck.copy(nowMs = 119_999L)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(deck.copy(nowMs = 120_000L)).action,
            "the exact positively classified pre-session deck frame reaches the bounded restart threshold",
        )

        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN,
            BetaCandidateScreenWatchdog().observe(
                deck.copy(screen = "UNKNOWN", confidence = 0, allowHealthyScreenRecovery = false),
            ).action,
            "unknown OCR must remain fail-closed",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_MATCHMAKING,
            BetaCandidateScreenWatchdog().observe(deck.copy(noActiveMatchmakingEvidence = false)).action,
            "fresh queue evidence fences the deck restart",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_MANUAL_PAUSE,
            BetaCandidateScreenWatchdog().observe(deck.copy(paused = true)).action,
            "manual pause fences the deck restart",
        )
    }

    @Test
    fun `pending mandatory mulligan restarts only after exact identity threshold when no live game remains`() {
        val pending = candidate(
            nowMs = 0L,
            screen = "MULLIGAN",
            mulligan = true,
            mandatoryRankSurrenderPending = true,
            inWar = false,
            currentSessionReady = false,
            noActiveMatchmakingEvidence = true,
        )
        val watchdog = BetaCandidateScreenWatchdog()
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(pending).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT,
            watchdog.observe(pending.copy(nowMs = 119_999L)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(pending.copy(nowMs = 120_000L)).action,
            "the identity-fenced Beta fallback reaches restart only at the exact boundary",
        )

        val activeGame = BetaCandidateScreenWatchdog()
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESET_IDENTITY,
            activeGame.observe(pending.copy(inWar = true)).action,
            "the exact pending Mulligan exception is eligible for the user-authorized Beta restart",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            activeGame.observe(pending.copy(inWar = true, nowMs = 120_000L)).action,
            "the exact pending Mulligan exception reaches the second-stage threshold",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_GAME,
            BetaCandidateScreenWatchdog().observe(pending.copy(inWar = true, mandatoryRankSurrenderPending = false)).action,
            "ordinary live gameplay remains fenced",
        )
        val paused = BetaCandidateScreenWatchdog()
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_MANUAL_PAUSE,
            paused.observe(pending.copy(paused = true)).action,
        )
        val queue = BetaCandidateScreenWatchdog()
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_MATCHMAKING,
            queue.observe(pending.copy(noActiveMatchmakingEvidence = false)).action,
        )
    }

    @Test
    fun `transient missing capture is uncertain and restarts the same-screen timer from a fresh baseline`() {
        val deck = candidate(
            nowMs = 0L,
            screen = "DECK_SELECTION",
            currentSessionReady = false,
            noActiveMatchmakingEvidence = true,
            allowHealthyScreenRecovery = true,
        )
        val watchdog = BetaCandidateScreenWatchdog()
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(deck).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN,
            watchdog.observe(deck.copy(nowMs = 60_000L, observationMissing = true, captureConfirmed = false)).action,
            "a null observation is missing evidence, not active matchmaking",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN,
            watchdog.observe(deck.copy(nowMs = 90_000L, observationMissing = true, captureConfirmed = false)).action,
            "repeated null probes remain uncertainty and do not erase the verified episode",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT,
            watchdog.observe(deck.copy(nowMs = 119_999L)).action,
            "a brief missing-capture gap preserves the same PID/HWND episode",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(deck.copy(nowMs = 120_000L)).action,
        )

        val expiredGap = BetaCandidateScreenWatchdog()
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, expiredGap.observe(deck).action)
        expiredGap.observe(deck.copy(nowMs = 60_000L, observationMissing = true, captureConfirmed = false))
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESET_IDENTITY,
            expiredGap.observe(deck.copy(nowMs = 200_001L)).action,
            "a gap beyond the bounded grace must require a new baseline",
        )

        val queued = BetaCandidateScreenWatchdog()
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, queued.observe(deck).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_MATCHMAKING,
            queued.observe(deck.copy(nowMs = 60_000L, noActiveMatchmakingEvidence = false)).action,
            "a real current queue fence remains distinct from a missing capture",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESET_IDENTITY,
            queued.observe(deck.copy(nowMs = 120_000L)).action,
        )
    }

    @Test
    fun `repeated missing captures can dispatch once from retained exact candidate evidence`() {
        val deck = candidate(
            nowMs = 0L,
            screen = "DECK_SELECTION",
            currentSessionReady = false,
            noActiveMatchmakingEvidence = true,
            allowHealthyScreenRecovery = true,
        )
        val watchdog = BetaCandidateScreenWatchdog()
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(deck).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.WAIT, watchdog.observe(deck.copy(nowMs = 1L)).action)
        val missing = deck.copy(
            nowMs = 20_000L,
            observationMissing = true,
            captureConfirmed = false,
            foregroundConfirmed = false,
            staleObservationAvailable = true,
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN, watchdog.observe(missing).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN,
            watchdog.observe(missing.copy(nowMs = 90_000L)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN,
            watchdog.observe(missing.copy(nowMs = 119_999L)).action,
            "a missing capture remains non-positive immediately before the boundary",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(missing.copy(nowMs = 120_000L)).action,
            "retained exact evidence reaches one bounded restart without treating UNKNOWN as positive",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.BACKOFF,
            watchdog.observe(missing.copy(nowMs = 120_001L)).action,
            "the same missing episode cannot dispatch a second immediate restart",
        )

        val queued = BetaCandidateScreenWatchdog()
        queued.observe(deck)
        queued.observe(deck.copy(nowMs = 1L))
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN,
            queued.observe(missing.copy(nowMs = 20_000L, noActiveMatchmakingEvidence = false)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN,
            queued.observe(missing.copy(nowMs = 120_000L, noActiveMatchmakingEvidence = false)).action,
            "active queue evidence prevents stale-observation restart",
        )
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
    fun `manual pause fences dispatch without erasing a proven reward episode`() {
        val reward = candidate(
            nowMs = 0L,
            screen = "RANK_PROGRESS_CONTINUATION",
            terminal = true,
            currentSessionReady = true,
        )
        val watchdog = BetaCandidateScreenWatchdog()
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(reward).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_MANUAL_PAUSE,
            watchdog.observe(reward.copy(nowMs = 60_000L, paused = true, working = false)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT,
            watchdog.observe(reward.copy(nowMs = 119_999L)).action,
            "resuming the same exact reward keeps the original timer and remains below the boundary",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(reward.copy(nowMs = 120_000L)).action,
            "a manual pause cannot permanently suppress the bounded restart once the operator resumes",
        )

        val boundaryPaused = BetaCandidateScreenWatchdog()
        boundaryPaused.observe(reward)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_MANUAL_PAUSE,
            boundaryPaused.observe(reward.copy(nowMs = 120_000L, paused = true, working = false)).action,
            "the exact threshold remains fenced while F2 pause is active",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            boundaryPaused.observe(reward.copy(nowMs = 120_000L)).action,
            "after explicit resume, the already-earned threshold is dispatched exactly once",
        )

        val changed = BetaCandidateScreenWatchdog()
        changed.observe(reward)
        assertEquals(BetaCandidateScreenWatchdog.Action.DEFER_MANUAL_PAUSE,
            changed.observe(reward.copy(nowMs = 60_000L, paused = true, working = false)).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESET_IDENTITY,
            changed.observe(reward.copy(nowMs = 120_000L, pid = 43L, hwnd = "hwnd-2", foregroundIdentity = "hwnd-2")).action,
            "a changed client identity after pause must restart from a fresh baseline",
        )

        val stopped = BetaCandidateScreenWatchdog()
        stopped.observe(reward)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_NOT_WORKING,
            stopped.observe(reward.copy(nowMs = 60_000L, working = false, paused = false)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESET_IDENTITY,
            stopped.observe(reward.copy(nowMs = 120_000L)).action,
            "a stopped worker without an explicit pause must not retain a recovery episode",
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
        mandatoryRankSurrenderPending: Boolean = false,
        terminal: Boolean = false,
        currentSessionReady: Boolean = true,
        noActiveMatchmakingEvidence: Boolean = true,
        allowHealthyScreenRecovery: Boolean = false,
        observationMissing: Boolean = false,
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
        mandatoryRankSurrenderPending = mandatoryRankSurrenderPending,
        terminal = terminal,
        currentSessionReady = currentSessionReady,
        noActiveMatchmakingEvidence = noActiveMatchmakingEvidence,
        allowHealthyScreenRecovery = allowHealthyScreenRecovery,
        observationMissing = observationMissing,
    )
}
