package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.strategy.AbstractModeStrategy
import club.xiaojiawei.hsscript.utils.go
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 游戏当前模式（界面）
 * @author 肖嘉威
 * @date 2022/11/25 0:09
 */
object Mode {

    data class ModeStruct(
        var currMode: ModeEnum? = null,
        var newMode: ModeEnum? = null,
        var enterStrategy: Boolean = true,
    )

    private val modeQueue = ArrayBlockingQueue<ModeStruct>(5)

    private var nextModeTimeoutTask: Future<*>? = null

    init {
        WorkTimeListener.addWorkStatusListener { _, oldValue, newValue ->
            if (!newValue) {
                stopTask()
            }
        }
        go {
            while (true) {
                val (currMode1, newMode, enterStrategy) = modeQueue.take()
                runCatching {
                    AbstractModeStrategy.cancelAllTask()
                }.onFailure {
                    log.error(it) { "" }
                }
                go {
                    currMode1?.modeStrategy?.afterLeave()
                    AbstractModeStrategy.cancelAllTask()
                    if (enterStrategy) {
                        newMode?.modeStrategy?.entering()
                    } else if (newMode != null) {
                        log.info { "恢复到【${newMode.comment}】，跳过该界面的重复入口动作" }
                    }
                }
            }
        }
    }

    private fun stopTask() {
        nextModeTimeoutTask?.let {
            it.cancel(true)
            nextModeTimeoutTask = null
        }
    }

    @Volatile
    var nextMode: ModeEnum? = null
        set(value) {
            if (value == field) return
            stopTask()
            field = value
            if (value == null) return
            log.info { "准备进入【${value.comment}】" }
            nextModeTimeoutTask = EXTRA_THREAD_POOL.schedule({
                if (currMode != value) {
                    log.warn { "日志长时间未打印已进入${value.comment}，默认已经进入" }
                    currMode = value
                }
            }, 5, TimeUnit.SECONDS)
        }

    @Volatile
    private var currModeValue: ModeEnum? = null

    var currMode: ModeEnum?
        get() = currModeValue
        set(value) {
            if (value === currModeValue) return
            stopTask()
            modeQueue.add(ModeStruct(currModeValue, value, true))
            prevMode = currModeValue
            currModeValue = value
        }

    @Volatile
    var prevMode: ModeEnum? = null

    /**
     * Set the parser's current screen after an independent visual check.
     * Some screens (notably deck selection) are already part-way through a
     * normal mode entry flow. Re-running the mode strategy there would click
     * controls for an earlier screen, so recovery can update the state while
     * deliberately skipping that strategy's entry clicks.
     */
    fun recover(value: ModeEnum, reason: String, enterStrategy: Boolean = false) {
        stopTask()
        if (value === currMode) {
            AbstractModeStrategy.cancelAllTask()
            log.info { "屏幕恢复确认：当前已经是【${value.comment}】 reason=$reason" }
            return
        }
        modeQueue.add(ModeStruct(currModeValue, value, enterStrategy))
        prevMode = currModeValue
        currModeValue = value
        nextMode = null
        log.warn { "屏幕恢复：${prevMode?.comment ?: "未知"} -> ${value.comment} reason=$reason" }
    }

    fun reset() {
        currMode?.let {
            currMode = null
            nextMode = null
            prevMode = null
            log.info { "已重置模式状态" }
        }
    }
}
