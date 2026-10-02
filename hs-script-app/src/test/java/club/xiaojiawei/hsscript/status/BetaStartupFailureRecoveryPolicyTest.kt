package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BetaStartupFailureRecoveryPolicyTest {

    @Test
    fun `only exact native Hearthstone application error caption with breakpoint body is accepted`() {
        val exact = candidate()
        assertTrue(WindowsApplicationErrorDialogProbe.isTargetDialog(exact))
        assertEquals(1536L, exact.hostPid) // Windows may host this native dialog in csrss.exe.
        assertEquals(null, exact.ownerPid)
        val first = WindowsApplicationErrorDialogProbe.observe(listOf(exact), 10L)
        assertEquals(10L, first?.firstSeenAtMs)
        assertEquals(10L, WindowsApplicationErrorDialogProbe.observe(listOf(exact), 20L)?.firstSeenAtMs)
        assertNull(
            WindowsApplicationErrorDialogProbe.observe(
                listOf(exact.copy(title = "Hearthstone.exe - Application Error (2)")),
                30L,
            ),
        )
        assertNull(
            WindowsApplicationErrorDialogProbe.observe(
                listOf(exact.copy(body = "A different application failure")),
                40L,
            ),
        )
    }

    @Test
    fun `confirmed current PID modal outranks startup grace then uses paced retries`() {
        val policy = BetaStartupFailureRecoveryPolicy(noProgressTimeoutMs = 180_000L)
        val observed = snapshot(
            nowMs = 200_000L,
            stalledForMs = 37_000L,
            powerLogIsCurrentSession = true,
            powerLogLength = 409_108L,
            powerLogAgeMs = 5_000L,
            dialog = dialog(firstSeenAtMs = 197_000L),
        )

        val confirming = policy.observe(observed.copy(nowMs = 199_999L))
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.WAIT, confirming.action)
        assertEquals("application-error-dialog-confirmation-window", confirming.reason)
        val first = policy.observe(observed)
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.RESTART_CLIENT, first.action)
        assertEquals(1, first.attempt)
        assertEquals(15_000L, first.retryDelayMs)
        val backoff = policy.observe(observed.copy(nowMs = 214_999L, stalledForMs = 52_000L))
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.WAIT, backoff.action)
        assertEquals("paced-retry-backoff", backoff.reason)
        val retry = policy.observe(observed.copy(nowMs = 215_000L, stalledForMs = 52_001L))
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.RESTART_CLIENT, retry.action)
        assertEquals(2, retry.attempt)
        assertEquals(30_000L, retry.retryDelayMs)
    }

    @Test
    fun `dialog observed before current game PID is stale but later exact dialog remains eligible`() {
        val policy = BetaStartupFailureRecoveryPolicy(noProgressTimeoutMs = 0L)
        val stale = policy.observe(
            snapshot(dialog = dialog(firstSeenAtMs = 4_999L), processStartedAtMs = 5_000L),
        )
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.WAIT, stale.action)
        assertEquals("stale-or-unattributed-error-dialog", stale.reason)
        val lateButSamePid = policy.observe(
            snapshot(dialog = dialog(firstSeenAtMs = 130_000L), processStartedAtMs = 5_000L),
        )
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.RESTART_CLIENT, lateButSamePid.action)
    }

    @Test
    fun `alive PID with unverified window cannot be killed from an unowned dialog`() {
        val policy = BetaStartupFailureRecoveryPolicy(noProgressTimeoutMs = 0L)
        val unverified = snapshot(
            dialog = dialog(firstSeenAtMs = 10_000L),
            gameWindowMatchesPid = false,
        )
        val first = policy.observe(unverified)
        assertEquals(1_536L, unverified.dialog?.hostPid)
        assertEquals(null, unverified.dialog?.ownerPid)
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.REBIND_WINDOW, first.action)
        assertEquals("application-error-dialog-game-window-unverified", first.reason)
        assertEquals(
            BetaStartupFailureRecoveryPolicy.Action.RESTART_STARTER_CHAIN,
            policy.observe(unverified.copy(nowMs = 200_000L + first.retryDelayMs)).action,
        )
    }

    @Test
    fun `missing process and unbound or empty Power log rebinds then relaunches with capped paced retries`() {
        val policy = BetaStartupFailureRecoveryPolicy(noProgressTimeoutMs = 0L)
        val missing = snapshot(
            processAlive = false,
            currentPid = null,
            gameWindowMatchesPid = false,
            powerLogIsCurrentSession = false,
            powerLogLength = 0L,
        )
        val rebind = policy.observe(missing)
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.REBIND_WINDOW, rebind.action)
        assertEquals(15_000L, rebind.retryDelayMs)
        assertEquals(
            "paced-retry-backoff",
            policy.observe(missing.copy(nowMs = 214_999L)).reason,
        )
        val restart = policy.observe(missing.copy(nowMs = 215_000L))
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.RESTART_STARTER_CHAIN, restart.action)
        assertEquals(30_000L, restart.retryDelayMs)
        val retry = policy.observe(missing.copy(nowMs = 245_000L))
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.RESTART_STARTER_CHAIN, retry.action)
        assertEquals(3, retry.attempt)
        assertEquals(60_000L, retry.retryDelayMs)
        var last = retry
        repeat(8) { index ->
            last = policy.observe(missing.copy(nowMs = missing.nowMs + 245_000L + index * 600_000L))
        }
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.RESTART_STARTER_CHAIN, last.action)
        assertEquals(BetaStartupFailureRecoveryPolicy.MAX_RETRY_DELAY_MS, last.retryDelayMs)
    }

    @Test
    fun `empty current session Power log ages into dialog recovery but unbound live process does not`() {
        val policy = BetaStartupFailureRecoveryPolicy(noProgressTimeoutMs = 180_000L)
        val dialog = dialog(firstSeenAtMs = 190_000L)
        val currentZeroLength = snapshot(
            nowMs = 200_000L,
            stalledForMs = 10_000L,
            powerLogIsCurrentSession = true,
            powerLogLength = 0L,
            powerLogAgeMs = 179_999L,
            dialog = dialog,
        )
        assertEquals(
            BetaStartupFailureRecoveryPolicy.Action.RESTART_CLIENT,
            policy.observe(currentZeroLength).action,
        )
        assertEquals(
            BetaStartupFailureRecoveryPolicy.Action.WAIT,
            policy.observe(currentZeroLength.copy(nowMs = 200_001L, powerLogAgeMs = 180_000L)).action,
        )

        val otherPolicy = BetaStartupFailureRecoveryPolicy(noProgressTimeoutMs = 0L)
        val unboundButAlive = snapshot(dialog = null, powerLogIsCurrentSession = false, powerLogLength = 0L)
        val noEvidence = otherPolicy.observe(unboundButAlive)
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.WAIT, noEvidence.action)
        assertEquals("no-positive-startup-failure-evidence", noEvidence.reason)
    }

    @Test
    fun `terminal and live match states take priority over modal or missing process`() {
        val policy = BetaStartupFailureRecoveryPolicy(noProgressTimeoutMs = 0L)
        val evidence = snapshot(
            processAlive = false,
            currentPid = null,
            gameWindowMatchesPid = false,
            dialog = dialog(firstSeenAtMs = 10_000L),
        )
        assertEquals("terminal-state-priority", policy.observe(evidence.copy(terminalState = true)).reason)
        assertEquals("authoritative-live-match-priority", policy.observe(evidence.copy(liveMatch = true)).reason)
        assertEquals("not-working-or-paused", policy.observe(evidence.copy(paused = true)).reason)
        assertEquals(
            "terminal-state-priority",
            policy.observe(evidence.copy(terminalState = true, dialog = null, liveMatch = false)).reason,
        )
    }

    @Test
    fun `confirmed exact dialog overrides stale live phase but stale dialog does not`() {
        val policy = BetaStartupFailureRecoveryPolicy(noProgressTimeoutMs = 180_000L)
        val currentDialogWithStaleLivePhase = policy.observe(
            snapshot(
                stalledForMs = 35_000L,
                liveMatch = true,
                dialog = dialog(firstSeenAtMs = 196_000L),
            ),
        )
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.RESTART_CLIENT, currentDialogWithStaleLivePhase.action)
        assertEquals(100L, currentDialogWithStaleLivePhase.currentApplicationErrorDialogHwnd)

        val staleDialog = BetaStartupFailureRecoveryPolicy(noProgressTimeoutMs = 0L).observe(
            snapshot(
                liveMatch = true,
                processStartedAtMs = 5_000L,
                dialog = dialog(firstSeenAtMs = 4_999L),
            ),
        )
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.WAIT, staleDialog.action)
        assertEquals("stale-or-unattributed-error-dialog", staleDialog.reason)
    }

    @Test
    fun `startup handoff deferral does not mask exact application error dialog`() {
        assertTrue(BetaScreenRecoveryService.shouldDeferStartupFailureRecovery(true, false))
        assertEquals(false, BetaScreenRecoveryService.shouldDeferStartupFailureRecovery(true, true))
    }

    @Test
    fun `recent current nonempty Power log or known screen resets recovery budget`() {
        val policy = BetaStartupFailureRecoveryPolicy(noProgressTimeoutMs = 0L)
        val evidence = snapshot(dialog = dialog(firstSeenAtMs = 10_000L))
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.RESTART_CLIENT, policy.observe(evidence).action)
        assertEquals(
            "authoritative-progress-or-usable-screen",
            policy.observe(evidence.copy(
                dialog = null,
                powerLogIsCurrentSession = true,
                powerLogLength = 1L,
                powerLogAgeMs = 1_000L,
            )).reason,
        )
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.RESTART_CLIENT, policy.observe(evidence).action)
    }

    @Test
    fun `policy action from exact dialog reaches client restart dispatch before startup timeout`() {
        val policy = BetaStartupFailureRecoveryPolicy(noProgressTimeoutMs = 180_000L)
        val decision = policy.observe(
            snapshot(
                nowMs = 50_000L,
                stalledForMs = 35_000L,
                powerLogIsCurrentSession = true,
                powerLogLength = 459_108L,
                powerLogAgeMs = 2_000L,
                liveMatch = true,
                dialog = dialog(firstSeenAtMs = 46_000L),
            ),
        )
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.RESTART_CLIENT, decision.action)
        assertEquals(
            false,
            BetaStartupFailureRecoveryDispatch.shouldBlockForGameState(
                terminalState = false,
                liveMatch = true,
                currentApplicationErrorDialogConfirmed = decision.currentApplicationErrorDialogHwnd == 100L,
            ),
        )
        assertEquals(
            true,
            BetaStartupFailureRecoveryDispatch.shouldBlockForGameState(
                terminalState = false,
                liveMatch = true,
                currentApplicationErrorDialogConfirmed = false,
            ),
            "dispatch must retain the stale-match guard when its current-dialog recheck fails",
        )
        assertEquals(
            true,
            BetaStartupFailureRecoveryDispatch.shouldBlockForGameState(
                terminalState = true,
                liveMatch = true,
                currentApplicationErrorDialogConfirmed = true,
            ),
            "authoritative result state must still win over a dialog",
        )
        var restartedPid: Long? = null
        val result = BetaStartupFailureRecoveryDispatch.dispatch(
            action = decision.action,
            rebindWindow = { false },
            restartClient = { restartedPid = 20L },
            restartStarterChain = {},
        )
        assertEquals(BetaStartupFailureRecoveryDispatch.Result.CLIENT_RESTARTED, result)
        assertEquals(20L, restartedPid)
    }

    @Test
    fun `stale nonempty Power log is not treated as ongoing progress`() {
        val policy = BetaStartupFailureRecoveryPolicy(noProgressTimeoutMs = 0L)
        val staleLogWithError = snapshot(
            dialog = dialog(firstSeenAtMs = 10_000L),
            powerLogIsCurrentSession = true,
            powerLogLength = 4096L,
            powerLogAgeMs = BetaStartupFailureRecoveryPolicy.MAX_POWER_LOG_PROGRESS_AGE_MS + 1L,
        )
        assertEquals(BetaStartupFailureRecoveryPolicy.Action.RESTART_CLIENT, policy.observe(staleLogWithError).action)
    }

    @Test
    fun `dispatch invokes the selected restart path and never pauses Beta`() {
        var rebound = 0
        var clientRestarts = 0
        var starterRestarts = 0

        val rebindResult = BetaStartupFailureRecoveryDispatch.dispatch(
            action = BetaStartupFailureRecoveryPolicy.Action.REBIND_WINDOW,
            rebindWindow = { rebound++; true },
            restartClient = { clientRestarts++ },
            restartStarterChain = { starterRestarts++ },
        )
        val restartResult = BetaStartupFailureRecoveryDispatch.dispatch(
            action = BetaStartupFailureRecoveryPolicy.Action.RESTART_STARTER_CHAIN,
            rebindWindow = { rebound++; true },
            restartClient = { clientRestarts++ },
            restartStarterChain = { starterRestarts++ },
        )
        val clientResult = BetaStartupFailureRecoveryDispatch.dispatch(
            action = BetaStartupFailureRecoveryPolicy.Action.RESTART_CLIENT,
            rebindWindow = { rebound++; true },
            restartClient = { clientRestarts++ },
            restartStarterChain = { starterRestarts++ },
        )

        assertEquals(BetaStartupFailureRecoveryDispatch.Result.WINDOW_REBOUND, rebindResult)
        assertEquals(BetaStartupFailureRecoveryDispatch.Result.STARTER_CHAIN_RESTARTED, restartResult)
        assertEquals(BetaStartupFailureRecoveryDispatch.Result.CLIENT_RESTARTED, clientResult)
        assertEquals(1, rebound)
        assertEquals(1, clientRestarts)
        assertEquals(1, starterRestarts)
    }

    private fun candidate() = WindowsApplicationErrorDialogProbe.Candidate(
        hwnd = 100L,
        className = "#32770",
        title = "炉石传说: Hearthstone.exe - Application Error",
        body = "The exception Breakpoint (0x80000003) occurred in the application",
        hostPid = 1_536L,
        ownerPid = null,
    )

    private fun dialog(firstSeenAtMs: Long) = BetaStartupFailureRecoveryPolicy.DialogEvidence(
        hwnd = 100L,
        title = "炉石传说: Hearthstone.exe - Application Error",
        body = "The exception Breakpoint (0x80000003)",
        hostPid = 1_536L,
        ownerPid = null,
        firstSeenAtMs = firstSeenAtMs,
    )

    private fun snapshot(
        nowMs: Long = 200_000L,
        stalledForMs: Long = 180_000L,
        working: Boolean = true,
        paused: Boolean = false,
        terminalState: Boolean = false,
        liveMatch: Boolean = false,
        processAlive: Boolean = true,
        currentPid: Long? = 20L,
        processStartedAtMs: Long? = 5_000L,
        gameWindowMatchesPid: Boolean = true,
        powerLogIsCurrentSession: Boolean = false,
        powerLogLength: Long = 0L,
        powerLogAgeMs: Long = 0L,
        knownUsableScreen: Boolean = false,
        dialog: BetaStartupFailureRecoveryPolicy.DialogEvidence? = null,
    ) = BetaStartupFailureRecoveryPolicy.Snapshot(
        nowMs = nowMs,
        stalledForMs = stalledForMs,
        working = working,
        paused = paused,
        terminalState = terminalState,
        liveMatch = liveMatch,
        processAlive = processAlive,
        currentPid = currentPid,
        processStartedAtMs = processStartedAtMs,
        gameWindowMatchesPid = gameWindowMatchesPid,
        powerLogIsCurrentSession = powerLogIsCurrentSession,
        powerLogLength = powerLogLength,
        powerLogAgeMs = powerLogAgeMs,
        knownUsableScreen = knownUsableScreen,
        dialog = dialog,
    )
}
