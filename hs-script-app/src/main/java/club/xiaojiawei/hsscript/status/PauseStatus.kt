package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.value.ChangeListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 脚本暂停状态
 * @author 肖嘉威
 * @date 2023/7/5 15:04
 */
object PauseStatus {

    enum class Origin {
        NONE,
        MANUAL,
        AUTOMATIC,
    }

    private val isPauseProperty: ReadOnlyBooleanWrapper = ReadOnlyBooleanWrapper(true)
    private val pauseState = AtomicBoolean(true)

    @Volatile
    private var origin: Origin = Origin.AUTOMATIC

    var isPause: Boolean
        get() {
            return pauseState.get()
        }
        set(value) {
            if (value && origin != Origin.MANUAL) {
                origin = Origin.AUTOMATIC
            } else if (!value) {
                origin = Origin.NONE
            }
            pauseState.set(value)
            isPauseProperty.set(value)
        }

    val isAutomaticPause: Boolean
        get() = isPause && origin == Origin.AUTOMATIC

    val pauseOrigin: Origin
        get() = origin

    /** User controls (F2, tray, or the main window) must remain authoritative. */
    fun setManualPause(paused: Boolean) {
        origin = if (paused) Origin.MANUAL else Origin.NONE
        pauseState.set(paused)
        isPauseProperty.set(paused)
    }

    /** Safety stops may be recovered by the screen watchdog after a known UI repair. */
    fun setAutomaticPause(paused: Boolean) {
        origin = if (paused) Origin.AUTOMATIC else Origin.NONE
        pauseState.set(paused)
        isPauseProperty.set(paused)
    }

    fun canRunAutomaticRecovery(): Boolean = !isPause || isAutomaticPause

    fun resumeAutomaticPause(reason: String): Boolean {
        if (!isAutomaticPause) return false
        setAutomaticPause(false)
        return true
    }

    val isStart
        get() = !pauseState.get()

    fun setPauseReturn(isPaused: Boolean): Boolean {
        setManualPause(isPaused)
        return isPause
    }

    fun asyncSetPause(isPaused: Boolean) {
        EXTRA_THREAD_POOL.submit {
            this.isPause = isPaused
        }
    }

    fun asyncSetManualPause(isPaused: Boolean) {
        EXTRA_THREAD_POOL.submit {
            setManualPause(isPaused)
        }
    }

    fun addChangeListener(listener: ChangeListener<Boolean>) {
        isPauseProperty.addListener(listener)
    }

    fun removeChangeListener(listener: ChangeListener<Boolean>) {
        isPauseProperty.removeListener(listener)
    }

}
