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
import club.xiaojiawei.hsscript.status.E2ETrace
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.MulliganScreenshot
import club.xiaojiawei.hsscriptbase.enums.StepEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.kt.config.log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 换牌阶段
 *
 * @author 肖嘉威
 * @date 2022/11/26 17:24
 */
object ReplaceCardPhaseStrategy : AbstractPhaseStrategy() {

    private val changeCardScheduled = AtomicBoolean(false)
    private val mulliganStageConfirmed = AtomicBoolean(false)
    private val mulliganInputConfirmed = AtomicBoolean(false)

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
        changeCardScheduled.set(false)
        mulliganStageConfirmed.set(false)
        mulliganInputConfirmed.set(false)
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

        if (PowerLogListener.replayingExistingLog) {
            replayedMulliganInput = tagChangeEntity
            log.info { "E2E恢复回放：跳过历史换牌点击，等待实时日志继续" }
            return
        }

        // This is the first authoritative event that proves the local
        // mulligan UI exists. Rank OCR is not allowed before this boundary.
        mulliganInputConfirmed.set(true)
        val rankDecision = SurrenderPolicy.evaluateCurrentRankBeforeMulligan()
        if (rankDecision != null) {
            dispatchSurrenderDecision(rankDecision, "mulligan-input-preflight")
            return
        }
        if (System.getProperty("hs.script.e2e.skip-surrender-policy") != "true" &&
            SurrenderPolicy.currentRankInspectionState() !==
            club.xiaojiawei.hsscript.status.surrender.RankInspectionState.RESOLVED
        ) {
            log.warn {
                "MULLIGAN_ACTION_WAITING_FOR_RANK state=${SurrenderPolicy.currentRankInspectionState()} " +
                    "action=WAIT dispatch=false"
            }
            return
        }

        val scheduled = changeCardScheduled.compareAndSet(false, true)
        log.info {
            "收到换牌输入：${tagChangeEntity.entity}，自动换牌线程调度结果：$scheduled"
        }
        if (!scheduled) return

        cancelAllTask()
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
            } else if (state === MulliganStateEnum.DONE &&
                mulliganStageConfirmed.compareAndSet(false, true)
            ) {
                log.info { "换牌阶段确认完成：当前玩家MULLIGAN_STATE=DONE" }
                MulliganScreenshot.capture("post-confirm", WarEx.warCount + 1)
                E2ETrace.markMulliganCompleted()
            }
        } else if (tagChangeEntity.tag == TagEnum.NEXT_STEP && StepEnum.MAIN_READY.name == tagChangeEntity.value) {
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
