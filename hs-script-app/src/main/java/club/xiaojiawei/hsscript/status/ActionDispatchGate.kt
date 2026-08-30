package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.config.log

/** Last-moment, process-wide gate for state-changing game actions. */
object ActionDispatchGate {

    fun allow(action: String): Boolean {
        if (!PauseStatus.isPause) return true
        log.warn { "ACTION_BLOCKED action=$action reason=paused" }
        return false
    }
}
