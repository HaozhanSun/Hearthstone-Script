package club.xiaojiawei.hsscript.strategy.phase

import club.xiaojiawei.hsscript.bean.ChangeCardThread
import club.xiaojiawei.hsscript.bean.log.TagChangeEntity
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.enums.MulliganStateEnum
import club.xiaojiawei.hsscript.enums.TagEnum
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.strategy.AbstractPhaseStrategy
import club.xiaojiawei.hsscript.strategy.DeckStrategyActuator.changeCard
import club.xiaojiawei.hsscript.status.surrender.SurrenderPolicy
import club.xiaojiawei.hsscript.status.surrender.SurrenderRuleResult
import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import club.xiaojiawei.hsscript.status.E2ETrace
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.MulliganScreenshot
import javafx.beans.value.ChangeListener
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.enums.StepEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.kt.config.log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 换牌阶段
 *
 * @author 肖嘉威
 * @date 2022/11/26 17:24
 */
object ReplaceCardPhaseStrategy : AbstractPhaseStrategy() {

    private val changeCardScheduled = MulliganActionGate()
    private val mulliganStageConfirmed = AtomicBoolean(false)
    private val mulliganInputConfirmed = AtomicBoolean(false)
    private val rankSurrenderRequested = AtomicBoolean(false)
    private val rankPreflightStarted = AtomicBoolean(false)
    private val rankSurrenderRetryPolicy = MulliganRankSurrenderRetryPolicy()

    @Volatile
    private var rankSurrenderRetryFuture: ScheduledFuture<*>? = null

    @Volatile
    private var rankBarrierTicket: Long? = null

    @Volatile
    private var rankPreflight: MulliganRankPreflight? = null

    @Volatile
    private var rankHoldResumeListener: ChangeListener<Boolean>? = null

    @Volatile
    private var latestMyMulliganState: MulliganStateEnum? = null

    /**
     * A watchdog restart can replay the current game's MULLIGAN_STATE=INPUT
     * before it starts tailing new lines. Keep the latest input event so the
     * real action is started once replay has finished, instead of losing the
     * only event that opened the mulligan UI.
     */
    @Volatile
    private var replayedMulliganInput: TagChangeEntity? = null

    /**
     * Some clients emit MULLIGAN_STATE=INPUT before the player mapping and
     * first-player game id have been populated. Keep those inputs until the
     * identity can be resolved instead of silently treating both players as
     * the opponent.
     */
    private val pendingUnknownMulliganInputs = ConcurrentLinkedQueue<TagChangeEntity>()

    /**
     * The current client can emit MULLIGAN_STATE=INPUT before
     * NEXT_STEP=BEGIN_MULLIGAN and can emit it more than once (once per
     * player and once per log stream). Schedule the player's mulligan once
     * for the current game, regardless of which stream emitted the first
     * INPUT line.
     */
    fun resetForNewGame() {
        rankHoldResumeListener?.let { PauseStatus.removeChangeListener(it) }
        rankHoldResumeListener = null
        cancelRankSurrenderRetry(resetBudget = true)
        cancelRankPreflight("new-game")
        // Keep the rank gate scoped to the same game lifecycle as the
        // mulligan state. FillDeck normally performs this reset too, but the
        // recovery/startup path can enter a new INPUT without replaying that
        // callback in the same ordering.
        SurrenderPolicy.resetForNewGame()
        MulliganRankDispatchBarrier.resetForNewGame()
        rankBarrierTicket = null
        changeCardScheduled.reset()
        mulliganStageConfirmed.set(false)
        mulliganInputConfirmed.set(false)
        rankSurrenderRequested.set(false)
        rankPreflightStarted.set(false)
        latestMyMulliganState = null
        replayedMulliganInput = null
        pendingUnknownMulliganInputs.clear()
    }

    fun resumeAfterExistingLogReplay() {
        flushPendingMulliganInputs()
        val pendingInput = replayedMulliganInput ?: return
        replayedMulliganInput = null
        if (war.currentPhase != WarPhaseEnum.REPLACE_CARD || !isMyMulliganEvent(pendingInput)) {
            log.info {
                "E2E恢复回放完成：历史换牌输入无效，phase=${war.currentPhase.name} " +
                    "entity=${pendingInput.entity} my=${effectiveMyGameId()}"
            }
            return
        }
        log.info { "E2E恢复回放完成：补处理当前有效的换牌输入" }
        handleMulliganInput(pendingInput)
    }

