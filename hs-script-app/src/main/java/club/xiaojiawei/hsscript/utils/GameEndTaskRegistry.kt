package club.xiaojiawei.hsscript.utils

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture

/** Tracks end-of-game workers while keeping a worker's own stop path scoped to itself. */
internal class GameEndTaskRegistry {
    private val tasks = CopyOnWriteArrayList<ScheduledFuture<*>>()

    fun add(task: ScheduledFuture<*>) {
        tasks.add(task)
    }

    fun remove(task: ScheduledFuture<*>) {
        tasks.remove(task)
    }

    fun cancel(task: ScheduledFuture<*>, mayInterruptIfRunning: Boolean = true) {
        task.cancel(mayInterruptIfRunning)
        tasks.remove(task)
    }

    fun cancelAll() {
        tasks.forEach { task ->
            if (!task.isDone) task.cancel(true)
            tasks.remove(task)
        }
    }

    fun isNotEmpty(): Boolean = tasks.isNotEmpty()
}
