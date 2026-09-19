package club.xiaojiawei.hsscript.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class E2EInputRecoveryPolicyTest {

    @Test
    fun confirmedForegroundSendsImmediately() {
        assertEquals(
            E2EInputRecoveryPolicy.Decision.SEND,
            E2EInputRecoveryPolicy.decide(0, windowValid = true, foregroundMatches = true),
        )
    }

    @Test
    fun mismatchRetriesBeforeTheBound() {
        assertEquals(
            E2EInputRecoveryPolicy.Decision.RETRY,
            E2EInputRecoveryPolicy.decide(0, windowValid = true, foregroundMatches = false),
        )
        assertEquals(
            E2EInputRecoveryPolicy.Decision.BLOCK,
            E2EInputRecoveryPolicy.decide(
                E2EInputRecoveryPolicy.MAX_FOREGROUND_ATTEMPTS - 1,
                windowValid = true,
                foregroundMatches = false,
            ),
        )
    }

    @Test
    fun invalidWindowIsAlsoBounded() {
        assertEquals(
            E2EInputRecoveryPolicy.Decision.RETRY,
            E2EInputRecoveryPolicy.decide(1, windowValid = false, foregroundMatches = false),
        )
        assertEquals(
            E2EInputRecoveryPolicy.Decision.BLOCK,
            E2EInputRecoveryPolicy.decide(
                E2EInputRecoveryPolicy.MAX_FOREGROUND_ATTEMPTS - 1,
                windowValid = false,
                foregroundMatches = false,
            ),
        )
    }
}