    fun discardAfterExistingLogReplay() {
        cancelRankPreflight("historical-replay-finished")
        if (replayedMulliganInput != null) {
            log.info { "E2E恢复回放完成：当前已离开换牌阶段，丢弃历史换牌输入" }
        }
        replayedMulliganInput = null
        pendingUnknownMulliganInputs.clear()
    }

    /** Called by the phase parser whenever player identity information improves. */
    fun flushPendingMulliganInputs() {
        if (pendingUnknownMulliganInputs.isEmpty() || !hasPlayerIdentity()) return

        val pending = ArrayList<TagChangeEntity>()
        while (true) {
            val event = pendingUnknownMulliganInputs.poll() ?: break
            pending.add(event)
        }
        val ownInput = pending.lastOrNull(::isMyMulliganEvent)
        if (ownInput != null) {
            log.info {
                "换牌玩家身份已解析，补处理暂存的我方换牌输入：entity=${ownInput.entity}"
            }
            handleMulliganInput(ownInput)
        } else {
            log.info { "换牌玩家身份已解析，暂存输入均属于对手，已丢弃" }
        }
    }

    fun handleMulliganInput(tagChangeEntity: TagChangeEntity) {
        if (tagChangeEntity.tag !== TagEnum.MULLIGAN_STATE
            || tagChangeEntity.value != MulliganStateEnum.INPUT.name
        ) {
            return
        }

        // DrawnInitCardPhaseStrategy can forward an early INPUT directly
        // before this phase's normal tag handler has stored the state.
        latestMyMulliganState = MulliganStateEnum.INPUT

        if (!isMyMulliganEvent(tagChangeEntity)) {
            if (!hasPlayerIdentity()) {
                pendingUnknownMulliganInputs.add(tagChangeEntity)
                log.info {
                    "暂存换牌输入，等待玩家身份解析：entity=${tagChangeEntity.entity}"
                }
            } else {
                log.info {
                    "忽略对手换牌输入：entity=${tagChangeEntity.entity} my=${effectiveMyGameId()}"
                }
            }
            return
        }

        val terminalIdentity = PowerLogListener.bindVerifiedLocalMulliganEntity(tagChangeEntity.entity)
        log.info {
            "MULLIGAN_LOCAL_POWERLOG_IDENTITY_BOUND entity=${tagChangeEntity.entity} " +
                "gameIdentity=${terminalIdentity ?: "UNRESOLVED"} source=verified-own-mulligan-input"
        }

        if (PowerLogListener.replayingExistingLog) {
            replayedMulliganInput = tagChangeEntity
            log.info { "E2E恢复回放：跳过历史换牌点击，等待实时日志继续" }
            return
        }
        if (!rankPreflightStarted.compareAndSet(false, true)) {
            log.info {
                "MULLIGAN_RANK_PREFLIGHT_START_IGNORED reason=duplicate-own-input " +
                    "action=KEEP_CURRENT_BARRIER_AND_RETRY_SCHEDULE"
            }
            return
        }

        // This is the first authoritative event that proves the local
        // mulligan UI exists. Rank OCR is not allowed before this boundary.
        val barrierTicket = MulliganRankDispatchBarrier.beginCurrentGame()
        rankBarrierTicket = barrierTicket
        log.info {
            "MULLIGAN_RANK_DISPATCH_BARRIER state=PENDING ticket=$barrierTicket " +
                "action=BLOCK_ORDINARY_INPUT_UNTIL_FRESH_ALLOWED_RANK"
        }
        mulliganInputConfirmed.set(true)
        rankSurrenderRequested.set(false)
        cancelAllTask()
        startRankPreflight(barrierTicket)
    }

