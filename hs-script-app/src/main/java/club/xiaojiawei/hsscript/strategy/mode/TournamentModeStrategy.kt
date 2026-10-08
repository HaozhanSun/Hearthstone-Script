package club.xiaojiawei.hsscript.strategy.mode

import club.xiaojiawei.hsscript.bean.GameRect
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import club.xiaojiawei.hsscript.status.Mode
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.status.ScreenStateRecovery
import club.xiaojiawei.hsscript.status.StartupMatchmakingQueueState
import club.xiaojiawei.hsscript.status.MatchmakingQueueLifecycle
import club.xiaojiawei.hsscript.status.StrategyDefaultDeckSlotBindings
import club.xiaojiawei.hsscript.status.TournamentModeConfirmation
import club.xiaojiawei.hsscript.status.TournamentStartupActionPolicy
import club.xiaojiawei.hsscript.status.UnknownStateScreenshot
import club.xiaojiawei.hsscript.status.surrender.CurrentRankDetector
import club.xiaojiawei.hsscript.strategy.AbstractModeStrategy
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.ConfigExUtil
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.GameUtil.reconnectAction
import club.xiaojiawei.hsscript.utils.MouseUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscriptbase.bean.LRunnable
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptbase.util.RandomUtil
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 传统对战
 * @author 肖嘉威
 * @date 2022/11/25 12:39
 */
object TournamentModeStrategy : AbstractModeStrategy<Any?>() {
    private val matchmakingTraceSequence = AtomicLong()

    val START_RECT: GameRect by lazy { GameRect(0.2586, 0.3459, 0.2706, 0.3794) }

    /** 自动补全不完整套牌确认按钮（新版套牌选择页） */
    val COMPLETE_DECK_CONFIRM_RECT: GameRect by lazy {
        GameRect(-0.1200, -0.0135, 0.0530, 0.1080)
    }

    val ERROR_RECT: GameRect by lazy { GameRect(-0.0397, 0.0325, 0.0856, 0.1249) }

    val CHANGE_MODE_RECT: GameRect by lazy { GameRect(0.2868, 0.3256, -0.4672, -0.4279) }

    val WILD_MODE_RECT: GameRect by lazy { GameRect(-0.3072, -0.1755, -0.1924, -0.0647) }

    val STANDARD_MODE_RECT: GameRect by lazy { GameRect(-0.0691, 0.0612, -0.2446, -0.1097) }

    val CASUAL_MODE_RECT: GameRect by lazy { GameRect(0.1622, 0.2886, -0.1817, -0.0540) }

    @Deprecated("已被移除")
    val CLASSIC_MODE_RECT: GameRect by lazy { GameRect(-0.4278, -0.2557, -0.1769, 0.0014) }

    val TOURNAMENT_MODE_RECT: GameRect by lazy { GameRect(-0.0790, 0.0811, -0.2090, -0.1737) }

    /**
     * 顶栏有限时借用套牌时使用
     */
    val FIRST_DECK_RECT_LIMIT: GameRect by lazy { GameRect(-0.4072, -0.2516, -0.0696, 0.0139) }

    val PREV_DECK_PAGE: GameRect by lazy { GameRect(-0.4755, -0.4473, -0.0302, 0.0095) }

    val BACK_RECT: GameRect by lazy { GameRect(0.4041, 0.4575, 0.4083, 0.4410) }

    val CANCEL_RECT: GameRect by lazy { GameRect(-0.0251, 0.0530, 0.3203, 0.3802) }

    override fun wantEnter() {
        val seed = RandomUtil.rerollSeed()
        log.info { "本局共享随机种子：$seed" }
        addWantEnterTask(
            EXTRA_THREAD_POOL.scheduleWithFixedDelay(
                LRunnable {
                    log.info { "模式入口轮询：当前模式=${Mode.currMode}，暂停=${PauseStatus.isPause}" }
                    if (PauseStatus.isPause) {
                        cancelAllWantEnterTasks()
                    } else if (Mode.currMode == ModeEnum.HUB) {
                        log.info { "点击传统对战入口" }
                        TOURNAMENT_MODE_RECT.lClick()
                    } else if (Mode.currMode == ModeEnum.GAME_MODE) {
                        cancelAllWantEnterTasks()
                        BACK_RECT.lClick()
                    } else {
                        cancelAllWantEnterTasks()
                    }
                },
                randomizedModeEntryDelay(),
                randomizedModeEntryInterval(),
                TimeUnit.MILLISECONDS,
            ),
        )
    }

