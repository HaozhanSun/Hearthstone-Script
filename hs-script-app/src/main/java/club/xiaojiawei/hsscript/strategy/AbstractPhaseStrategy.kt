package club.xiaojiawei.hsscript.strategy

import club.xiaojiawei.hsscript.bean.log.Block
import club.xiaojiawei.hsscript.bean.log.ExtraEntity
import club.xiaojiawei.hsscript.bean.log.TagChangeEntity
import club.xiaojiawei.hsscript.consts.*
import club.xiaojiawei.hsscript.enums.BlockTypeEnum
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.interfaces.closer.ThreadCloser
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.status.TaskManager
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.surrender.SurrenderPolicy
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.PowerLogUtil
import club.xiaojiawei.hsscript.utils.PowerLogUtil.dealChangeEntity
import club.xiaojiawei.hsscript.utils.PowerLogUtil.dealFullEntity
import club.xiaojiawei.hsscript.utils.PowerLogUtil.dealShowEntity
import club.xiaojiawei.hsscript.utils.PowerLogUtil.dealTagChange
import club.xiaojiawei.hsscript.utils.PowerLogUtil.isRelevance
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.StepEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscriptbase.interfaces.PhaseStrategy
import club.xiaojiawei.hsscriptbase.util.RandomUtil
import club.xiaojiawei.hsscriptbase.util.isTrue
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import java.io.IOException

/**
 * 游戏阶段抽象类
 * @author 肖嘉威
 * @date 2022/11/26 17:59
 */
abstract class AbstractPhaseStrategy : PhaseStrategy {

    protected val war = WAR

    override fun deal(line: String) {
        dealing = true
        try {
            beforeDeal()
            afterDeal(dealLog(line))
        } finally {
            dealing = false
        }
    }

