package club.xiaojiawei.hsscript.strategy.phase

import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import java.util.concurrent.atomic.AtomicBoolean

/** Reserves the one normal mulligan action allowed for a live INPUT event. */
internal class MulliganActionGate {
    private val reserved = AtomicBoolean(false)

    /** Reserve only after the current game's verified rank opens the barrier. */
    fun tryReserve(): Boolean =
        MulliganRankDispatchBarrier.currentState() == MulliganRankDispatchBarrier.State.ELIGIBLE &&
            reserved.compareAndSet(false, true)

    fun tryReserve(isEligible: () -> Boolean): Boolean =
        isEligible() && tryReserve()

    fun reset() {
        reserved.set(false)
    }
}
