package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class ActionDispatchGateTest {

    @Test
    fun queuedWorkerIsRejectedAfterPauseAndResumeIsExplicit() {
        try {
            PauseStatus.isPause = false
            assertTrue(ActionDispatchGate.allow("test-before-pause"))

            PauseStatus.isPause = true
            assertFalse(ActionDispatchGate.allow("test-queued-action"))

            PauseStatus.isPause = false
            assertTrue(ActionDispatchGate.allow("test-after-resume"))
        } finally {
            PauseStatus.isPause = true
        }
    }

    @Test
    fun concurrentWorkerIsRejectedAtDispatchAfterF2() {
        val queued = CountDownLatch(1)
        val dispatch = CountDownLatch(1)
        val dispatched = AtomicBoolean(false)
        val worker = Thread {
            queued.countDown()
            dispatch.await()
            if (ActionDispatchGate.allow("test-concurrent-action")) {
                dispatched.set(true)
            }
        }
        try {
            PauseStatus.isPause = false
            worker.start()
            queued.await()
            PauseStatus.isPause = true
            dispatch.countDown()
            worker.join(2_000)
            assertFalse(dispatched.get())
        } finally {
            dispatch.countDown()
            if (worker.isAlive) worker.join(2_000)
            PauseStatus.isPause = true
        }
    }
}