    override fun afterEnter(t: Any?) {
        if (WorkTimeListener.canWork()) {
            val deckStrategy = DeckStrategyManager.currentDeckStrategy
            if (deckStrategy == null) {
                SystemUtil.notice("未配置卡组策略")
                log.warn { "未配置卡组策略" }
                PauseStatus.isPause = true
                return
            }
            val runMode = DeckStrategyManager.currentRunMode
            if (runMode === RunModeEnum.STANDARD
                || runMode === RunModeEnum.WILD
                || runMode === RunModeEnum.CASUAL
                || runMode === RunModeEnum.TWIST
                || runMode === RunModeEnum.CLASSIC
            ) {
                if (!runMode.isEnable) {
                    log.warn { "${runMode.comment}未启用" }
                    PauseStatus.isPause = false
                    return
                }
                if (!PowerLogListener.checkPowerLogSize()) {
                    return
                }
                val terminalCleanupWasPending = GameUtil.hasTerminalPageCleanupFence()
                val startupScreen = ScreenStateRecovery.observeFreshTournamentStartupScreen()
                if (terminalCleanupWasPending && !GameUtil.hasTerminalPageCleanupFence() &&
                    startupScreen?.screen == "DECK_SELECTION"
                ) {
                    log.info {
                        "TOURNAMENT_STARTUP_HANDOFF screen=DECK_SELECTION " +
                            "action=RECOVER_DECK_SELECTION_WITHOUT_MODE_SWITCH"
                    }
                    recoverDeckSelectionAndStart()
                    return
                }
                if (GameUtil.hasTerminalPageCleanupFence()) {
                    log.info {
                        "TOURNAMENT_STARTUP_ACTION_DEFERRED reason=terminal-result-cleanup-pending " +
                            "screen=${startupScreen?.screen ?: "UNKNOWN"} " +
                            "action=NO_MODE_DECK_OR_MATCHMAKING_INPUT"
                    }
                    return
                }
                val startupActionAllowed = TournamentStartupActionPolicy.mayStartModeSelection(
                    screen = startupScreen?.screen,
                    confidence = startupScreen?.confidence ?: 0,
                    currentPid = GameUtil.findGameProcessIdForDiagnostics(),
                    observedPid = startupScreen?.pid,
                    working = WorkTimeListener.canWork(),
                    paused = PauseStatus.isPause,
                )
                if (!startupActionAllowed) {
                    PauseStatus.setAutomaticPause(true)
                    log.warn {
                        "TOURNAMENT_STARTUP_ACTION_BLOCKED reason=fresh-trusted-tournament-screen-required " +
                            "screen=${startupScreen?.screen ?: "UNKNOWN"} " +
                            "confidence=${startupScreen?.confidence ?: 0} " +
                            "pid=${startupScreen?.pid ?: "n/a"} " +
                            "action=NO_MODE_DECK_OR_MATCHMAKING_INPUT"
                    }
                    return
                }
                SystemUtil.delayShort()
                clickModeChangeButton()
                SystemUtil.delayShort()
                changeMode(runMode)
                SystemUtil.delayShort()
                val expectedDeckSlot = expectedDeckSlot(deckStrategy)
                if (!TournamentModeConfirmation.confirmBeforeDeckSelection(
                        expectedMode = runMode,
                        deckStrategy = deckStrategy,
                        deckSlot = expectedDeckSlot,
                        shouldContinue = {
                            !WarEx.inWar &&
                                Mode.currMode == ModeEnum.TOURNAMENT &&
                                !PauseStatus.isPause
                        },
                    )
                ) {
                    return
                }
                selectDeck(deckStrategy, expectedDeckSlot)
                SystemUtil.delayShort()
                startMatching()
            } else {
                addEnteredTask(
                    EXTRA_THREAD_POOL.scheduleWithFixedDelay(
                        LRunnable {
                            if (PauseStatus.isPause) {
                                cancelAllEnteredTasks()
                            } else if (Mode.currMode === ModeEnum.TOURNAMENT) {
                                BACK_RECT.lClick()
                            } else {
                                cancelAllEnteredTasks()
                            }
                        },
                        0,
                        200,
                        TimeUnit.MILLISECONDS,
                    ),
                )
            }
        }
    }

