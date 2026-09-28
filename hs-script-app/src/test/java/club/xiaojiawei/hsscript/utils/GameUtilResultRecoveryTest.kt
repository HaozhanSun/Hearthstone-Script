package club.xiaojiawei.hsscript.utils

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameUtilResultRecoveryTest {

    @Test
    fun keepsEveryBoundedRecoveryAttemptOnTheStableContinueTarget() {
        assertTrue(GameUtil.shouldUseStaleResultCenterClick(1))
        assertTrue(GameUtil.shouldUseStaleResultCenterClick(2))
        assertTrue(GameUtil.shouldUseStaleResultCenterClick(3))
        assertTrue(GameUtil.shouldUseStaleResultCenterClick(4))
        assertTrue(GameUtil.shouldUseStaleResultCenterClick(5))
        assertFalse(GameUtil.shouldUseStaleResultCenterClick(0))
        assertFalse(GameUtil.shouldUseStaleResultCenterClick(6))
    }
}
