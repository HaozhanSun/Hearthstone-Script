package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import javafx.application.Platform
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.value.ChangeListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 脚本暂停状态
 * @author 肖嘉威
 * @date 2023/7/5 15:04
 */
object PauseStatus {

    private val isPauseProperty: ReadOnlyBooleanWrapper = ReadOnlyBooleanWrapper(true)
    private val pauseState = AtomicBoolean(true)

    var isPause: Boolean
        get() = pauseState.get()
        set(value) = setPauseInternal(value)

    val isStart
        get() = !pauseState.get()

    /** The atomic value is the immediate source of truth for action gates. */
    private fun setPauseInternal(value: Boolean) {
        val changed = pauseState.getAndSet(value) != value
        if (!changed && isPauseProperty.get() == value) return
        val update = Runnable { isPauseProperty.set(value) }
        if (runCatching { Platform.isFxApplicationThread() }.getOrDefault(false)) {
            update.run()
        } else {
            runCatching { Platform.runLater(update) }
                .onFailure { update.run() }
        }
    }

    fun setPauseReturn(isPaused: Boolean): Boolean {
        isPause = isPaused
        return isPause
    }

    fun asyncSetPause(isPaused: Boolean) {
        EXTRA_THREAD_POOL.submit {
            this.isPause = isPaused
        }
    }

    fun addChangeListener(listener: ChangeListener<Boolean>) {
        isPauseProperty.addListener(listener)
    }

    fun removeChangeListener(listener: ChangeListener<Boolean>) {
        isPauseProperty.removeListener(listener)
    }

}
