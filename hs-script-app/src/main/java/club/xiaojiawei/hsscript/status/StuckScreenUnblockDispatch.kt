package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.util.concurrent.atomic.AtomicBoolean

/** Injectable dispatch boundary shared by the idle ladder runtime and its callsite regression. */
internal object StuckScreenUnblockDispatch {
    fun dispatchTopLeftBatch(
        batchInFlight: AtomicBoolean,
        action: StuckScreenUnblockPolicy.Action,
        firstClick: Int,
        freshCaptureAuthorized: Boolean,
        point: Point?,
        expectedFingerprint: String,
        completionTimeMs: () -> Long,
        dispatchClick: (Point, Int) -> Boolean,
        completeBatch: (String, Long) -> Boolean,
        requestFreshRecheck: () -> Unit,
    ): Boolean {
        if (action != StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT || !freshCaptureAuthorized ||
            point == null || expectedFingerprint.isBlank() ||
            firstClick !in 1..StuckScreenUnblockPolicy.MAX_TOP_LEFT_CLICKS
        ) return false

        if (!batchInFlight.compareAndSet(false, true)) return false
        try {
            return dispatchAuthorizedTopLeftBatch(
                action, firstClick, point, expectedFingerprint, completionTimeMs,
                dispatchClick, completeBatch, requestFreshRecheck,
            )
        } finally {
            batchInFlight.set(false)
        }
    }

    private fun dispatchAuthorizedTopLeftBatch(
        action: StuckScreenUnblockPolicy.Action,
        firstClick: Int,
        point: Point,
        expectedFingerprint: String,
        completionTimeMs: () -> Long,
        dispatchClick: (Point, Int) -> Boolean,
        completeBatch: (String, Long) -> Boolean,
        requestFreshRecheck: () -> Unit,
    ): Boolean {
        if (action != StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT) return false

        for (clickNumber in firstClick..StuckScreenUnblockPolicy.MAX_TOP_LEFT_CLICKS) {
            if (!dispatchClick(point, clickNumber)) return false
        }
        if (!completeBatch(expectedFingerprint, completionTimeMs())) return false
        requestFreshRecheck()
        return true
    }

    fun dispatchRestartIfAuthorized(
        action: StuckScreenUnblockPolicy.Action,
        finalSafetyPassed: Boolean,
        restart: () -> Unit,
    ): Boolean {
        if (action != StuckScreenUnblockPolicy.Action.RESTART_CLIENT || !finalSafetyPassed) return false
        restart()
        return true
    }
}
