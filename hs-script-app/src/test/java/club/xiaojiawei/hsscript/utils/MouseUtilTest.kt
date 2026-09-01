package club.xiaojiawei.hsscript.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MouseUtilTest {

    @Test
    fun `phase transition interruption is an expected input cancellation`() {
        assertTrue(MouseUtil.isExpectedE2eInputCancellation(InterruptedException()))
        assertFalse(MouseUtil.isExpectedE2eInputCancellation(IllegalStateException()))
    }
}