    private fun dealLog(line: String): Boolean {
        val logFile = PowerLogListener.logFile
        logFile ?: return false
        var l: String? = line
        // A phase handler may consume the tail of the file while it waits for
        // the next state transition. Bound one pass so one malformed or very
        // large power-log burst cannot monopolize the listener forever.
        val deadlineNanos = System.nanoTime() + 2_000_000_000L
        var processedLines = 0
        while (WorkTimeListener.working && processedLines++ < 2_000 && System.nanoTime() < deadlineNanos) {
            try {
                if (l == null) {
                    SystemUtil.delay(RandomUtil.getInteractionDelay(100))
                } else if (isRelevance(l)) {
                    if (log.isDebugEnabled()) {
                        log.debug { l }
                    }
                    if (l.contains(TAG_CHANGE)) {
                        val phaseTransitionDetected = dealTagChangeThenIsOver(l, dealTagChange(l))
                        if (surrenderImmediatelyForResolvedOpponentHero()) return true
                        if (surrenderImmediatelyForCurrentRank()) return true
                        if (phaseTransitionDetected || war.currentTurnStep == StepEnum.FINAL_GAMEOVER) return true
                    } else if (l.contains(SHOW_ENTITY)) {
                        val phaseTransitionDetected = dealShowEntityThenIsOver(l, dealShowEntity(l, logFile))
                        if (surrenderImmediatelyForResolvedOpponentHero()) return true
                        if (surrenderImmediatelyForCurrentRank()) return true
                        if (phaseTransitionDetected) return true
                    } else if (l.contains(FULL_ENTITY)) {
                        val phaseTransitionDetected = dealFullEntityThenIsOver(l, dealFullEntity(l, logFile))
                        if (surrenderImmediatelyForResolvedOpponentHero()) return true
                        if (surrenderImmediatelyForCurrentRank()) return true
                        if (phaseTransitionDetected) return true
                    } else if (l.contains(CHANGE_ENTITY)) {
                        val phaseTransitionDetected = dealChangeEntityThenIsOver(l, dealChangeEntity(l, logFile))
                        if (surrenderImmediatelyForResolvedOpponentHero()) return true
                        if (surrenderImmediatelyForCurrentRank()) return true
                        if (phaseTransitionDetected) return true
                    } else if (l.contains(BLOCK_TYPE) || l.contains(BLOCK_START_NULL)) {
                        val phaseTransitionDetected = dealBlockIsOver(l, PowerLogUtil.dealBlock(l))
                        if (surrenderImmediatelyForResolvedOpponentHero()) return true
                        if (surrenderImmediatelyForCurrentRank()) return true
                        if (phaseTransitionDetected) return true
                    } else if (l.contains(BLOCK_END) || l.contains(BLOCK_END_NULL)) {
                        val phaseTransitionDetected = dealBlockEndIsOver(l, PowerLogUtil.dealBlockEnd(l))
                        if (surrenderImmediatelyForResolvedOpponentHero()) return true
                        if (surrenderImmediatelyForCurrentRank()) return true
                        if (phaseTransitionDetected) return true
                    } else {
                        val phaseTransitionDetected = dealOtherThenIsOver(l)
                        if (surrenderImmediatelyForResolvedOpponentHero()) return true
                        if (surrenderImmediatelyForCurrentRank()) return true
                        if (phaseTransitionDetected) return true
                    }
                }
                l = logFile.readLine()
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }
        return false
    }

    /**
     * The opponent portrait can become known while the initial hand is still
     * on screen.  Handle that event at the common log boundary so every early
     * phase gets the same behavior, canceling a delayed mulligan worker before
     * it can click anything.
     */
    private fun surrenderImmediatelyForResolvedOpponentHero(): Boolean {
        val result = SurrenderPolicy.evaluateOpponentHeroBeforeMulligan(war) ?: return false
        cancelAllTask()
        log.warn {
            "立即投降：对手基础英雄检查失败，跳过剩余换牌流程 " +
                "rule=${result.ruleId} reason=${result.reason ?: "none"}"
        }
        GameUtil.surrender(skipEndTurn = true)
        return true
    }

    private fun surrenderImmediatelyForCurrentRank(): Boolean {
        val result = SurrenderPolicy.evaluateCurrentRankBeforeMulligan() ?: return false
        cancelAllTask()
        if (result.shouldSurrender) {
            log.warn {
                "立即投降：当前排位策略命中，跳过剩余换牌流程 " +
                    "rule=${result.ruleId} reason=${result.reason ?: "none"}"
            }
            GameUtil.surrender(skipEndTurn = true)
        } else {
            log.error {
                "立即投降流程阻断：当前排位无法确认，暂停等待明确OCR证据 " +
                    "rule=${result.ruleId} reason=${result.reason ?: "none"}"
            }
            PauseStatus.isPause = true
        }
        return true
    }

    protected fun beforeDeal() {
        WarPhaseEnum.find(this)?.let {
            log.info { "当前处于：" + it.comment }
        }
    }

    protected fun afterDeal(phaseTransitionDetected: Boolean) {
        WarPhaseEnum.find(this)?.let {
            if (phaseTransitionDetected) {
                log.info { it.comment + " -> 阶段转换已确认" }
            } else {
                log.info { it.comment + "本次日志批处理结束，等待新的阶段事件" }
            }
        }
    }

    protected open fun dealTagChangeThenIsOver(line: String, tagChangeEntity: TagChangeEntity): Boolean {
        return false
    }

    protected open fun dealShowEntityThenIsOver(line: String, extraEntity: ExtraEntity): Boolean {
        return false
    }

    protected open fun dealFullEntityThenIsOver(line: String, extraEntity: ExtraEntity): Boolean {
        return false
    }

    protected open fun dealChangeEntityThenIsOver(line: String, extraEntity: ExtraEntity): Boolean {
        return false
    }

    protected open fun dealBlockIsOver(line: String, block: Block): Boolean {
        return false
    }

    protected open fun dealBlockEndIsOver(line: String, block: Block?): Boolean {
        if (ConfigUtil.getBoolean(ConfigEnum.KILLED_SURRENDER) && !WAR.isMyTurn && block != null) {
            if (block.blockType === BlockTypeEnum.ATTACK || block.blockType === BlockTypeEnum.POWER) {
                GameUtil.triggerReachingMyHeroDeadLine()
            }
        }
        return false
    }

    protected open fun dealOtherThenIsOver(line: String): Boolean {
        return false
    }

    companion object : ThreadCloser {

        init {
            TaskManager.addTask(this)
        }

        var dealing = false
        private val tasks: MutableList<Thread> = mutableListOf()

        fun addTask(task: Thread) {
            tasks.add(task)
        }

        fun cancelAllTask() {
            val toList = tasks.toList()
            tasks.clear()
            toList.forEach {
                it.isAlive.isTrue {
                    it.interrupt()
                }
            }
        }

        override fun stopAll() {
            cancelAllTask()
        }
    }

}
