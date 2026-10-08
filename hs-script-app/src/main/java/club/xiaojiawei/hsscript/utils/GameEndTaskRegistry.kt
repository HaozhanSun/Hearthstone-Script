package club.xiaojiawei.hsscript.utils

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture

/** Tracks end-of-game workers while keeping a worker's own stop path scoped to itself. */
internal class GameEndTaskRegistry {
    enum class Kind { SURRENDER_RECOVERY, TERMINAL_PAGE }

    private data class Entry(val task: ScheduledFuture<*>, val kind: Kind)

    private val tasks = CopyOnWriteArrayList<Entry>()

    fun add(task: ScheduledFuture<*>, kind: Kind = Kind.SURRENDER_RECOVERY) {
        tasks.add(Entry(task, kind))
    }

    fun remove(task: ScheduledFuture<*>) {
        tasks.removeIf { it.task == task }
    }

    fun cancel(task: ScheduledFuture<*>, mayInterruptIfRunning: Boolean = true) {
        task.cancel(mayInterruptIfRunning)
        remove(task)
    }

    fun cancelAll() {
        tasks.forEach { entry ->
            if (!entry.task.isDone) entry.task.cancel(true)
            tasks.remove(entry)
        }
    }

    /** A recognized safe destination ends result cleanup without cancelling unrelated recovery work. */
    fun cancelKind(kind: Kind) {
        tasks.filter { it.kind == kind }.forEach { entry ->
            if (!entry.task.isDone) entry.task.cancel(true)
            tasks.remove(entry)
        }
    }

    fun isNotEmpty(): Boolean = tasks.isNotEmpty()

    /** Only result-page workers prove a terminal UI; an in-flight surrender retry does not. */
    fun hasTerminalPageTask(): Boolean = tasks.any { it.kind == Kind.TERMINAL_PAGE && !it.task.isDone }

    fun hasSurrenderRecoveryTask(): Boolean =
        tasks.any { it.kind == Kind.SURRENDER_RECOVERY && !it.task.isDone }
}
