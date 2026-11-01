package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard
import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscriptbase.enums.ModeEnum

/**
 * The last, process-wide gate before an action can reach the desktop.  Phase
 * checks are useful for deciding what to do, but they are not sufficient when
 * a queued worker races F2.  Every central mouse/surrender path calls this
 * gate immediately before dispatch and again after acquiring its input lock.
 */
object ActionDispatchGate {
    private val terminalCleanupBlockLogThrottle = TerminalCleanupBlockLogThrottle()
    private val startupQuestOverlayDispatchScope = ThreadLocal<Boolean>()
    private val seasonRankAwardDispatchScope = ThreadLocal<String>()
    private val seasonRankAwardDispatchScreenMode = ThreadLocal<String>()

    internal fun isSeasonRankAwardContinueDispatchScoped(): Boolean =
        seasonRankAwardDispatchScope.get() == SeasonRankAwardRecoveryPolicy.DISPATCH_ACTION

    internal fun isSeasonRankAwardChestOpenDispatchScoped(): Boolean =
        seasonRankAwardDispatchScope.get() == SeasonRankAwardRecoveryPolicy.OPEN_CHEST_ACTION

    internal fun isSeasonRankAwardRewardsContinueDispatchScoped(): Boolean =
        seasonRankAwardDispatchScope.get() == SeasonRankAwardRecoveryPolicy.REWARDS_CONTINUE_ACTION

    internal fun seasonRankAwardDispatchMode(): String? = seasonRankAwardDispatchScreenMode.get()

    internal fun isStartupQuestRecoveryFenceBlocking(action: String): Boolean =
        StartupQuestOverlayRecoveryFence.isActive() &&
            startupQuestOverlayDispatchScope.get() != true &&
            !(action in SEASON_RANK_AWARD_ACTIONS && seasonRankAwardDispatchScope.get() == action)

    internal fun dispatchSeasonRankAwardContinue(verifiedScreenMode: String = "HUB", dispatch: () -> Boolean): Boolean {
        return dispatchSeasonRankAwardAction(SeasonRankAwardRecoveryPolicy.DISPATCH_ACTION, verifiedScreenMode, dispatch)
    }

    internal fun dispatchSeasonRankAwardChestOpen(dispatch: () -> Boolean): Boolean =
        dispatchSeasonRankAwardAction(SeasonRankAwardRecoveryPolicy.OPEN_CHEST_ACTION, "HUB", dispatch)

    internal fun dispatchSeasonRankAwardRewardsContinue(
        verifiedScreenMode: String = "HUB",
        dispatch: () -> Boolean,
    ): Boolean = dispatchSeasonRankAwardAction(
        SeasonRankAwardRecoveryPolicy.REWARDS_CONTINUE_ACTION, verifiedScreenMode, dispatch,
    )

    private fun dispatchSeasonRankAwardAction(action: String, verifiedScreenMode: String, dispatch: () -> Boolean): Boolean {
        if (seasonRankAwardDispatchScope.get() != null) return false
        seasonRankAwardDispatchScope.set(action)
        seasonRankAwardDispatchScreenMode.set(verifiedScreenMode)
        return try {
            dispatch()
        } finally {
            seasonRankAwardDispatchScope.remove()
            seasonRankAwardDispatchScreenMode.remove()
        }
    }

    private val SEASON_RANK_AWARD_ACTIONS = setOf(
        SeasonRankAwardRecoveryPolicy.DISPATCH_ACTION,
        SeasonRankAwardRecoveryPolicy.OPEN_CHEST_ACTION,
        SeasonRankAwardRecoveryPolicy.REWARDS_CONTINUE_ACTION,
    )