    /** Queue normal Mulligan work only after this game's fresh rank decision opens the barrier. */
    private fun scheduleMulliganAction() {
        if (MulliganRankDispatchBarrier.currentState() != MulliganRankDispatchBarrier.State.ELIGIBLE) {
            log.warn {
                "MULLIGAN_ACTION_QUEUE_BLOCKED reason=rank-barrier-not-eligible " +
                    "state=${MulliganRankDispatchBarrier.currentState()} queue=false"
            }
            return
        }
        val scheduled = changeCardScheduled.tryReserve()
        log.info { "自动换牌线程调度结果：$scheduled rankBarrier=ELIGIBLE" }
        if (!scheduled) return
        val skipMulliganSurrender =
            System.getProperty("hs.script.e2e.skip-mulligan-surrender") == "true"
        (ChangeCardThread {
            try {
                if (skipMulliganSurrender) {
                    log.info { "E2E_TEST_ONLY 跳过排位/对手身份投降检查，保留真实换牌动作" }
                }
                log.info { "自动换牌线程开始执行" }
                if (changeCard()) {
                    log.info {
                        "自动换牌动作已提交，等待Power.log确认当前玩家换牌状态结束"
                    }
                } else {
                    log.warn { "自动换牌动作未提交，等待当前对局的阶段事件继续诊断" }
                }
            } catch (interrupted: InterruptedException) {
                // A game-over/surrender transition intentionally cancels the
                // delayed mulligan worker.  This is normal task lifecycle
                // cancellation, not a script crash; keep it visible without
                // poisoning the UI's error stream.
                Thread.currentThread().interrupt()
                log.info {
                    "自动换牌线程按生命周期取消，未视为崩溃 " +
                        "phase=${war.currentPhase.name} " +
                        "myMulliganState=${latestMyMulliganState?.name ?: "NONE"}"
                }
            } catch (t: Throwable) {
                log.error(t) { "自动换牌线程异常退出" }
            } finally {
                log.info {
                    "自动换牌线程结束（仅表示动作线程返回），interrupted=${Thread.currentThread().isInterrupted} " +
                        "myMulliganState=${latestMyMulliganState?.name ?: "NONE"} " +
                        "stageConfirmed=${mulliganStageConfirmed.get()} phase=${war.currentPhase.name}"
                }
            }
        }.also { addTask(it) }).start()
    }

