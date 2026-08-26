package club.xiaojiawei.hsscript.strategy

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscript.interfaces.closer.ScheduledCloser
import club.xiaojiawei.hsscript.status.Mode
import club.xiaojiawei.hsscript.status.TaskManager
import club.xiaojiawei.hsscriptbase.interfaces.ModeStrategy
import club.xiaojiawei.hsscriptbase.util.RandomUtil
import club.xiaojiawei.hsscriptbase.util.isFalse
import java.util.*
import java.util.concurrent.ScheduledFuture

/**
 * 游戏模式抽象类
 * @author 肖嘉威
 * @date 2022/11/26 17:39
 */
abstract class AbstractModeStrategy<T> : ModeStrategy<T> {

    override fun entering() {
        entering(null)
    }

    override fun entering(t: T?) {
        beforeEnter()
        log.info { "切换到【${Mode.currMode?.comment}】" }
        synchronized(AbstractModeStrategy::class.java) {
            afterEnter(t)
        }
    }

    override fun afterLeave() {
        cancelAllEnteredTasks()
    }

    protected abstract fun afterEnter(t: T?)

    private fun beforeEnter() {
        cancelAllWantEnterTasks()
    }

    protected fun addWantEnterTask(task: ScheduledFuture<*>): ScheduledFuture<*> {
        wantEnterTasks.add(task)
        return task
    }

    protected fun cancelWantEnterTask(task: ScheduledFuture<*>) {
        task.isDone.isFalse {
            wantEnterTasks.remove(task)
            task.cancel(true)
        }
    }

    protected fun addEnteredTask(task: ScheduledFuture<*>): ScheduledFuture<*> {
        enteredTasks.add(task)
        return task
    }

    protected fun cancelEnteredTask(task: ScheduledFuture<*>) {
        task.isDone.isFalse {
            enteredTasks.remove(task)
            task.cancel(true)
        }
    }


    companion object:ScheduledCloser {

        init {
            TaskManager.addTask(this)
        }

        fun randomizedModeEntryDelay(): Long = RandomUtil.getModeEntryDelay()

        fun randomizedModeEntryInterval(): Long = RandomUtil.getModeEntryInterval()

        /**
         * Randomized schedule values for mode-transition cleanup actions.
         * A zero legacy delay becomes a short human pause instead of a fixed
         * immediate click; positive values retain their old center point.
         */
        fun randomizedTaskDelay(centerMillis: Long): Long {
            if (centerMillis <= 0L) return RandomUtil.getRandom(100, 500).toLong()
            return RandomUtil.getInteractionDelay(centerMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).toLong()
        }

        fun randomizedTaskInterval(centerMillis: Long): Long =
            randomizedTaskDelay(centerMillis)

        private var wantEnterTasks: MutableList<ScheduledFuture<*>> = Collections.synchronizedList(mutableListOf())
        private var enteredTasks: MutableList<ScheduledFuture<*>> = Collections.synchronizedList(mutableListOf())

        fun cancelAllEnteredTasks() {
            val listOf = enteredTasks.toList()
            enteredTasks.clear()
            listOf.forEach {
                it.isDone.isFalse {
                    it.cancel(true)
                }
            }
        }

        fun cancelAllWantEnterTasks() {
            val listOf = wantEnterTasks.toList()
            wantEnterTasks.clear()
            listOf.forEach {
                it.isDone.isFalse {
                    it.cancel(true)
                }
            }
        }

        fun cancelAllTask() {
            cancelAllEnteredTasks()
            cancelAllWantEnterTasks()
        }

        override fun stopAll() {
            cancelAllTask()
        }

    }

}
