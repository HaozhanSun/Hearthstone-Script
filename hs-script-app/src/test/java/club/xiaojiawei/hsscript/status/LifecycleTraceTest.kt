package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertEquals

class LifecycleTraceTest {

    @Test
    fun automaticPauseStartupRemainsObservableButManualPauseDoesNot() {
        assertEquals(
            true,
            LifecycleTrace.shouldObserveNoProgress(
                working = false,
                automaticPause = true,
                replaying = false,
                paused = true,
                recoveryPending = false,
            ),
        )
        assertEquals(
            false,
            LifecycleTrace.shouldObserveNoProgress(
                working = false,
                automaticPause = false,
                replaying = false,
                paused = true,
                recoveryPending = false,
            ),
        )
    }

    @Test
    fun startupRecoveryGraceDefersRecoveryOnlyDuringTheGraceWindow() {
        val now = 1_000_000L

        LifecycleTrace.markStartupRequested("offline-test", now)

        assertEquals(3_000L, LifecycleTrace.startupRecoveryGraceRemainingMs(now))
        assertEquals(0L, LifecycleTrace.startupRecoveryGraceRemainingMs(now + 3_000L))
        assertEquals(0L, LifecycleTrace.startupRecoveryGraceRemainingMs(now + 30_000L))
    }
}
