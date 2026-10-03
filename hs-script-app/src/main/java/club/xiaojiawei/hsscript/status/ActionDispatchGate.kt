package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import club.xiaojiawei.hsscriptbase.config.log

/**
 * The last, process-wide gate before an action can reach the desktop.  Phase
 * checks are useful for deciding what to do, but they are not sufficient when
 * a queued worker races F2.  Every central mouse/surrender path calls this
 * gate immediately before dispatch and again after acquiring its input lock.
 */
object ActionDispatchGate {

    fun allow(
        action: String,
        recoveryCapability: MandatoryRankSurrenderGuard.RecoveryCapability? = null,
        terminalCleanupCapability: MandatoryRankSurrenderGuard.TerminalCleanupCapability? = null,
        rankSurrenderCapability: MulliganRankDispatchBarrier.SurrenderCapability? = null,
    ): Boolean {
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

    internal fun allowForState(
        action: String,
        paused: Boolean,
        working: Boolean,
        mandatoryRankSurrenderPending: Boolean = false,
        recoveryCapabilityValid: Boolean = false,
        terminalCleanupCapabilityValid: Boolean = false,
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