    private fun clickModeChangeButton() {
        log.info { "点击切换模式按钮" }
        CHANGE_MODE_RECT.lClick()
    }

    private fun changeMode(runModeEnum: RunModeEnum) {
        when (runModeEnum) {
            RunModeEnum.CLASSIC, RunModeEnum.TWIST -> changeModeToClassic()
            RunModeEnum.STANDARD -> changeModeToStandard()
            RunModeEnum.WILD -> changeModeToWild()
            RunModeEnum.CASUAL -> changeModeToCasual()
            else -> throw RuntimeException("不支持此模式：" + runModeEnum.comment)
        }
    }

    /** Resolve a slot only from a snapshot that belongs to this strategy. */
    fun expectedDeckSlot(deckStrategy: DeckStrategy): Int {
        val snapshot = DeckStrategyManager.currentRuntimeSelectionSnapshot()
        val snapshotSlot = snapshot.deckSlot
            ?.takeIf { it in StrategyDefaultDeckSlotBindings.MIN_DECK_SLOT..StrategyDefaultDeckSlotBindings.MAX_DECK_SLOT }
            ?.takeIf { snapshot.strategyId == deckStrategy.id() }
        val resolved = snapshotSlot
            ?: StrategyDefaultDeckSlotBindings.chooseDeckSlots(
                rule = null,
                strategyId = deckStrategy.id(),
                globalDeckSlots = ConfigExUtil.getChooseDeckPos(),
            ).deckSlots.firstOrNull()
            ?: StrategyDefaultDeckSlotBindings.MIN_DECK_SLOT
        log.info {
            "DECK_SLOT_RESOLVED strategy=${deckStrategy.id()} name=${deckStrategy.name()} " +
                "slot=$resolved snapshotStrategy=${snapshot.strategyId ?: "none"} " +
                "snapshotSlot=${snapshot.deckSlot ?: "none"}"
        }
        return resolved
    }

    /** Used by screen recovery after it has positively identified deck selection. */
    fun recoverDeckSelectionAndStart() {
        val deckStrategy = DeckStrategyManager.currentDeckStrategy
        val runMode = DeckStrategyManager.currentRunMode
        if (deckStrategy == null || runMode == null) {
            log.warn { "DECK_SELECTION_RECOVERY_SKIPPED reason=missing-runtime-selection" }
            return
        }
        val slot = expectedDeckSlot(deckStrategy)
        log.warn {
            "DECK_SELECTION_RECOVERY_APPLY mode=${runMode.name} strategy=${deckStrategy.id()} " +
                "deckSlot=$slot"
        }
        selectDeck(deckStrategy, slot)
        SystemUtil.delayShort()
        startMatching()
    }

    fun selectDeck(deckStrategy: DeckStrategy, deckSlot: Int = expectedDeckSlot(deckStrategy)) {
//        val decks: List<Deck> = DECKS
//        for (i in decks.indices.reversed()) {
//            val d = decks[i]
//            if (d.code == deckStrategy.deckCode() || d.name == deckStrategy.name()) {
//                log.debug { "找到套牌:" + deckStrategy.name() }
//                break
//            }
//        }
        log.info { "选择套牌" }

        PREV_DECK_PAGE.lClick()
        SystemUtil.delayTiny()
        log.info { "选择套牌槽位 strategy=${deckStrategy.id()} name=${deckStrategy.name()} deckSlot=$deckSlot" }
        GameUtil.lClickDeckSlot(deckSlot)
    }