    /** Mirrors normal-input startup readiness without emitting a block log or dispatching input. */
    internal fun isStartupScreenConfirmedForAction(action: String): Boolean {
        if (StartupQuestOverlayRecoveryFence.isActive()) return false
        val currentSessionReady = runCatching { ScreenStateRecovery.isCurrentGameSessionReadyForStart() }
            .getOrDefault(false)
        val gameWindow = ScriptStatus.gameHWND
        val processId = runCatching { GameUtil.findGameProcessIdForDiagnostics()?.toInt() }.getOrNull()
        val freshOnlineMenuReady = processId != null && gameWindow != null &&
            !WarEx.inWar && !runCatching { GameUtil.isTerminalGameState() }.getOrDefault(true) &&
            runCatching { GameUtil.isVerifiedCurrentGameWindow(gameWindow) }.getOrDefault(false) &&
            StartupInputReadiness.isMenuReady(processId, gameWindow.toString(), Mode.currMode?.name)
        val exactGameWindowVerified = gameWindow != null &&
            runCatching { GameUtil.isVerifiedCurrentGameWindow(gameWindow) }.getOrDefault(false)
        val freshDeckSelectionStartReady = action == "matchmaking.start" && processId != null && gameWindow != null &&
            StartupInputAuthorizationPolicy.allowsDeckSelectionStart(
                action = action,
                processId = processId,
                hwnd = gameWindow.toString(),
                exactWindowVerified = exactGameWindowVerified,
                freshDeckSelectionStartReady = StartupInputReadiness.isDeckSelectionStartReady(
                    processId,
                    gameWindow.toString(),
                ),
            )
        val selectedModeStartPermitValid = action == "matchmaking.start" && processId != null && gameWindow != null &&
            exactGameWindowVerified && DeckStrategyManager.currentRunMode != null &&
            StartupInputReadiness.hasSelectedTournamentModeStartPermit(
                processId, gameWindow.toString(), DeckStrategyManager.currentRunMode?.name,
            )
        val startupDeckSelectionModeNavigationReady = startupDeckSelectionModeNavigationReady(
            action, processId, gameWindow?.toString(), exactGameWindowVerified, currentSessionReady,
        )
        val seasonRankAwardContinuePermitValid = seasonRankAwardContinuePermitValid(
            action, processId, gameWindow, exactGameWindowVerified, currentSessionReady,
            PauseStatus.isPause, WorkTimeListener.working,
        )
        val seasonRankAwardChestOpenPermitValid = seasonRankAwardChestOpenPermitValid(
            action, processId, gameWindow, exactGameWindowVerified, currentSessionReady,
            PauseStatus.isPause, WorkTimeListener.working,
        )
        val seasonRankAwardRewardsContinuePermitValid = seasonRankAwardRewardsContinuePermitValid(
            action, processId, gameWindow, exactGameWindowVerified, currentSessionReady,
            PauseStatus.isPause, WorkTimeListener.working,
        )
        return StartupInputAuthorizationPolicy.allows(
            action = action,
            currentSessionReady = currentSessionReady,
            freshOnlineMenuReady = freshOnlineMenuReady,
            freshDeckSelectionStartReady = freshDeckSelectionStartReady,
            selectedModeStartPermitValid = selectedModeStartPermitValid,
            startupDeckSelectionModeNavigationReady = startupDeckSelectionModeNavigationReady,
            seasonRankAwardContinuePermitValid = seasonRankAwardContinuePermitValid,
            seasonRankAwardChestOpenPermitValid = seasonRankAwardChestOpenPermitValid,
            seasonRankAwardRewardsContinuePermitValid = seasonRankAwardRewardsContinuePermitValid,
        )
    }

    /** Recovery-specific readiness: generic session/menu proof cannot stand in for configured-mode deck proof. */
    internal fun isFreshConfiguredDeckSelectionStartProofReady(): Boolean {
        val gameWindow = ScriptStatus.gameHWND ?: return false
        val processId = runCatching { GameUtil.findGameProcessIdForDiagnostics()?.toInt() }.getOrNull()
            ?: return false
        return !WarEx.inWar &&
            !runCatching { GameUtil.isTerminalGameState() }.getOrDefault(true) &&
            runCatching { GameUtil.isVerifiedCurrentGameWindow(gameWindow) }.getOrDefault(false) &&
            StartupInputReadiness.isDeckSelectionStartReady(processId, gameWindow.toString())
    }

    private fun startupDeckSelectionModeNavigationReady(
        action: String,
        processId: Int?,
        hwnd: String?,
        exactWindowVerified: Boolean,
        currentSessionReady: Boolean,
    ): Boolean {
        val activeGame = currentSessionReady || WarEx.inWar ||
            Mode.currMode == ModeEnum.GAMEPLAY || Mode.nextMode == ModeEnum.GAMEPLAY
        val activeMatchmaking = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
            LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard()
        val terminal = MandatoryRankSurrenderGuard.isPending() ||
            MandatoryRankSurrenderGuard.isTerminalCleanupPending() ||
            PowerLogListener.hasCurrentGameCompleteTerminalPowerLogEvidence()
        val expectedCorrectionMode = if (action in setOf(
                StartupDeckModeNavigationPolicy.OPEN_SELECTOR_ACTION,
                StartupDeckModeNavigationPolicy.SELECT_WILD_ACTION,
            )
        ) "WILD" else null
        val modeNavigationProofFresh = when (action) {
            StartupDeckModeNavigationPolicy.SELECT_WILD_ACTION -> processId != null && !hwnd.isNullOrBlank() &&
                StartupInputReadiness.isWildModeSelectorControlReady(processId, hwnd)
            else -> processId != null && !hwnd.isNullOrBlank() &&
                StartupInputReadiness.isDeckSelectionModeNavigationReady(
                    processId, hwnd, expectedMode = expectedCorrectionMode,
                )
        }
        return processId != null && !hwnd.isNullOrBlank() &&
            StartupDeckModeNavigationPolicy.allowsDispatch(
                action = action,
                proofFresh = modeNavigationProofFresh,
                exactWindowVerified = exactWindowVerified,
                currentSessionReady = currentSessionReady,
                activeGame = activeGame,
                activeMatchmaking = activeMatchmaking,
                terminal = terminal,
            )
    }

