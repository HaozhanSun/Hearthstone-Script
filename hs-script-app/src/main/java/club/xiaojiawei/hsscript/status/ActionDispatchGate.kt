package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.listener.log.PowerLogListener

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
            // The cleanup capability is minted only after complete terminal
            // proof was accepted. Once the client process is gone, its session
            // log may no longer be bindable and its inWar/screen telemetry is
            // stale; retain a pending rank surrender as a hard fence.
            val acceptedCleanupProof = !MandatoryRankSurrenderGuard.isPending() &&
                MandatoryRankSurrenderGuard.isTerminalCleanupPending()
            val activeMatchmaking = gameAlive && LifecycleTrace.hasRecentActiveMatchmakingScreen()
            val currentPowerLogProof = gameAlive && PowerLogListener.hasCurrentGameCompleteTerminalPowerLogEvidence()
            val terminalProof = currentPowerLogProof || (!gameAlive && acceptedCleanupProof)
            if (reconcileStartupMissingGameTerminalCleanupFence(gameAlive, acceptedCleanupProof) ||
                reconcileMissingGameTerminalCleanupFence(gameAlive, WarEx.inWar, activeMatchmaking, terminalProof)
            ) {
                log.warn {
                    "MISSING_GAME_TERMINAL_CLEANUP_FENCE_RELEASED " +
                        "proof=${if (acceptedCleanupProof && !currentPowerLogProof) "previously-accepted-current-game-terminal" else "current-powerlog-terminal"} " +
                        "gameAlive=false inWar=${WarEx.inWar} activeMatchmaking=$activeMatchmaking " +
                        "powerLogProof=$currentPowerLogProof pause=${PauseStatus.isPause} dispatchAction=$action"
                }
            }
        }
        val paused = PauseStatus.isPause
        val working = WorkTimeListener.working
        val mandatoryRankSurrenderPending = MandatoryRankSurrenderGuard.isPending()
        val currentSessionReady = runCatching { ScreenStateRecovery.isCurrentGameSessionReadyForStart() }
            .getOrDefault(false)
        val gameWindow = ScriptStatus.gameHWND
        val processId = runCatching { GameUtil.findGameProcessIdForDiagnostics()?.toInt() }.getOrNull()
        val freshOnlineMenuReady = processId != null && gameWindow != null &&
            !WarEx.inWar && !runCatching { GameUtil.isTerminalGameState() }.getOrDefault(true) &&
            runCatching { GameUtil.isVerifiedCurrentGameWindow(gameWindow) }.getOrDefault(false) &&
            StartupInputReadiness.isMenuReady(
                processId,
                gameWindow.toString(),
                Mode.currMode?.name,
            )
        val startupTraditionalBattleSelectionReady = action == StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION &&
            processId != null && gameWindow != null && !WarEx.inWar &&
            !runCatching { GameUtil.isTerminalGameState() }.getOrDefault(true) &&
            runCatching { GameUtil.isVerifiedCurrentGameWindow(gameWindow) }.getOrDefault(false) &&
            StartupInputReadiness.isHubTraditionalBattleSelectionReady(
                processId,
                gameWindow.toString(),
                Mode.currMode?.name,
            )
        val startupGateAllowed = StartupInputAuthorizationPolicy.allows(
            action = action,
            currentSessionReady = currentSessionReady,
            freshOnlineMenuReady = freshOnlineMenuReady,
            reconnectPermitValid = StartupInputReadiness.hasReconnectPermit(),
            terminalCleanupCapabilityValid =
                MandatoryRankSurrenderGuard.isTerminalCleanupCapabilityValid(terminalCleanupCapability),
            rankSurrenderCapabilityValid = action == "surrender.request" &&
                MulliganRankDispatchBarrier.isSurrenderCapabilityValid(rankSurrenderCapability),
            startupTraditionalBattleSelectionReady = startupTraditionalBattleSelectionReady,
            startupNoticeDismissPermitValid = action == StartupNoticeRecoveryPolicy.DISPATCH_ACTION &&
                processId != null && gameWindow != null &&
                StartupInputReadiness.hasNoticeDismissPermit(
                    processId,
                    gameWindow.toString(),
                    System.currentTimeMillis(),
                ),
        )
        if (!startupGateAllowed) {
            log.warn {
                "ACTION_BLOCKED action=$action reason=startup-screen-not-confirmed " +
                    "currentSessionReady=$currentSessionReady freshOnlineMenuReady=$freshOnlineMenuReady " +
                    "dispatch=false"
            }
            return false
        }
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
            startupNoticeDismissPermitValid = action == StartupNoticeRecoveryPolicy.DISPATCH_ACTION &&
                processId != null && gameWindow != null &&
                StartupInputReadiness.hasNoticeDismissPermit(
                    processId,
                    gameWindow.toString(),
                    System.currentTimeMillis(),
                ),
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

    internal fun reconcileStartupMissingGameTerminalCleanupFence(
        gameAlive: Boolean,
        acceptedCleanupProof: Boolean,
    ): Boolean {
        if (gameAlive || !acceptedCleanupProof) return false
        return reconcileMissingGameTerminalCleanupFence(
            gameAlive = false,
            activeGame = false,
            activeMatchmaking = false,
            authoritativeTerminalProof = true,
        )
    }

    internal fun reconcileMissingGameTerminalCleanupFence(
        gameAlive: Boolean,
        activeGame: Boolean,
        activeMatchmaking: Boolean,
        authoritativeTerminalProof: Boolean,
    ): Boolean {
        val acceptedCleanupProof = !MandatoryRankSurrenderGuard.isPending() &&
            MandatoryRankSurrenderGuard.isTerminalCleanupPending()
        if (!LiveMissingGameRecoveryPolicy.shouldReleaseTerminalCleanupFence(
                terminalCleanupPending = MandatoryRankSurrenderGuard.isTerminalCleanupPending(),
                gameAlive = gameAlive,
                activeGame = activeGame && gameAlive,
                activeMatchmaking = activeMatchmaking && gameAlive,
                authoritativeTerminalProof = authoritativeTerminalProof || (!gameAlive && acceptedCleanupProof),
            )
        ) return false
        return MandatoryRankSurrenderGuard.reconcileMissingProcessAfterTerminalProof(
            completeCurrentGameTerminalProof = true,
            gameProcessAlive = false,
        )
    }

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
        currentSessionReady: Boolean = true,
        freshOnlineMenuReady: Boolean = false,
        preSessionReconnectPermitValid: Boolean = false,
        startupTraditionalBattleSelectionReady: Boolean = false,
        startupNoticeDismissPermitValid: Boolean = false,
    ): Boolean {
        if (paused || !working) {
            log.warn {
                "ACTION_BLOCKED action=$action reason=${if (paused) "paused" else "not-working"} " +
                    "pause=$paused working=$working dispatch=false"
            }
            return false
        }
        if (!StartupInputAuthorizationPolicy.allows(
                action = action,
                currentSessionReady = currentSessionReady,
                freshOnlineMenuReady = freshOnlineMenuReady,
                reconnectPermitValid = preSessionReconnectPermitValid,
                terminalCleanupCapabilityValid = terminalCleanupCapabilityValid,
                rankSurrenderCapabilityValid = rankSurrenderRequestCapabilityValid,
                startupTraditionalBattleSelectionReady = startupTraditionalBattleSelectionReady,
                startupNoticeDismissPermitValid = startupNoticeDismissPermitValid,
            )
        ) {
            log.warn {
                "ACTION_BLOCKED action=$action reason=startup-screen-not-confirmed " +
                    "currentSessionReady=$currentSessionReady freshOnlineMenuReady=$freshOnlineMenuReady " +
                    "dispatch=false"
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
        val rankPolicyApplies = !isRankIndependentAction(action)
        val rankSurrenderRecoveryAllowed = rankBarrierState == MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED &&
            recoveryCapabilityValid
        val rankSurrenderRequestAllowed = rankBarrierState == MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED &&
            action == "surrender.request" && rankSurrenderRequestCapabilityValid
        if (rankPolicyApplies && (rankBarrierState == MulliganRankDispatchBarrier.State.PENDING ||
            rankBarrierState == MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED &&
            !rankSurrenderRecoveryAllowed && !rankSurrenderRequestAllowed && !terminalCleanupAllowed)
        ) {
            log.warn {
                "ACTION_BLOCKED action=$action reason=mulligan-rank-preflight-${rankBarrierState.name.lowercase()} " +
                    "pause=false working=true dispatch=false"
            }
            return false
        }
        if (rankPolicyApplies && mandatoryRankSurrenderPending && !recoveryCapabilityValid &&
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

    /** Rank authorization is a gameplay gate; it never controls startup or queue dispatch. */
    private fun isRankIndependentAction(action: String): Boolean =
        action.startsWith("startup.") || action.startsWith("matchmaking.") || action.startsWith("queue.") ||
            action == "recovery.reconnect" ||
            action == StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION

    internal fun allowedForState(paused: Boolean, working: Boolean): Boolean = !paused && working
}
