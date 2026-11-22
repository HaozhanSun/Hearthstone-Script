package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BetaCandidateRestartFencePolicyTest {
    @Test
    fun `empty-session launcher ignores stale terminal latch but retains every live fence`() {
        val base = BetaCandidateRestartFencePolicy.Snapshot(
            betaChannel = true,
            observationPresent = true,
            working = true,
            paused = false,
            inWar = false,
            mulligan = false,
            noActiveMatchmakingEvidence = true,
            currentSessionReady = false,
            screen = "STARTUP_LAUNCHER",
            lifecycleTerminalLatch = true,
            authorizedTerminalVisualProof = false,
            foregroundConfirmed = true,
            pidMatches = true,
            hwndMatches = true,
            currentWindowVerified = true,
        )
        assertNull(BetaCandidateRestartFencePolicy.rejectionReason(base))
        assertEquals(
            "active-matchmaking-evidence",
            BetaCandidateRestartFencePolicy.rejectionReason(base.copy(noActiveMatchmakingEvidence = false)),
        )
        assertEquals(
            "active-game",
            BetaCandidateRestartFencePolicy.rejectionReason(base.copy(inWar = true)),
        )
        assertEquals(
            "manual-pause",
            BetaCandidateRestartFencePolicy.rejectionReason(base.copy(paused = true)),
        )
        assertEquals(
            "pid-changed",
            BetaCandidateRestartFencePolicy.rejectionReason(base.copy(pidMatches = false)),
        )
        assertEquals(
            "hwnd-changed",
            BetaCandidateRestartFencePolicy.rejectionReason(base.copy(hwndMatches = false)),
        )
        assertEquals(
            "current-window-unverified",
            BetaCandidateRestartFencePolicy.rejectionReason(base.copy(currentWindowVerified = false)),
        )
        assertEquals(
            "foreground-unverified",
            BetaCandidateRestartFencePolicy.rejectionReason(base.copy(foregroundConfirmed = false)),
        )
        assertEquals(
            "terminal-input-fence",
            BetaCandidateRestartFencePolicy.rejectionReason(base.copy(terminalInputFence = true)),
        )
        assertEquals(
            "current-session-ready",
            BetaCandidateRestartFencePolicy.rejectionReason(
                base.copy(currentSessionReady = true, lifecycleTerminalLatch = false),
            ),
        )
    }

    @Test
    fun `terminal reward remains proof-bound`() {
        val reward = BetaCandidateRestartFencePolicy.Snapshot(
            betaChannel = true,
            observationPresent = true,
            working = true,
            paused = false,
            inWar = false,
            mulligan = false,
            noActiveMatchmakingEvidence = true,
            currentSessionReady = true,
            screen = "RANK_PROGRESS_CONTINUATION",
            lifecycleTerminalLatch = false,
            authorizedTerminalVisualProof = false,
            foregroundConfirmed = true,
            pidMatches = true,
            hwndMatches = true,
            currentWindowVerified = true,
        )
        assertEquals(
            "terminal-reward-proof-missing",
            BetaCandidateRestartFencePolicy.rejectionReason(reward),
        )
        assertNull(
            BetaCandidateRestartFencePolicy.rejectionReason(
                reward.copy(authorizedTerminalVisualProof = true),
            ),
        )
    }

    @Test
    fun `pending mandatory mulligan permits only no-session identity-fenced restart`() {
        val pendingMulligan = BetaCandidateRestartFencePolicy.Snapshot(
            betaChannel = true,
            observationPresent = true,
            working = true,
            paused = false,
            inWar = false,
            mulligan = true,
            mandatoryRankSurrenderPending = true,
            noActiveMatchmakingEvidence = true,
            currentSessionReady = false,
            screen = "MULLIGAN",
            lifecycleTerminalLatch = false,
            authorizedTerminalVisualProof = false,
            foregroundConfirmed = true,
            pidMatches = true,
            hwndMatches = true,
            currentWindowVerified = true,
        )
        assertNull(BetaCandidateRestartFencePolicy.rejectionReason(pendingMulligan))
        assertNull(BetaCandidateRestartFencePolicy.rejectionReason(pendingMulligan.copy(inWar = true)))
        assertEquals(
            "active-game",
            BetaCandidateRestartFencePolicy.rejectionReason(
                pendingMulligan.copy(inWar = true, mandatoryRankSurrenderPending = false),
            ),
        )
        assertEquals(
            "active-game",
            BetaCandidateRestartFencePolicy.rejectionReason(
                pendingMulligan.copy(inWar = true, screen = "UNKNOWN"),
            ),
            "phase/barrier state alone must not authorize a restart without the exact Mulligan screen",
        )
        assertEquals(
            "mulligan",
            BetaCandidateRestartFencePolicy.rejectionReason(
                pendingMulligan.copy(screen = "UNKNOWN"),
            ),
            "a no-session pending phase still requires the exact Mulligan screen",
        )
        assertEquals(
            "manual-pause",
            BetaCandidateRestartFencePolicy.rejectionReason(pendingMulligan.copy(paused = true)),
        )
        assertEquals(
            "active-matchmaking-evidence",
            BetaCandidateRestartFencePolicy.rejectionReason(
                pendingMulligan.copy(noActiveMatchmakingEvidence = false),
            ),
        )
    }
}
