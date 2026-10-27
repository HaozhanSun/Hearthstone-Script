package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscript.utils.GameUtil

/**
 * The last, process-wide gate before an action can reach the desktop.  Phase
 * checks are useful for deciding what to do, but they are not sufficient when
 * a queued worker races F2.  Every central mouse/surrender path calls this
 * gate immediately before dispatch and again after acquiring its input lock.
 */
object ActionDispatchGate {
    private val terminalCleanupBlockLogThrottle = TerminalCleanupBlockLogThrottle()

    fun allow(
        action: String,
        recoveryCapability: MandatoryRankSurrenderGuard.RecoveryCapability? = null,
        terminalCleanupCapability: MandatoryRankSurrenderGuard.TerminalCleanupCapability? = null,
        rankSurrenderCapability: MulliganRankDispatchBarrier.SurrenderCapability? = null,
    ): Boolean {
        if (action.startsWith("startup.")) {
            val gameAlive = runCatching { GameUtil.isAliveOfGame() }.getOrDefault(true)
            val acceptedProof = !MandatoryRankSurrenderGuard.isPending() &&
                MandatoryRankSurrenderGuard.isTerminalCleanupPending()
            if (reconcileMissingGameTerminalCleanupFence(gameAlive, acceptedProof)) {
                log.warn { "MISSING_GAME_TERMINAL_CLEANUP_FENCE_RELEASED proof=accepted-terminal gameAlive=false action=$action" }
            }
        }
        val paused = PauseStatus.isPause
        val working = WorkTimeListener.working
        val mandatoryRankSurrenderPending = MandatoryRankSurrenderGuard.isPending()
        val allowed = allowForState(
            action = action,
            paused = paused,
            working = working,
            mandatoryRankSurrenderPending = mandatoryRankSurrenderPending,
            recoveryCapabilityValid = MandatoryRankSurrenderGuard.isRecoveryCapabilityValid(recoveryCapability),
            terminalCleanupCapabilityValid =
                MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(terminalCleanupCapability),
            terminalCleanupPending = MandatoryRankSurrenderGuard.isTerminalCleanupPending(),
            rankBarrierState = MulliganRankDispatchBarrier.currentState(),
            rankSurrenderRequestCapabilityValid = action == "surrender.request" &&
                MulliganRankDispatchBarrier.isSurrenderCapabilityValid(rankSurrenderCapability),
        )
        if (!allowed) return false
        if (action == "surrender.request" && rankSurrenderCapability != null &&
            !MulliganRankDispatchBarrier.consumeSurrenderCapability(rankSurrenderCapability)
        ) {
            log.warn {
                "ACTION_BLOCKED action=$action reason=rank-surrender-capability-consumed dispatch=false"
            }
            return false
        }
        return true
    }

    internal fun reconcileMissingGameTerminalCleanupFence(gameAlive: Boolean, acceptedTerminalProof: Boolean): Boolean =
        MandatoryRankSurrenderGuard.reconcileMissingProcessAfterTerminalProof(
            completeCurrentGameTerminalProof = acceptedTerminalProof,
            gameProcessAlive = gameAlive,
        )

    internal fun allowForState(
        action: String,
        paused: Boolean,
        working: Boolean,
        mandatoryRankSurrenderPending: Boolean = false,
        recoveryCapabilityValid: Boolean = false,
        terminalCleanupCapabilityValid: Boolean = false,
        terminalCleanupPending: Boolean = false,
        rankBarrierState: MulliganRankDispatchBarrier.State = MulliganRankDispatchBarrier.State.IDLE,
        rankSurrenderRequestCapabilityValid: Boolean = false,
    ): Boolean {
        if (paused || !working) {
            log.warn {
                "ACTION_BLOCKED action=$action reason=${if (paused) "paused" else "not-working"} " +
                    "pause=$paused working=$working dispatch=false"
            }
            return false
        }
        val terminalCleanupAllowed = action == "terminal-result.dismiss" && terminalCleanupCapabilityValid
        if (terminalCleanupPending && !terminalCleanupAllowed) {
            terminalCleanupBlockLogThrottle.onBlocked(action)?.let { suppressed ->
                log.warn {
                    "ACTION_BLOCKED action=$action reason=terminal-result-cleanup-pending " +
                        "pause=false working=true dispatch=false" +
                        if (suppressed > 0) " repeatedSuppressed=$suppressed" else ""
                }
            }
            return false
        }
        terminalCleanupBlockLogThrottle.onAllowed(action)
        val rankSurrenderRecoveryAllowed = rankBarrierState == MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED &&
            recoveryCapabilityValid
        val rankSurrenderRequestAllowed = rankBarrierState == MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED &&
            action == "surrender.request" && rankSurrenderRequestCapabilityValid
        if (rankBarrierState == MulliganRankDispatchBarrier.State.PENDING ||
            rankBarrierState == MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED &&
            !rankSurrenderRecoveryAllowed && !rankSurrenderRequestAllowed && !terminalCleanupAllowed
        ) {
            log.warn {
                "ACTION_BLOCKED action=$action reason=mulligan-rank-preflight-${rankBarrierState.name.lowercase()} " +
                    "pause=false working=true dispatch=false"
            }
            return false
        }
        if (mandatoryRankSurrenderPending && !recoveryCapabilityValid &&
            !rankSurrenderRequestAllowed && !terminalCleanupAllowed
        ) {
            log.warn {
                "ACTION_BLOCKED action=$action reason=mandatory-rank-surrender-pending " +
                    "pause=false working=true dispatch=false"
            }
            return false
        }
        return true
    }

    internal fun allowedForState(paused: Boolean, working: Boolean): Boolean = !paused && working
}
