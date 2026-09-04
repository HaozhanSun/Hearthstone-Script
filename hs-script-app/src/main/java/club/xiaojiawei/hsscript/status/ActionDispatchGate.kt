package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscriptbase.config.log

/**
 * The last, process-wide gate before an action can reach the desktop.  Phase
 * checks are useful for deciding what to do, but they are not sufficient when
 * a queued worker races F2.  Every central mouse/surrender path calls this
 * gate immediately before dispatch and again after acquiring its input lock.
 */
object ActionDispatchGate {

    fun allow(action: String): Boolean {
        val paused = PauseStatus.isPause
        val working = WorkTimeListener.working
        if (paused || !working) {
            log.warn {
                "ACTION_BLOCKED action=$action reason=${if (paused) "paused" else "not-working"} " +
                    "pause=$paused working=$working dispatch=false"
            }
            return false
        }
        return true
    }

    internal fun allowedForState(paused: Boolean, working: Boolean): Boolean = !paused && working
}
