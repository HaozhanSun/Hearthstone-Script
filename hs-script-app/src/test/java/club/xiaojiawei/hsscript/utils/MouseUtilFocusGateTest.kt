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
}
