package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.status.ResultPageDismissalPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GameUtilResultRecoveryTest {

    @Test
    fun usesUpstreamUnityDismissalSequenceWithinTheRetryBudget() {
        assertEquals(ResultPageDismissalPolicy.Input.CENTER_CLICK, GameUtil.staleResultInputForAttempt(1))
        assertEquals(ResultPageDismissalPolicy.Input.KEYBOARD_ENTER, GameUtil.staleResultInputForAttempt(2))
        assertEquals(ResultPageDismissalPolicy.Input.RETRY_CLICK, GameUtil.staleResultInputForAttempt(3))
        assertEquals(ResultPageDismissalPolicy.Input.RETRY_CLICK, GameUtil.staleResultInputForAttempt(5))
        assertEquals(ResultPageDismissalPolicy.Input.CENTER_CLICK, GameUtil.terminalResultInputForAttempt(3))
        assertEquals(ResultPageDismissalPolicy.Input.KEYBOARD_ENTER, GameUtil.terminalResultInputForAttempt(4))
        assertEquals(ResultPageDismissalPolicy.Input.CENTER_CLICK, GameUtil.terminalResultInputForAttempt(9))
        assertEquals(null, GameUtil.terminalResultInputForAttempt(17))
        assertEquals(null, GameUtil.staleResultInputForAttempt(0))
        assertEquals(null, GameUtil.staleResultInputForAttempt(6))
    }
}
