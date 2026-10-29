package club.xiaojiawei.hsscript.strategy.mode

import java.util.concurrent.atomic.AtomicBoolean

/** Allows one OCR-heavy matchmaking error-dialog recovery task per app process. */
class MatchmakingDialogRecoveryTaskGate {
    private val owned = AtomicBoolean(false)

    fun tryAcquire(): Boolean = owned.compareAndSet(false, true)

    fun release() {
        owned.set(false)
    }

    fun isOwned(): Boolean = owned.get()
}
