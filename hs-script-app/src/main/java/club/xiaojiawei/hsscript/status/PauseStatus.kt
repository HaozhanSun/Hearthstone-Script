package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
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
            if (value) {
                // Legacy callers historically assigned this property from
                // recovery and timeout paths. A running script must now only
                // enter Pause through setManualPause (F2 or the UI), never as
                // a side effect of an uncertain observation.
                if (!pauseState.get()) {
                    log.warn {
                        "AUTOMATION_PAUSE_SUPPRESSED source=legacy-direct-setter " +
                            "reason=manual-pause-required pause=false"
                    }
                }
                return
            }
            if (!value) {
                origin = Origin.NONE
            }
            pauseState.set(value)
            isPauseProperty.set(value)
        }

    val isAutomaticPause: Boolean
        get() = isPause && origin == Origin.AUTOMATIC

    val pauseOrigin: Origin
        get() = origin

    /** The fixed F2 hotkey is the only production path that may pause an active run. */
    internal fun pauseFromF2() {
        origin = Origin.MANUAL
        pauseState.set(true)
        isPauseProperty.set(true)
    }

    /** Starting or resuming from F1, the main UI, or tray is allowed. */
    internal fun resumeFromUserControl() {
        origin = Origin.NONE
        pauseState.set(false)
        isPauseProperty.set(false)
    }

    /**
     * Non-hotkey UI controls are intentionally unable to pause an active run.
     * Keeping this decision at the state boundary prevents a future handler
     * from reintroducing a second pause authority.
     */
    internal fun suppressNonF2Pause(source: String): Boolean {
        log.warn {
            "UI_PAUSE_SUPPRESSED source=$source reason=f2-only " +
                "pause=${pauseState.get()} working-run-remains-active"
        }
        return false
    }

    /** Test-only state setup; production code must use F2 or a resume path. */
    internal fun setManualPauseForTest(paused: Boolean) {
        if (paused) pauseFromF2() else resumeFromUserControl()
    }

    /**
     * Record an automation safety condition without stopping an active run.
     *
     * F2/the UI call [setManualPause] and remain the only paths that may
     * transition an active script to Pause. A condition observed before a
     * user starts is already paused and therefore stays inert.
     */
    fun setAutomaticPause(paused: Boolean): Boolean {
        if (paused && !pauseState.get()) {
            log.warn {
                "AUTOMATION_PAUSE_SUPPRESSED source=automatic-request " +
                    "reason=manual-pause-required pause=false"
            }
            return false
        }
        if (paused && origin == Origin.MANUAL) return false
        origin = if (paused) Origin.AUTOMATIC else Origin.NONE
        pauseState.set(paused)
        isPauseProperty.set(paused)
        return true
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
        if (isPaused) suppressNonF2Pause("legacy-setPauseReturn") else resumeFromUserControl()
        return isPause
    }

    fun asyncSetPause(isPaused: Boolean) {
        EXTRA_THREAD_POOL.submit {
            setAutomaticPause(isPaused)
        }
    }

    internal fun asyncResumeFromUserControl() {
        EXTRA_THREAD_POOL.submit {
            resumeFromUserControl()
        }
    }

    fun addChangeListener(listener: ChangeListener<Boolean>) {
        isPauseProperty.addListener(listener)
    }

    fun removeChangeListener(listener: ChangeListener<Boolean>) {
        isPauseProperty.removeListener(listener)
    }

}
