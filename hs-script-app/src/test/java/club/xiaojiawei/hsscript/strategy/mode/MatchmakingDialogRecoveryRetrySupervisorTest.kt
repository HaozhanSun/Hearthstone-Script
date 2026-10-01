package club.xiaojiawei.hsscript.strategy.mode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatchmakingDialogRecoveryRetrySupervisorTest {
    @Test
    fun `exhaustion keeps script unpaused and blocks clicks during cooldown`() {
        val supervisor = MatchmakingDialogRecoveryRetrySupervisor(cooldownMs = 30_000L)
        val exhausted = supervisor.beginCooldown(10_000L)

        assertEquals(MatchmakingDialogRecoveryRetrySupervisor.Action.WAIT_COOLDOWN, exhausted.action)
        assertFalse(exhausted.pauseRequested)
        assertFalse(exhausted.clickAllowed)

        val duringCooldown = supervisor.observe(
            39_999L,
            MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE,
        )
        assertEquals(MatchmakingDialogRecoveryRetrySupervisor.Action.WAIT_COOLDOWN, duringCooldown.action)
        assertFalse(duringCooldown.pauseRequested)
        assertFalse(duringCooldown.clickAllowed)
    }

    @Test
    fun `cooldown expiry requires fresh exact dialog evidence and another probe before clicking`() {
        val supervisor = MatchmakingDialogRecoveryRetrySupervisor(cooldownMs = 30_000L)
        supervisor.beginCooldown(10_000L)

        val unknownAtDeadline = supervisor.observe(
            40_000L,
            MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
        )
        assertEquals(MatchmakingDialogRecoveryRetrySupervisor.Action.WAIT_COOLDOWN, unknownAtDeadline.action)
        assertEquals("fresh-exact-dialog-evidence-required", unknownAtDeadline.reason)
        assertFalse(unknownAtDeadline.clickAllowed)
        assertFalse(unknownAtDeadline.pauseRequested)

        val stillCooling = supervisor.observe(
            69_999L,
            MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE,
        )
        assertEquals(MatchmakingDialogRecoveryRetrySupervisor.Action.WAIT_COOLDOWN, stillCooling.action)

        val exactEvidenceRearms = supervisor.observe(
            70_000L,
            MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE,
        )
        assertEquals(MatchmakingDialogRecoveryRetrySupervisor.Action.REARMED, exactEvidenceRearms.action)
        assertTrue(exactEvidenceRearms.reason.contains("fresh-exact-dialog-evidence"))
        assertFalse(exactEvidenceRearms.clickAllowed)
        assertFalse(exactEvidenceRearms.pauseRequested)

        val nextFreshProbe = supervisor.observe(
            71_000L,
            MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE,
        )
        assertEquals(MatchmakingDialogRecoveryRetrySupervisor.Action.ATTEMPT_ALLOWED, nextFreshProbe.action)
        assertTrue(nextFreshProbe.clickAllowed)
        assertFalse(nextFreshProbe.pauseRequested)
    }
}
