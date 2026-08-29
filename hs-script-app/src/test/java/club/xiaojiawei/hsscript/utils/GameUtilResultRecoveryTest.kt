package club.xiaojiawei.hsscript.utils

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameUtilResultRecoveryTest {

    @Test
    fun usesStableCenterOnlyForFirstBoundedRecoveryAttempt() {
        assertTrue(GameUtil.shouldUseStaleResultCenterClick(1))
        assertFalse(GameUtil.shouldUseStaleResultCenterClick(0))
        assertFalse(GameUtil.shouldUseStaleResultCenterClick(2))
        assertFalse(GameUtil.shouldUseStaleResultCenterClick(5))
    }
}