    private fun changeModeToClassic() {
        log.info { "切换至经典模式" }
        CLASSIC_MODE_RECT.lClick()
    }

    private fun changeModeToStandard() {
        log.info { "切换至标准模式" }
        STANDARD_MODE_RECT.lClick()
    }

    private fun changeModeToWild() {
        log.info { "切换至狂野模式" }
        WILD_MODE_RECT.lClick()
    }

    private fun changeModeToCasual() {
        log.info { "切换至休闲模式" }
        CASUAL_MODE_RECT.lClick()
    }

    fun startMatching() {
        if (PauseStatus.isPause || !WorkTimeListener.working) {
            log.info {
                "MATCHMAKING_REQUEST_IGNORED reason=${if (PauseStatus.isPause) "paused" else "runtime-not-working"} " +
                    "action=NO_RANK_READ_NO_QUEUE_INPUT"
            }
            return
        }
        if (GameUtil.hasTerminalPageCleanupFence()) {
            log.info {
                "MATCHMAKING_DEFERRED reason=terminal-result-cleanup-pending " +
                    "action=NO_QUEUE_INPUT"
            }
            return
        }
        // This capture happens immediately before the first matchmaking input;
        // it is never satisfied from a cached rank from an earlier game.
        val rankDetection = CurrentRankDetector.detect(
            trigger = "pre-match-deck-selection-rank-gate",
            phase = "DECK_SELECTION",
        )
        val traceId = matchmakingTraceSequence.incrementAndGet()
        log.info { "开始匹配 trace=$traceId" }
        val mandatoryRankSurrenderPending =
            club.xiaojiawei.hsscript.status.surrender.MandatoryRankSurrenderGuard.isPending()
        val rankGate = PreMatchRankGate.evaluate(
            working = WorkTimeListener.working,
            paused = PauseStatus.isPause,
            mandatoryRankSurrenderPending = mandatoryRankSurrenderPending,
            tournamentMode = Mode.currMode === ModeEnum.TOURNAMENT,
            inWar = WarEx.inWar,
            observedRank = rankDetection?.rank,
            freshRankObservation = rankDetection != null,
            ocrFailure = rankDetection == null,
        )
        val queueAuthorization = rankGate.queueAuthorization
        val dispatchMatchmaking = queueAuthorization.allowed
        log.info {
            "MATCHMAKING_GATE stage=PRE_MATCH rankPolicy=FRESH_EXACT_5_OR_10 " +
                "working=${WorkTimeListener.working} paused=${PauseStatus.isPause} " +
                "mode=${Mode.currMode} inWar=${WarEx.inWar} rank=${rankDetection?.rank ?: "UNKNOWN"} " +
                "rankProvider=${rankDetection?.provider ?: "UNAVAILABLE"} " +
                "decision=${if (dispatchMatchmaking) "ALLOW" else "DENY"} " +
                "reason=${queueAuthorization.reason}"
        }
        if (!dispatchMatchmaking) {
            log.warn {
                "MATCHMAKING_BLOCKED trace=$traceId " +
                    "reason=${queueAuthorization.reason} " +
                    "action=NO_QUEUE_INPUT"
            }
            return
        }
        logMatchmakingCheckpoint(traceId, "before-start")
        if (abortMatchmakingIfGameStarted(traceId, "before-start-click")) return
        // Keep the upstream entry sequence: Hearthstone may first display the
        // automatic deck-completion dialog.  A single start click can leave
        // the client on the deck-selection page while the script has already
        // logged START_MATCHING, which is not a real state transition.
        if (!MatchmakingGuardPolicy.dispatchIfAuthorized(queueAuthorization) {
                clickMatchmakingControl(START_RECT)
            }
        ) {
            log.warn {
                "MATCHMAKING_BLOCKED trace=$traceId reason=${queueAuthorization.reason} " +
                    "action=NO_QUEUE_INPUT"
            }
            return
        }
        logMatchmakingCheckpoint(traceId, "after-start-click")
        SystemUtil.delayLong()
        if (abortMatchmakingIfGameStarted(traceId, "after-start-click")) return
        log.info { "尝试确认自动补全套牌弹窗" }
        logMatchmakingCheckpoint(traceId, "before-complete-deck-click-1")
        clickMatchmakingControl(COMPLETE_DECK_CONFIRM_RECT)
        logMatchmakingCheckpoint(traceId, "after-complete-deck-click-1")
        SystemUtil.delayShortMedium()
        if (abortMatchmakingIfGameStarted(traceId, "after-complete-deck-click-1")) return
        log.info { "重试确认自动补全套牌弹窗" }
        logMatchmakingCheckpoint(traceId, "before-complete-deck-click-2")
        clickMatchmakingControl(COMPLETE_DECK_CONFIRM_RECT)
        logMatchmakingCheckpoint(traceId, "after-complete-deck-click-2")
        SystemUtil.delayShortMedium()
        if (abortMatchmakingIfGameStarted(traceId, "after-complete-deck-click-2")) return
        log.info { "确认补全后再次点击开始" }
        logMatchmakingCheckpoint(traceId, "before-final-start-click")
        clickMatchmakingControl(START_RECT)
        logMatchmakingCheckpoint(traceId, "after-final-start-click")
        captureMatchmakingCheckpoint(traceId, "after-final-start-click")
        if (abortMatchmakingIfGameStarted(traceId, "after-final-start-click")) return
        generateTimer(traceId)
        scheduleMatchmakingDialogRecovery(traceId)
    }

