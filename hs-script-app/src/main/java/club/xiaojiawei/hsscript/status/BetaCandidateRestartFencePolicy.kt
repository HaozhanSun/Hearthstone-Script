package club.xiaojiawei.hsscript.status

/**
 * Pure final-dispatch fence for the Beta candidate watchdog.  Keeping this
 * decision independent from process lookups makes stale lifecycle state and
 * every live safety veto directly regression-testable.
 */
internal object BetaCandidateRestartFencePolicy {
    data class Snapshot(
        val betaChannel: Boolean,
        val observationPresent: Boolean,
        val working: Boolean,
        val paused: Boolean,
        val inWar: Boolean,
        val mulligan: Boolean,
        val noActiveMatchmakingEvidence: Boolean,
        val terminalInputFence: Boolean = false,
        val currentSessionReady: Boolean,
        val screen: String,
        val lifecycleTerminalLatch: Boolean,
        val authorizedTerminalVisualProof: Boolean,
        /** Fail closed for synthetic/forged snapshots; production callsites pass capture authority explicitly. */
        val foregroundConfirmed: Boolean = false,
        val pidMatches: Boolean,
        val hwndMatches: Boolean,
        val currentWindowVerified: Boolean,
    )

    fun rejectionReason(snapshot: Snapshot): String? {
        if (!snapshot.betaChannel) return "non-beta-channel"
        if (!snapshot.observationPresent) return "observation-missing"
        if (!snapshot.working) return "worker-not-working"
        if (snapshot.paused) return "manual-pause"
        if (snapshot.inWar) return "active-game"
        if (snapshot.mulligan) return "mulligan"
        if (snapshot.terminalInputFence) return "terminal-input-fence"
        if (!snapshot.noActiveMatchmakingEvidence) return "active-matchmaking-evidence"
        if (!snapshot.foregroundConfirmed) return "foreground-unverified"

        val screen = snapshot.screen.uppercase()
        val terminalState = snapshot.currentSessionReady &&
            (snapshot.lifecycleTerminalLatch || snapshot.authorizedTerminalVisualProof)
        val terminalScreen = screen in setOf(
            "RESULT",
            "RANK_PROGRESS_CONTINUATION",
            "SEASON_RANK_AWARD",
            "SEASON_RANK_AWARD_CHEST",
            "SEASON_RANK_AWARD_REWARDS",
        )
        val opponentDisconnectDialog = screen in setOf(
            "START_GAME_ERROR_DIALOG",
            "RECONNECT_FAILURE",
        )
        if (terminalState && !(terminalScreen && snapshot.currentSessionReady)) {
            return "terminal-proof-or-screen-missing"
        }
        if (terminalScreen && !(terminalState && snapshot.currentSessionReady)) {
            return "terminal-reward-proof-missing"
        }
        if (opponentDisconnectDialog && snapshot.currentSessionReady) {
            return "opponent-disconnect-current-session-not-empty"
        }
        if (!terminalScreen && !opponentDisconnectDialog && snapshot.currentSessionReady) {
            return "current-session-ready"
        }
        if (!terminalScreen && !opponentDisconnectDialog && terminalState) {
            return "terminal-state-without-terminal-screen"
        }
        if (!snapshot.pidMatches) return "pid-changed"
        if (!snapshot.hwndMatches) return "hwnd-changed"
        if (!snapshot.currentWindowVerified) return "current-window-unverified"
        return null
    }
}