    fun allow(
        action: String,
        recoveryCapability: MandatoryRankSurrenderGuard.RecoveryCapability? = null,
        terminalCleanupCapability: MandatoryRankSurrenderGuard.TerminalCleanupCapability? = null,
        rankSurrenderCapability: MulliganRankDispatchBarrier.SurrenderCapability? = null,
    ): Boolean {
        if (isStartupQuestRecoveryFenceBlocking(action)) {
            log.warn {
                "ACTION_BLOCKED action=$action reason=startup-quest-overlay-unresolved " +
                    "pause=${PauseStatus.isPause} working=${WorkTimeListener.working} dispatch=false"
            }
            return false
        }
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
        val exactGameWindowVerified = gameWindow != null &&
            runCatching { GameUtil.isVerifiedCurrentGameWindow(gameWindow) }.getOrDefault(false)
        val freshDeckSelectionStartReady = StartupInputAuthorizationPolicy.allowsDeckSelectionStart(
            action = action,
            processId = processId,
            hwnd = gameWindow?.toString(),
            exactWindowVerified = exactGameWindowVerified,
            freshDeckSelectionStartReady = processId != null && gameWindow != null &&
                StartupInputReadiness.isDeckSelectionStartReady(processId, gameWindow.toString()),
        )
        val startupTraditionalBattleSelectionReady = StartupInputAuthorizationPolicy
            .allowsHubTraditionalBattleSelection(
                action = action,
                processId = processId,
                hwnd = gameWindow?.toString(),
                exactWindowVerified = exactGameWindowVerified,
                freshHubChooserReady = processId != null && gameWindow != null &&
                    StartupInputReadiness.isHubTraditionalBattleSelectionReady(
                        processId,
                        gameWindow.toString(),
                        Mode.currMode?.name,
                    ),
                currentSessionReady = currentSessionReady,
                activeGame = WarEx.inWar || Mode.currMode == ModeEnum.GAMEPLAY || Mode.nextMode == ModeEnum.GAMEPLAY,
                activeMatchmaking = LifecycleTrace.hasRecentActiveMatchmakingScreen() ||
                    LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard(),
                terminal = GameUtil.isTerminalGameState() || MandatoryRankSurrenderGuard.isPending() ||
                    MandatoryRankSurrenderGuard.isTerminalCleanupPending(),
            )
        val startupDeckSelectionModeNavigationReady = startupDeckSelectionModeNavigationReady(
            action, processId, gameWindow?.toString(), exactGameWindowVerified, currentSessionReady,
        )
        val seasonRankAwardContinuePermitValid = seasonRankAwardContinuePermitValid(
            action, processId, gameWindow, exactGameWindowVerified, currentSessionReady, paused, working,
        )
        val seasonRankAwardChestOpenPermitValid = seasonRankAwardChestOpenPermitValid(
            action, processId, gameWindow, exactGameWindowVerified, currentSessionReady, paused, working,
        )
        val seasonRankAwardRewardsContinuePermitValid = seasonRankAwardRewardsContinuePermitValid(
            action, processId, gameWindow, exactGameWindowVerified, currentSessionReady, paused, working,
        )
        val selectedModeStartPermitValid = action == "matchmaking.start" && processId != null && gameWindow != null &&
            exactGameWindowVerified && DeckStrategyManager.currentRunMode != null &&
            StartupInputReadiness.hasSelectedTournamentModeStartPermit(
                processId, gameWindow.toString(), DeckStrategyManager.currentRunMode?.name,
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
            betaResultConfirmationPermitValid = action == BetaStartupResultConfirmationPolicy.DISPATCH_ACTION &&
                processId != null && gameWindow != null &&
                StartupInputReadiness.hasBetaResultConfirmationPermit(
                    processId,
                    gameWindow.toString(),
                    System.currentTimeMillis(),
                ),
            startupQuestOverlayDismissPermitValid = action == StartupQuestOverlayDismissPolicy.DISPATCH_ACTION &&
                startupQuestOverlayDispatchScope.get() == true,
            freshDeckSelectionStartReady = freshDeckSelectionStartReady,
            selectedModeStartPermitValid = selectedModeStartPermitValid,
            startupDeckSelectionModeNavigationReady = startupDeckSelectionModeNavigationReady,
            seasonRankAwardContinuePermitValid = seasonRankAwardContinuePermitValid,
            seasonRankAwardChestOpenPermitValid = seasonRankAwardChestOpenPermitValid,
            seasonRankAwardRewardsContinuePermitValid = seasonRankAwardRewardsContinuePermitValid,
        )
        if (!startupGateAllowed) {
            log.warn {
                "ACTION_BLOCKED action=$action reason=startup-screen-not-confirmed " +
                    "currentSessionReady=$currentSessionReady freshOnlineMenuReady=$freshOnlineMenuReady " +
                    "freshDeckSelectionStartReady=$freshDeckSelectionStartReady " +
                    "selectedModeStartPermitValid=$selectedModeStartPermitValid " +
                    "modeNavigationPermitValid=$startupDeckSelectionModeNavigationReady " +
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
            betaResultConfirmationPermitValid = action == BetaStartupResultConfirmationPolicy.DISPATCH_ACTION &&
                processId != null && gameWindow != null &&
                StartupInputReadiness.hasBetaResultConfirmationPermit(
                    processId,
                    gameWindow.toString(),
                    System.currentTimeMillis(),
                ),
            startupQuestOverlayDismissPermitValid = action == StartupQuestOverlayDismissPolicy.DISPATCH_ACTION &&
                startupQuestOverlayDispatchScope.get() == true,
            freshDeckSelectionStartReady = freshDeckSelectionStartReady,
            startupDeckSelectionModeNavigationPermitValid = startupDeckSelectionModeNavigationReady,
            seasonRankAwardContinuePermitValid = seasonRankAwardContinuePermitValid,
            seasonRankAwardChestOpenPermitValid = seasonRankAwardChestOpenPermitValid,
            seasonRankAwardRewardsContinuePermitValid = seasonRankAwardRewardsContinuePermitValid,
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

    internal fun startupQuestOverlayPermitValid(
        permit: StartupQuestOverlayDismissPermitStore.Permit?,
        pid: Long?,
        window: String?,
        point: java.awt.Point,
        nowMs: Long,
        consume: Boolean,
    ): Boolean {
        val valid = if (consume) {
            StartupQuestOverlayDismissAuthorization.permits.consume(permit, pid, window, point, nowMs)
        } else {
            StartupQuestOverlayDismissAuthorization.permits.isValid(permit, pid, window, point, nowMs)
        }
        if (!valid) {
            log.warn {
                "ACTION_BLOCKED action=recovery.startup-quest-overlay.top-left " +
                    "reason=no-valid-one-use-permit pid=${pid ?: "none"} window=${window ?: "none"} " +
                    "point=(${point.x},${point.y}) dispatch=false"
            }
        }
        return valid
    }

    internal fun dispatchStartupQuestOverlayDismiss(
        permit: StartupQuestOverlayDismissPermitStore.Permit?,
        pid: Long?,
        window: String?,
        point: java.awt.Point,
        nowMs: Long,
        dispatch: () -> Boolean,
    ): Boolean {
        if (!StartupQuestOverlayRecoveryFence.isActive() ||
            !startupQuestOverlayPermitValid(permit, pid, window, point, nowMs, consume = true)
        ) return false
        startupQuestOverlayDispatchScope.set(true)
        return try {
            dispatch()
        } finally {
            startupQuestOverlayDispatchScope.remove()
        }
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
        betaResultConfirmationPermitValid: Boolean = false,
        startupQuestOverlayDismissPermitValid: Boolean = false,
        freshDeckSelectionStartReady: Boolean = false,
        selectedModeStartPermitValid: Boolean = false,
        startupDeckSelectionModeNavigationPermitValid: Boolean = false,
        seasonRankAwardContinuePermitValid: Boolean = false,
        seasonRankAwardChestOpenPermitValid: Boolean = false,
        seasonRankAwardRewardsContinuePermitValid: Boolean = false,
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
                betaResultConfirmationPermitValid = betaResultConfirmationPermitValid,
                startupQuestOverlayDismissPermitValid = startupQuestOverlayDismissPermitValid,
                freshDeckSelectionStartReady = freshDeckSelectionStartReady,
                selectedModeStartPermitValid = selectedModeStartPermitValid,
                startupDeckSelectionModeNavigationReady = startupDeckSelectionModeNavigationPermitValid,
                seasonRankAwardContinuePermitValid = seasonRankAwardContinuePermitValid,
                seasonRankAwardChestOpenPermitValid = seasonRankAwardChestOpenPermitValid,
                seasonRankAwardRewardsContinuePermitValid = seasonRankAwardRewardsContinuePermitValid,
            )
        ) {
            log.warn {
                "ACTION_BLOCKED action=$action reason=startup-screen-not-confirmed " +
                    "currentSessionReady=$currentSessionReady freshOnlineMenuReady=$freshOnlineMenuReady " +
                    "freshDeckSelectionStartReady=$freshDeckSelectionStartReady " +
                    "selectedModeStartPermitValid=$selectedModeStartPermitValid " +
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
            action == StartupInputReadiness.HUB_TRADITIONAL_BATTLE_ACTION ||
            action in SEASON_RANK_AWARD_ACTIONS

    private fun seasonRankAwardContinuePermitValid(
        action: String,
        processId: Int?,
        gameWindow: com.sun.jna.platform.win32.WinDef.HWND?,
        exactWindowVerified: Boolean,
        currentSessionReady: Boolean,
        paused: Boolean,
        working: Boolean,
    ): Boolean = action == SeasonRankAwardRecoveryPolicy.DISPATCH_ACTION &&
        isSeasonRankAwardContinueDispatchScoped() && processId != null && processId > 0 && gameWindow != null &&
        exactWindowVerified && SeasonRankAwardRecoveryPolicy.allowsMenuContext(
            seasonRankAwardDispatchMode(), currentSessionReady,
        ) && !WarEx.inWar && Mode.currMode != ModeEnum.GAMEPLAY && Mode.nextMode != ModeEnum.GAMEPLAY &&
        !GameUtil.isTerminalGameState() &&
        !LifecycleTrace.hasRecentActiveMatchmakingScreen() &&
        !LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard() &&
        !MandatoryRankSurrenderGuard.isPending() && !MandatoryRankSurrenderGuard.isTerminalCleanupPending() &&
        !paused && working

    private fun seasonRankAwardChestOpenPermitValid(
        action: String,
        processId: Int?,
        gameWindow: com.sun.jna.platform.win32.WinDef.HWND?,
        exactWindowVerified: Boolean,
        currentSessionReady: Boolean,
        paused: Boolean,
        working: Boolean,
    ): Boolean = action == SeasonRankAwardRecoveryPolicy.OPEN_CHEST_ACTION &&
        isSeasonRankAwardChestOpenDispatchScoped() && processId != null && processId > 0 && gameWindow != null &&
        exactWindowVerified && SeasonRankAwardRecoveryPolicy.allowsMenuContext(
            seasonRankAwardDispatchMode(), currentSessionReady,
        ) && !WarEx.inWar && Mode.currMode != ModeEnum.GAMEPLAY && Mode.nextMode != ModeEnum.GAMEPLAY &&
        !GameUtil.isTerminalGameState() && !LifecycleTrace.hasRecentActiveMatchmakingScreen() &&
        !LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard() && !MandatoryRankSurrenderGuard.isPending() &&
        !MandatoryRankSurrenderGuard.isTerminalCleanupPending() && !paused && working

    private fun seasonRankAwardRewardsContinuePermitValid(
        action: String,
        processId: Int?,
        gameWindow: com.sun.jna.platform.win32.WinDef.HWND?,
        exactWindowVerified: Boolean,
        currentSessionReady: Boolean,
        paused: Boolean,
        working: Boolean,
    ): Boolean = action == SeasonRankAwardRecoveryPolicy.REWARDS_CONTINUE_ACTION &&
        isSeasonRankAwardRewardsContinueDispatchScoped() && processId != null && processId > 0 && gameWindow != null &&
        exactWindowVerified && SeasonRankAwardRecoveryPolicy.allowsMenuContext(
            seasonRankAwardDispatchMode(), currentSessionReady,
        ) && !WarEx.inWar && Mode.currMode != ModeEnum.GAMEPLAY && Mode.nextMode != ModeEnum.GAMEPLAY &&
        !GameUtil.isTerminalGameState() && !LifecycleTrace.hasRecentActiveMatchmakingScreen() &&
        !LifecycleTrace.hasActiveMatchmakingEntryRecoveryGuard() && !MandatoryRankSurrenderGuard.isPending() &&
        !MandatoryRankSurrenderGuard.isTerminalCleanupPending() && !paused && working

    internal fun allowedForState(paused: Boolean, working: Boolean): Boolean = !paused && working
}