    private fun abortMatchmakingIfGameStarted(traceId: Long, stage: String): Boolean {
        val evidence = liveGameEvidence()
        if (MatchmakingGuardPolicy.decide(evidence) != MatchmakingGuardPolicy.Decision.ABORT_GAME_STARTED) {
            return false
        }
        StartupMatchmakingQueueState.observeGameCreated(GameUtil.findGameProcessIdForDiagnostics())
        cancelAllEnteredTasks()
        log.warn {
            "MATCHMAKING_ABORTED trace=$traceId reason=game-started stage=$stage " +
                "mode=${Mode.currMode?.name ?: "NONE"} inWar=${evidence.inWar} " +
                "warPhase=${evidence.warPhase} gameId=${evidence.gameId.ifBlank { "NONE" }} " +
                "powerLogPosition=${evidence.powerLogPosition}"
        }
        return true
    }

    private fun liveGameEvidence(): MatchmakingGuardPolicy.LiveGameEvidence {
        val war = WarEx.war
        val gameId = listOf(war.firstPlayerGameId, war.me.gameId, war.rival.gameId)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return MatchmakingGuardPolicy.LiveGameEvidence(
            inWar = WarEx.inWar,
            warPhase = war.currentPhase.name,
            gameId = gameId,
            powerLogPosition = PowerLogListener.logFile?.getPosition() ?: -1L,
        )
    }

    private fun logMatchmakingCheckpoint(traceId: Long, stage: String) {
        val powerLog = PowerLogListener.logFile
        log.info {
            "MATCHMAKING_TRACE trace=$traceId stage=$stage " +
                "mode=${Mode.currMode?.name ?: "NONE"} " +
                "pause=${PauseStatus.isPause} working=${WorkTimeListener.working} " +
                "gameHwnd=${ScriptStatus.gameHWND} " +
                "powerLog=${powerLog?.path() ?: "none"} " +
                "powerPos=${powerLog?.getPosition() ?: -1} " +
                "powerLen=${powerLog?.length() ?: -1}"
        }
    }

    /**
     * Matchmaking controls belong to the normal event-driven path. The old
     * implementation routed every start/confirm/error click through the
     * recovery input path, forcing an eight-attempt foreground recovery even
     * when Hearthstone was already focused.
     *
     * The normal click path still verifies the target window before sending
     * input. The bounded matchmaking popup watchdog remains the fallback when
     * the client genuinely stops progressing.
     */
    private fun clickMatchmakingControl(rect: GameRect): Boolean {
        val pos = rect.getCenterClickPos()
        log.info {
            "MATCHMAKING_INPUT_DISPATCH mode=normal pos=(${pos.x},${pos.y}) " +
                "gameHwnd=${ScriptStatus.gameHWND}"
        }
        MouseUtil.leftButtonClick(pos, ScriptStatus.gameHWND)
        return true
    }

