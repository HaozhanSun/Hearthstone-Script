package club.xiaojiawei.hsscript.utils

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
    fun `robot revalidates exact foreground immediately before press`() {
        assertTrue(
            MouseUtil.shouldDispatchE2ERobotInputAtPress(
                targetHandle = 0x100L,
                foregroundHandle = 0x100L,
                targetVisible = true,
                foregroundVisible = true,
                workerInterrupted = false,
                actionAllowed = true,
            ),
        )
        assertFalse(
            MouseUtil.shouldDispatchE2ERobotInputAtPress(
                targetHandle = 0x100L,
                foregroundHandle = 0x200L,
                targetVisible = true,
                foregroundVisible = true,
                workerInterrupted = false,
                actionAllowed = true,
            ),
        )
        assertFalse(
            MouseUtil.shouldDispatchE2ERobotInputAtPress(
                targetHandle = 0x100L,
                foregroundHandle = 0x100L,
                targetVisible = true,
                foregroundVisible = true,
                workerInterrupted = false,
                actionAllowed = false,
            ),
        )
    }

    @Test
    fun `post input foreground cannot prove Hearthstone accepted the action`() {
        val dispatchedToTarget = MouseUtil.shouldDispatchE2ERobotInputAtPress(
            targetHandle = 0x100L,
            foregroundHandle = 0x100L,
            targetVisible = true,
            foregroundVisible = true,
            workerInterrupted = false,
            actionAllowed = true,
        )
        val acceptedAfterward = MouseUtil.e2eRobotForegroundRemainedTarget(
            targetHandle = 0x100L,
            foregroundHandle = 0x200L,
            targetVisible = true,
            foregroundVisible = true,
        )
        assertTrue(dispatchedToTarget)
        assertFalse(acceptedAfterward)
    }
}
