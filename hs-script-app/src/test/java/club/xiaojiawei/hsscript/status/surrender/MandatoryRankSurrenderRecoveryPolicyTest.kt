package club.xiaojiawei.hsscript.status.surrender

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MandatoryRankSurrenderRecoveryPolicyTest {
    @Test
    fun `uncertain mandatory rank surrender must stay in recovery`() {
        assertTrue(MandatoryRankSurrenderRecoveryPolicy.shouldWaitForMoreEvidence(true, false))
    }

    @Test
    fun `ordinary strategy surrender retains existing recovery behavior`() {
        assertFalse(MandatoryRankSurrenderRecoveryPolicy.shouldWaitForMoreEvidence(false, false))
        assertFalse(MandatoryRankSurrenderRecoveryPolicy.shouldWaitForMoreEvidence(true, true))
    }
}
