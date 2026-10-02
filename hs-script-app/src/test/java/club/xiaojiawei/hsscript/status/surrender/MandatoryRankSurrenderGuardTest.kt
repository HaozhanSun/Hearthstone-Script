package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
import club.xiaojiawei.hsscript.status.PauseStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MandatoryRankSurrenderGuardTest {
    @AfterEach
    fun cleanup() = MandatoryRankSurrenderGuard.resetForTest()

    @Test
    fun `uncertain surrender blocks queue until terminal evidence then allows recheck`() {
        MandatoryRankSurrenderGuard.begin()
        MandatoryRankSurrenderGuard.markRecoveryUncertain()

        assertTrue(MandatoryRankSurrenderGuard.isPending())
        assertTrue(MandatoryRankSurrenderGuard.isRecoveryUncertain())
        assertFalse(MandatoryRankSurrenderGuard.confirmCompleted("UNKNOWN"))
        assertFalse(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, true))
        assertFalse(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, MandatoryRankSurrenderGuard.isPending()))

        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL"))
        assertFalse(MandatoryRankSurrenderGuard.isPending())
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, MandatoryRankSurrenderGuard.isPending()))
    }

    @Test
    fun `confirmed main menu screen completes surrender recovery and permits requeue`() {
        MandatoryRankSurrenderGuard.begin()
        MandatoryRankSurrenderGuard.markRecoveryUncertain()
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("SCREEN_MAIN_MENU"))
        assertTrue(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, MandatoryRankSurrenderGuard.isPending()))
    }

    @Test
    fun `uncertain mandatory surrender retains retry state without auto-pausing`() {
        PauseStatus.setManualPause(false)
        try {
            MandatoryRankSurrenderGuard.begin()
            MandatoryRankSurrenderGuard.markRecoveryUncertain()

            assertTrue(MandatoryRankSurrenderGuard.isPending())
            assertTrue(MandatoryRankSurrenderGuard.isRecoveryUncertain())
            assertFalse(PauseStatus.isPause)
            assertTrue(
                MandatoryRankSurrenderRecoveryPolicy.shouldWaitForMoreEvidence(
                    mandatoryRank = true,
                    screenConfirmed = false,
                ),
            )
        } finally {
            PauseStatus.setManualPause(true)
        }
    }

    @Test
    fun `only the active surrender capability authorizes gated recovery input`() {
        val active = MandatoryRankSurrenderGuard.begin()
        val forged = MandatoryRankSurrenderGuard.RecoveryCapability()

        assertTrue(MandatoryRankSurrenderGuard.isRecoveryCapabilityValid(active))
        assertFalse(MandatoryRankSurrenderGuard.isRecoveryCapabilityValid(forged))
        assertTrue(MandatoryRankSurrenderGuard.confirmCompleted("POWERLOG_TERMINAL"))
        assertFalse(MandatoryRankSurrenderGuard.isRecoveryCapabilityValid(active))
    }
}
