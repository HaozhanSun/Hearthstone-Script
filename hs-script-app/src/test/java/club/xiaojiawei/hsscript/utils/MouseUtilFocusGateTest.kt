package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.ActionDispatchGate
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.StartupQuestOverlayDismissAuthorization
import club.xiaojiawei.hsscript.status.StartupQuestOverlayDismissPolicy
import club.xiaojiawei.hsscript.status.StartupQuestOverlayRecoveryFence
import java.awt.Point
import kotlin.test.assertNotNull
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MouseUtilFocusGateTest {

    @Test
    fun `robot input requires confirmed foreground and live worker`() {
        assertTrue(MouseUtil.shouldDispatchE2ERobotInput(foregroundConfirmed = true, workerInterrupted = false))
        assertFalse(MouseUtil.shouldDispatchE2ERobotInput(foregroundConfirmed = false, workerInterrupted = false))
        assertFalse(MouseUtil.shouldDispatchE2ERobotInput(foregroundConfirmed = true, workerInterrupted = true))
    }

    @Test
    fun `special overlay consumer accepts one exact permit and rejects reuse or generic input`() {
        val wasWorking = WorkTimeListener.working
        val wasPaused = PauseStatus.isPause
        val wasAutomaticPause = PauseStatus.isAutomaticPause
        val point = Point(8, 8)
        try {
            WorkTimeListener.working = true
            PauseStatus.setAutomaticPause(false)
            StartupQuestOverlayRecoveryFence.begin()
            val permit = assertNotNull(
                StartupQuestOverlayDismissAuthorization.permits.issue(44L, "window-55", point, 1_000L),
            )
            var sent = 0
            assertTrue(
                MouseUtil.dispatchStartupQuestOverlayRecoveryClick(
                    permit = permit,
                    pid = 44L,
                    window = "window-55",
                    point = point,
                    nowMs = 1_001L,
                ) {
                    assertTrue(ActionDispatchGate.allow(StartupQuestOverlayDismissPolicy.DISPATCH_ACTION))
                    assertFalse(ActionDispatchGate.allow("mouse.left"))
                    sent++
                    true
                },
            )
            assertTrue(sent == 1)
            assertFalse(
                MouseUtil.dispatchStartupQuestOverlayRecoveryClick(
                    permit = permit,
                    pid = 44L,
                    window = "window-55",
                    point = point,
                    nowMs = 1_002L,
                ) { sent++; true },
                "the one-use permit must reject a second native click",
            )
            assertTrue(sent == 1)
            assertFalse(
                MouseUtil.dispatchStartupQuestOverlayRecoveryClick(
                    permit = null,
                    pid = 44L,
                    window = "window-55",
                    point = point,
                    nowMs = 1_003L,
                ) { sent++; true },
            )
        } finally {
            StartupQuestOverlayRecoveryFence.resetForTest()
            StartupQuestOverlayDismissAuthorization.permits.revoke()
            WorkTimeListener.working = wasWorking
            when {
                wasPaused && wasAutomaticPause -> PauseStatus.setAutomaticPause(true)
                wasPaused -> PauseStatus.setManualPause(true)
                else -> PauseStatus.setAutomaticPause(false)
            }
        }
    }
}
