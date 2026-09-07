package club.xiaojiawei.hsscript.strategy.phase

import java.util.concurrent.atomic.AtomicBoolean

/** Reserves the one normal mulligan action allowed for a live INPUT event. */
internal class MulliganActionGate {
    private val reserved = AtomicBoolean(false)

    /** Reserve the normal mulligan action without consulting rank/OCR state. */
    fun tryReserve(): Boolean = reserved.compareAndSet(false, true)

    fun tryReserve(isEligible: () -> Boolean): Boolean =
        isEligible() && reserved.compareAndSet(false, true)

    fun reset() {
        reserved.set(false)
    }
}