    private fun startRankPreflight(barrierTicket: Long) {
        if (System.getProperty("hs.script.e2e.skip-surrender-policy") == "true") {
            log.info { "MULLIGAN_RANK_PREFLIGHT_SKIPPED reason=e2e-policy-bypass action=CONTINUE_MULLIGAN pause=false" }
            return
        }
        rankPreflight?.cancel("replaced-by-new-input")
        rankPreflight = MulliganRankPreflight(
            isEligible = ::isRankPreflightEligible,
            inspect = {
                SurrenderPolicy.evaluateCurrentRankBeforeMulligan()
                    ?: if (SurrenderPolicy.currentRankContinueAuthorized()) {
                        SurrenderRuleResult(
                            ruleId = "rank-continue-authorized",
                            matched = true,
                            shouldSurrender = false,
                            reason = "verified-eligible-current-rank",
                            currentRank = SurrenderPolicy.currentRankAuthorizedNumber(),
                        )
                    } else {
                        null
                    }
            },
            // A null inspection result is never enough to continue: it can
            // mean OCR is unavailable, the policy is waiting, or a stale
            // completion flag leaked from another lifecycle.
            isResolved = { false },
            authorizeContinue = { result ->
                result.currentRank?.let { rank ->
                    MulliganRankDispatchBarrier.authorizeEligibleRank(barrierTicket, rank)
                } ?: false
            },
            provider = {
                if (OcrRuntime.isLegacySelected()) "LEGACY" else "PADDLEX"
            },
            onSurrender = surrender@{ result ->
                if (GameUtil.isTerminalGameState()) {
                    cancelRankSurrenderRetry(resetBudget = true)
                    cancelAllTask()
                    cancelRankPreflight("terminal-state-priority")
                    val released = MulliganRankDispatchBarrier.completeTerminalWithoutSurrender(barrierTicket)
                    log.info {
                        "MULLIGAN_RANK_PREFLIGHT_TERMINAL_PRIORITY ticket=$barrierTicket " +
                            "rule=${result.ruleId} barrierReleased=$released action=NO_SURRENDER"
                    }
                    return@surrender
                }
                if (!isRankPreflightEligible()) {
                    log.info {
                        "MULLIGAN_RANK_PREFLIGHT_DECISION_DISCARDED reason=phase-or-input-left " +
                            "rule=${result.ruleId} action=NO_ACTION pause=false"
                    }
                    return@surrender
                }
                if (!rankSurrenderRequested.compareAndSet(false, true)) return@surrender
                val surrenderCapability = MulliganRankDispatchBarrier.requireSurrender(barrierTicket)
                if (surrenderCapability == null) {
                    log.warn {
                        "MULLIGAN_RANK_DISPATCH_BARRIER_SURRENDER_REJECTED ticket=$barrierTicket " +
                            "reason=stale-or-invalid-barrier action=BLOCK"
                    }
                    rankSurrenderRequested.set(false)
                    return@surrender
                }
                log.warn {
                    "MULLIGAN_RANK_DISPATCH_BARRIER state=SURRENDER_REQUIRED ticket=$barrierTicket " +
                        "rule=${result.ruleId} action=BLOCK_ORDINARY_ALLOW_MANDATORY_SURRENDER_ONLY"
                }
                cancelAllTask()
                val dispatched = dispatchSurrenderDecision(
                    result,
                    "mulligan-rank-preflight",
                    rankSurrenderCapability = surrenderCapability,
                )
                if (dispatched) {
                    cancelRankSurrenderRetry(resetBudget = true)
                } else {
                    scheduleRankSurrenderRetry(result, "mulligan-rank-preflight", surrenderCapability, barrierTicket)
                }
            },
            onContinue = {
                val rank = SurrenderPolicy.currentRankAuthorizedNumber()
                log.info {
                    "MULLIGAN_RANK_DISPATCH_BARRIER state=ELIGIBLE ticket=$barrierTicket rank=$rank " +
                        "evidence=fresh-policy-verified action=ALLOW_ORDINARY_INPUT"
                }
                log.info {
                    "MULLIGAN_RANK_PREFLIGHT_CONTINUE action=CONTINUE_MULLIGAN " +
                        "provider=${if (OcrRuntime.isLegacySelected()) "LEGACY" else "PADDLEX"} pause=false"
                }
                scheduleMulliganAction()
            },
            onHold = { result -> holdForUnresolvedRank(barrierTicket, result) },
        ).also { it.start() }
    }

    private fun holdForUnresolvedRank(ticket: Long, result: SurrenderRuleResult) {
        rankHoldResumeListener?.let(PauseStatus::removeChangeListener)
        val listener = ChangeListener<Boolean> { _, _, paused ->
            if (!paused && rankBarrierTicket == ticket &&
                MulliganRankDispatchBarrier.currentState() == MulliganRankDispatchBarrier.State.PENDING
            ) {
                if (isRankPreflightEligible()) {
                    rankHoldResumeListener?.let { PauseStatus.removeChangeListener(it) }
                    rankHoldResumeListener = null
                    SurrenderPolicy.retryRankInspectionAfterHold()
                    log.warn {
                        "MULLIGAN_RANK_HOLD_RESUMED ticket=$ticket action=RETRY_FRESH_RANK " +
                            "ordinaryInput=false reason=explicit-resume"
                    }
                    startRankPreflight(ticket)
                } else {
                    log.warn {
                        "MULLIGAN_RANK_HOLD_RESUME_BLOCKED ticket=$ticket " +
                            "reason=active-mulligan-not-confirmed action=REPAUSE ordinaryInput=false"
                    }
                    PauseStatus.setAutomaticPause(true)
                }
            }
        }
        rankHoldResumeListener = listener
        PauseStatus.addChangeListener(listener)
        PauseStatus.setAutomaticPause(true)
        log.warn {
            "MULLIGAN_RANK_FAIL_CLOSED_HOLD ticket=$ticket rule=${result.ruleId} " +
                "action=AUTOMATIC_PAUSE ordinaryInput=false surrender=false resume=F1_OR_EXPLICIT"
        }
    }