    private fun captureMatchmakingCheckpoint(traceId: Long, stage: String) {
        if (System.getProperty("hs.script.e2e") != "true") return
        val evidence = UnknownStateScreenshot.capture(
            category = UnknownStateScreenshot.CATEGORY_POPUP_RECOVERY,
            trigger = "matchmaking-$stage",
            state = "trace=$traceId|mode=${Mode.currMode?.name ?: "NONE"}",
            phase = "tournament-matchmaking",
            label = stage,
        )
        log.info {
            "MATCHMAKING_TRACE_SCREENSHOT trace=$traceId stage=$stage " +
                "path=${evidence?.file?.absolutePath ?: "not-saved"} " +
                "link=${evidence?.link ?: "none"}"
        }
    }

    /**
     * A failed start can leave the exact error modal above deck selection.
     * Probe the live client and click the upstream ERROR_RECT only after a
     * fresh capture positively matches the title, body, and confirm label.
     */
    private fun scheduleMatchmakingDialogRecovery(traceId: Long) {
        var attempts = 0
        var priorClickSent = false
        val retrySupervisor = MatchmakingDialogRecoveryRetrySupervisor()
        lateinit var recoveryTask: ScheduledFuture<*>
        recoveryTask = EXTRA_THREAD_POOL.scheduleWithFixedDelay(
            LRunnable {
                if (PauseStatus.isPause || Mode.currMode !== ModeEnum.TOURNAMENT) {
                    recoveryTask.cancel(false)
                    return@LRunnable
                }
                if (abortMatchmakingIfGameStarted(traceId, "popup-recovery")) {
                    recoveryTask.cancel(false)
                    return@LRunnable
                }
                val probe = ScreenStateRecovery.probeStartGameErrorDialogForMatchmaking()
                if (probe.queueSearchModalVisible && probe.capturedGamePid != null) {
                    log.info {
                        "MATCHMAKING_QUEUE_VISUAL_PRESERVED trace=$traceId pid=${probe.capturedGamePid} " +
                            "probe=verified-live-queue-modal input=none retryBudgetConsumed=false " +
                            "startupRecovery=wait-for-create-game screenshot=${probe.screenshot ?: "none"}"
                    }
                    return@LRunnable
                }
                if (probe.state == MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE) {
                    StartupMatchmakingQueueState.observeQueueTerminal(
                        GameUtil.findGameProcessIdForDiagnostics(),
                        "authoritative-start-game-error-dialog",
                    )
                }
                if (!MatchmakingDialogRecoveryPolicy.countsTowardAttemptBudget(probe.reason)) {
                    log.debug {
                        "MATCHMAKING_ERROR_DIALOG_PROBE_DEFERRED trace=$traceId " +
                            "reason=${probe.reason} attempts=$attempts retryBudgetConsumed=false input=none"
                    }
                    return@LRunnable
                }
                val retryDecision = retrySupervisor.observe(System.currentTimeMillis(), probe.state)
                when (retryDecision.action) {
                    MatchmakingDialogRecoveryRetrySupervisor.Action.WAIT_COOLDOWN -> {
                        if (retryDecision.reason != "cooldown-active") {
                            log.info {
                                "MATCHMAKING_ERROR_DIALOG_COOLDOWN trace=$traceId " +
                                    "reason=${retryDecision.reason} clickAllowed=false pauseRequested=false " +
                                    "retryAfterMs=${retryDecision.retryAfterMs} " +
                                    "freshProbe=${probe.state} probeReason=${probe.reason} " +
                                    "screenshot=${probe.screenshot ?: "none"}"
                            }
                        }
                        return@LRunnable
                    }

                    MatchmakingDialogRecoveryRetrySupervisor.Action.REARMED -> {
                        attempts = 0
                        priorClickSent = false
                        log.warn {
                            "MATCHMAKING_ERROR_DIALOG_REARMED trace=$traceId " +
                                "reason=${retryDecision.reason} evidence=fresh-exact-dialog " +
                                "nextProbeRequired=true clickAllowed=false pauseRequested=false"
                        }
                        return@LRunnable
                    }

                    MatchmakingDialogRecoveryRetrySupervisor.Action.ATTEMPT_ALLOWED -> Unit
                }
                val gameStarted = MatchmakingGuardPolicy.decide(liveGameEvidence()) ==
                    MatchmakingGuardPolicy.Decision.ABORT_GAME_STARTED
                val decision = MatchmakingDialogRecoveryPolicy.decide(
                    context = MatchmakingDialogRecoveryPolicy.Context(
                        paused = PauseStatus.isPause,
                        tournamentMode = Mode.currMode === ModeEnum.TOURNAMENT,
                        gameStarted = gameStarted,
                    ),
                    probe = probe.state,
                    completedAttempts = attempts,
                    priorClickSent = priorClickSent,
                )
                log.info {
                    "MATCHMAKING_ERROR_DIALOG_PROBE trace=$traceId attempt=${attempts + 1} " +
                        "state=${probe.state} provider=${probe.provider} reason=${probe.reason} action=${decision.action} " +
                        "title=${probe.title.ifBlank { "<empty>" }} " +
                        "body=${probe.body.ifBlank { "<empty>" }} " +
                        "confirm=${probe.confirm.ifBlank { "<empty>" }} " +
                        "screenshot=${probe.screenshot ?: "none"}"
                }
                when (decision.action) {
                    MatchmakingDialogRecoveryPolicy.Action.CLICK_CONFIRM -> {
                        if (abortMatchmakingIfGameStarted(traceId, "popup-recovery-before-error-click")) {
                            recoveryTask.cancel(false)
                            return@LRunnable
                        }
                        val accepted = MatchmakingDialogRecoveryPolicy.dispatchConfirm(decision) {
                            MouseUtil.leftButtonClickForRecovery(ERROR_RECT.getCenterClickPos())
                        } ?: false
                        attempts++
                        priorClickSent = priorClickSent || accepted
                        log.warn {
                            "MATCHMAKING_ERROR_DIALOG_CLICK trace=$traceId attempt=$attempts " +
                                "inputAccepted=$accepted targetSystemConfirmed=false " +
                                "rect=ERROR_RECT reason=${decision.reason}"
                        }
                    }

                    MatchmakingDialogRecoveryPolicy.Action.WAIT_AND_RETRY -> {
                        attempts++
                        log.warn {
                            "MATCHMAKING_ERROR_DIALOG_WAIT trace=$traceId attempt=$attempts " +
                                "reason=${decision.reason} input=none"
                        }
                    }

                    MatchmakingDialogRecoveryPolicy.Action.CONFIRMED_DISMISSED -> {
                        log.warn {
                            "MATCHMAKING_ERROR_DIALOG_CONFIRMED trace=$traceId " +
                                "evidence=fresh-post-click-visual-state input=none"
                        }
                        recoveryTask.cancel(false)
                    }

                    MatchmakingDialogRecoveryPolicy.Action.STOP_NOT_PRESENT -> {
                        log.info {
                            "MATCHMAKING_ERROR_DIALOG_NOT_PRESENT trace=$traceId " +
                                "evidence=positive-non-error-screen input=none"
                        }
                        recoveryTask.cancel(false)
                    }

                    MatchmakingDialogRecoveryPolicy.Action.CANCEL -> {
                        log.info {
                            "MATCHMAKING_ERROR_DIALOG_CANCELLED trace=$traceId reason=${decision.reason} input=none"
                        }
                        recoveryTask.cancel(false)
                    }

                    MatchmakingDialogRecoveryPolicy.Action.EXHAUSTED -> {
                        val evidence = UnknownStateScreenshot.capture(
                            category = UnknownStateScreenshot.CATEGORY_POPUP_RECOVERY,
                            trigger = "matchmaking-popup-recovery-exhausted",
                            state = "mode=${Mode.currMode?.name ?: "NONE"}|attempts=$attempts",
                            phase = "tournament-matchmaking",
                            label = "popup-recovery-exhausted",
                        )
                        val cooldown = retrySupervisor.beginCooldown(System.currentTimeMillis())
                        log.warn {
                            "MATCHMAKING_ERROR_DIALOG_EXHAUSTED trace=$traceId attempts=$attempts " +
                                "pauseRequested=false dispatch=false input=none " +
                                "retryAfterMs=${cooldown.retryAfterMs} " +
                                "screenshot=${evidence?.file?.absolutePath ?: "not-saved"} " +
                                "screenshotLink=${evidence?.link ?: "none"}"
                        }
                    }
                }
            },
            800,
            1_000,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * 生成匹配失败时兜底的定时器
     */
    private fun generateTimer(traceId: Long) {
        cancelAllEnteredTasks()
        val matchMaximumTime = if (System.getProperty("hs.script.e2e") == "true") {
            minOf(ConfigUtil.getLong(ConfigEnum.MATCH_MAXIMUM_TIME), 45L)
        } else {
            ConfigUtil.getLong(ConfigEnum.MATCH_MAXIMUM_TIME)
        }
        addEnteredTask(
            EXTRA_THREAD_POOL.schedule(
                LRunnable {
                    if (PauseStatus.isPause || Thread.currentThread().isInterrupted || Mode.currMode === ModeEnum.GAMEPLAY) {
                        cancelAllEnteredTasks()
                    } else if (abortMatchmakingIfGameStarted(traceId, "match-timeout")) {
                        cancelAllEnteredTasks()
                    } else if (StartupMatchmakingQueueState.timeoutDisposition(
                            GameUtil.findGameProcessIdForDiagnostics(),
                            GameUtil.isAliveOfGame(),
                        ) == MatchmakingQueueLifecycle.TimeoutDisposition.DEFER_WITHOUT_INPUT
                    ) {
                        log.info {
                            "MATCHMAKING_TIMEOUT_DEFERRED trace=$traceId " +
                                "reason=confirmed-queue-awaiting-create-game cancel=false retryInput=false"
                        }
                    } else if (StartupMatchmakingQueueState.timeoutDisposition(
                            GameUtil.findGameProcessIdForDiagnostics(),
                            GameUtil.isAliveOfGame(),
                        ) == MatchmakingQueueLifecycle.TimeoutDisposition.STOP
                    ) {
                        val queuePhase = StartupMatchmakingQueueState.snapshotFor(
                            GameUtil.findGameProcessIdForDiagnostics(),
                            GameUtil.isAliveOfGame(),
                        ).phase
                        log.info {
                            "MATCHMAKING_TIMEOUT_STOPPED trace=$traceId " +
                                "reason=queue-phase-${queuePhase.name.lowercase()} input=none"
                        }
                    } else {
                        log.info { "匹配失败，再次匹配中" }
                        SystemUtil.notice("匹配失败，再次匹配中")
//                点击取消匹配按钮
                        if (abortMatchmakingIfGameStarted(traceId, "match-timeout-before-cancel")) return@LRunnable
                        CANCEL_RECT.lClick()
                        SystemUtil.delayLong()
//                点击错误按钮
                        if (abortMatchmakingIfGameStarted(traceId, "match-timeout-before-error")) return@LRunnable
                        // ERROR_RECT is only reachable through the exact-dialog
                        // watchdog above, which verifies disappearance after click.
                        log.info { "MATCHMAKING_TIMEOUT_ERROR_DIALOG_CLICK_SKIPPED reason=exact-dialog-watchdog-only" }
                        if (abortMatchmakingIfGameStarted(traceId, "match-timeout-before-reconnect")) return@LRunnable
                        reconnectAction()
                        val seed = RandomUtil.rerollSeed()
                        log.info { "重新匹配，共享随机种子：$seed" }
                        afterEnter(null)
                    }
                },
                matchMaximumTime,
                TimeUnit.SECONDS,
            ),
        )
    }
}
