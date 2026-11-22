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
}