    private fun isRankPreflightEligible(): Boolean =
        war.currentPhase === WarPhaseEnum.REPLACE_CARD &&
            latestMyMulliganState === MulliganStateEnum.INPUT &&
            !PowerLogListener.replayingExistingLog &&
            !PauseStatus.isPause &&
            !rankSurrenderRequested.get()

    internal fun cancelRankPreflight(reason: String) {
        rankHoldResumeListener?.let { PauseStatus.removeChangeListener(it) }
        rankHoldResumeListener = null
        rankPreflight?.cancel(reason)
        rankPreflight = null
    }

    /** Stop delayed rank retries once same-game terminal Power.log proof is accepted. */
    @Synchronized
    fun onAuthoritativeTerminalProofAccepted() {
        cancelRankSurrenderRetry(resetBudget = true)
        cancelRankPreflight("authoritative-terminal-proof")
        rankSurrenderRequested.set(false)
        log.info {
            "MULLIGAN_RANK_RETRY_CANCELLED reason=authoritative-terminal-proof " +
                "barrier=${MulliganRankDispatchBarrier.currentState()} ordinaryDispatch=false"
        }
    }

    @Synchronized
    private fun scheduleRankSurrenderRetry(
        result: SurrenderRuleResult,
        source: String,
        capability: MulliganRankDispatchBarrier.SurrenderCapability,
        ticket: Long,
    ) {
        if (rankSurrenderRetryFuture?.isDone == false) return
        val retry = rankSurrenderRetryPolicy.nextRetryAfterRejection()
        log.warn {
            "MANDATORY_RANK_SURRENDER_RETRY_${if (retry.cooldown) "COOLDOWN" else "SCHEDULED"} " +
                "ticket=$ticket delayMs=${retry.delayMs} " +
                "state=${MulliganRankDispatchBarrier.currentState()} attempt=${retry.attempt} pause=${retry.pause} " +
                "phaseAdvance=${retry.allowPhaseAdvance}"
        }
        rankSurrenderRetryFuture = EXTRA_THREAD_POOL.schedule({
            synchronized(this) { rankSurrenderRetryFuture = null }
            if (rankBarrierTicket != ticket ||
                MulliganRankDispatchBarrier.currentState() != MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED ||
                !MulliganRankDispatchBarrier.isSurrenderCapabilityValid(capability)
            ) {
                log.info {
                    "MANDATORY_RANK_SURRENDER_RETRY_CANCELLED ticket=$ticket reason=stale-or-consumed-capability " +
                        "dispatch=false phaseAdvance=false"
                }
                return@schedule
            }
            if (GameUtil.isTerminalGameState()) {
                val released = MulliganRankDispatchBarrier.completeTerminalWithoutSurrender(ticket)
                cancelRankSurrenderRetry(resetBudget = true)
                log.info {
                    "MULLIGAN_RANK_PREFLIGHT_TERMINAL_PRIORITY ticket=$ticket " +
                        "reason=terminal-during-surrender-retry barrierReleased=$released action=NO_SURRENDER"
                }
                return@schedule
            }
            log.warn {
                "MANDATORY_RANK_SURRENDER_RETRY_ATTEMPT ticket=$ticket " +
                    "phase=${war.currentPhase.name} pause=${PauseStatus.isPause} dispatch=requested"
            }
            if (dispatchSurrenderDecision(result, source, rankSurrenderCapability = capability)) {
                cancelRankSurrenderRetry(resetBudget = true)
                log.info {
                    "MANDATORY_RANK_SURRENDER_RETRY_ACCEPTED ticket=$ticket " +
                        "barrier=${MulliganRankDispatchBarrier.currentState()} phaseAdvance=false"
                }
            } else {
                scheduleRankSurrenderRetry(result, source, capability, ticket)
            }
        }, retry.delayMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun cancelRankSurrenderRetry(resetBudget: Boolean) {
        rankSurrenderRetryFuture?.cancel(false)
        rankSurrenderRetryFuture = null
        if (resetBudget) rankSurrenderRetryPolicy.reset()
    }

    private fun blockMulliganAdvanceIfRankPending(reason: String): Boolean {
        val barrierState = MulliganRankDispatchBarrier.currentState()
        if (!MulliganRankSurrenderRetryPolicy.mustBlockMulliganAdvance(barrierState)) return false
        log.warn {
            "MULLIGAN_PHASE_ADVANCE_BLOCKED reason=rank-dispatch-unresolved source=$reason " +
                "barrier=$barrierState ordinaryInput=false phaseAdvance=false pause=${PauseStatus.isPause}"
        }
        return true
    }

    /** Leaving our Mulligan INPUT without a rank decision cannot silently open gameplay. */
    private fun failClosedIfRankWindowEnded(reason: String) {
        val barrierState = MulliganRankDispatchBarrier.currentState()
        if (barrierState !in setOf(
                MulliganRankDispatchBarrier.State.IDLE,
                MulliganRankDispatchBarrier.State.PENDING,
                MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED,
            )
        ) return
        if (GameUtil.isTerminalGameState()) {
            val ticket = rankBarrierTicket
            val released = ticket?.let(MulliganRankDispatchBarrier::completeTerminalWithoutSurrender) ?: false
            cancelRankSurrenderRetry(resetBudget = true)
            log.info {
                "MULLIGAN_RANK_PREFLIGHT_TERMINAL_PRIORITY ticket=${ticket ?: "none"} " +
                    "reason=$reason barrierReleased=$released action=NO_SURRENDER"
            }
            return
        }
        if (barrierState == MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED) return
        if (PauseStatus.isPause) return
        val attempts = rankPreflight?.snapshot()?.attempts ?: 0
        val result = SurrenderPolicy.blockForUnresolvedRank(attempts)
        val ticket = rankBarrierTicket ?: MulliganRankDispatchBarrier.beginCurrentGame().also { rankBarrierTicket = it }
        log.warn {
            "MULLIGAN_RANK_PREFLIGHT_WINDOW_ENDED ticket=${rankBarrierTicket ?: "none"} reason=$reason " +
                "attempts=$attempts action=HOLD_UNRESOLVED_RANK"
        }
        cancelRankPreflight("rank-window-ended-$reason")
        cancelAllTask()
        holdForUnresolvedRank(ticket, result)
    }

    /** Guard every mulligan click against a late rank decision or phase exit. */
    internal fun isMulliganActionStillAllowed(): Boolean = isRankPreflightEligible()

    override fun dealTagChangeThenIsOver(line: String, tagChangeEntity: TagChangeEntity): Boolean {
        flushPendingMulliganInputs()
        if (tagChangeEntity.tag === TagEnum.MULLIGAN_STATE) {
            if (!isMyMulliganEvent(tagChangeEntity)) {
                if (tagChangeEntity.value == MulliganStateEnum.INPUT.name && !hasPlayerIdentity()) {
                    pendingUnknownMulliganInputs.add(tagChangeEntity)
                    log.info {
                        "暂存换牌阶段事件，等待玩家身份解析：entity=${tagChangeEntity.entity} " +
                            "state=${tagChangeEntity.value}"
                    }
                } else {
                    log.info {
                        "忽略对手换牌阶段事件：entity=${tagChangeEntity.entity} state=${tagChangeEntity.value} " +
                            "my=${effectiveMyGameId()}"
                    }
                }
                return false
            }

            val state = runCatching { MulliganStateEnum.valueOf(tagChangeEntity.value) }.getOrNull()
            latestMyMulliganState = state
            log.info {
                "换牌阶段事件：entity=${tagChangeEntity.entity} state=${tagChangeEntity.value} " +
                    "replaying=${PowerLogListener.replayingExistingLog} phase=${war.currentPhase.name}"
            }

            if (state === MulliganStateEnum.INPUT) {
                handleMulliganInput(tagChangeEntity)
            } else {
                failClosedIfRankWindowEnded("mulligan-state-${state?.name?.lowercase() ?: "unknown"}")
                cancelRankPreflight("mulligan-state-${state?.name?.lowercase() ?: "unknown"}")
            }
            if (state === MulliganStateEnum.DONE && blockMulliganAdvanceIfRankPending("mulligan-done")) {
                return false
            }
            if (state === MulliganStateEnum.DONE &&
                mulliganStageConfirmed.compareAndSet(false, true)
            ) {
                log.info { "换牌阶段确认完成：当前玩家MULLIGAN_STATE=DONE" }
                MulliganScreenshot.capture("post-confirm", WarEx.warCount + 1)
                E2ETrace.markMulliganCompleted()
            }
        } else if (tagChangeEntity.tag == TagEnum.NEXT_STEP && StepEnum.MAIN_READY.name == tagChangeEntity.value) {
            failClosedIfRankWindowEnded("main-ready")
            if (blockMulliganAdvanceIfRankPending("main-ready")) return false
            cancelRankPreflight("main-ready")
            if (mulliganStageConfirmed.compareAndSet(false, true)) {
                log.info { "换牌阶段确认完成：收到NEXT_STEP=MAIN_READY" }
                E2ETrace.markMulliganCompleted()
            }
            war.currentPhase = WarPhaseEnum.SPECIAL_EFFECT_TRIGGER
            return true
        }
        return false
    }

    /** True only after a live local MULLIGAN_STATE=INPUT has been classified. */
    fun isRankInspectionReady(): Boolean =
        mulliganInputConfirmed.get() &&
            latestMyMulliganState === MulliganStateEnum.INPUT &&
            war.currentPhase === WarPhaseEnum.REPLACE_CARD &&
            !PowerLogListener.replayingExistingLog

    internal fun rankInspectionReadinessDiagnostic(): String =
        "inputConfirmed=${mulliganInputConfirmed.get()}" +
            ",latestState=${latestMyMulliganState?.name ?: "NONE"}" +
            ",phase=${war.currentPhase.name}" +
            ",replaying=${PowerLogListener.replayingExistingLog}" +
            ",ready=${isRankInspectionReady()}"

    private fun hasPlayerIdentity(): Boolean =
        war.me.gameId.isNotBlank() ||
            (war.me.playerId.isNotBlank() && war.firstPlayerGameId.isNotBlank())

    private fun effectiveMyGameId(): String {
        if (war.me.gameId.isNotBlank()) return war.me.gameId
        // During BEGIN_MULLIGAN the local player's account name can arrive
        // after the opponent's name.  When the visible-card parser has
        // already identified the opponent, the local identity is precisely
        // the other MULLIGAN_STATE entity; do not substitute the first-player
        // name for it.  FIRST_PLAYER is the opponent whenever the local
        // player lost the coin toss.
        if (war.rival.gameId.isNotBlank()) {
            return "unresolved-local(excluding=${war.rival.gameId})"
        }
        if (war.me.playerId == "1" && war.firstPlayerGameId.isNotBlank()) {
            return war.firstPlayerGameId
        }
        if (war.me.playerId == "2" && war.firstPlayerGameId.isNotBlank()) {
            return "player2(not-yet-named)"
        }
        return "unknown"
    }

    private fun isMyMulliganEvent(tagChangeEntity: TagChangeEntity): Boolean {
        val myGameId = war.me.gameId
        if (myGameId.isNotBlank()) return tagChangeEntity.entity == myGameId

        // The first-player entity is not a reliable local-player identity:
        // the opponent may have gone first.  Once the hidden-card parser has
        // assigned the opponent's game id, classify the other named entity as
        // ours.  The old fallback treated FIRST_PLAYER as local for player 1,
        // which made the script click/log the opponent's INPUT and left our
        // real Mulligan choice untouched until the timeout.
        val rivalGameId = war.rival.gameId
        if (rivalGameId.isNotBlank()) {
            return tagChangeEntity.entity.isNotBlank() &&
                tagChangeEntity.entity != rivalGameId
        }

        // Before the full-entity card record arrives, the log still tells us
        // which player went first.  If we are player 2, the non-first player
        // entity is ours; this is enough to schedule mulligan without a
        // timeout while we wait for the later game-id assignment.
        val firstPlayerGameId = war.firstPlayerGameId
        if (firstPlayerGameId.isNotBlank() && war.me.playerId in setOf("1", "2")) {
            return if (war.me.playerId == "1") {
                tagChangeEntity.entity == firstPlayerGameId
            } else {
                tagChangeEntity.entity.isNotBlank() && tagChangeEntity.entity != firstPlayerGameId
            }
        }
        return false
    }

}
