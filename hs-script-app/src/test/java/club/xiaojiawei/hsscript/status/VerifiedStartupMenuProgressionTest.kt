package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VerifiedStartupMenuProgressionTest {
    private fun evidence(
        screen: VerifiedStartupMenuProgression.Screen = VerifiedStartupMenuProgression.Screen.HOME,
        active: PowerLogActiveMatchProbe.State = PowerLogActiveMatchProbe.State.NO_MATCH,
        lineage: Boolean = false,
        ready: Boolean = false,
        confidence: Int = 95,
        overlayDismissals: Int = 0,
    ) = VerifiedStartupMenuProgression.Evidence(
        screen, confidence, 42L, 42L, 42L, true, true, true, false,
        ready, active, lineage, overlayDismissals,
    )

    @Test
    fun `verified home advances only with no match and no prior lineage`() {
        assertEquals(VerifiedStartupMenuProgression.Action.ENTER_HUB, VerifiedStartupMenuProgression.decide(evidence()))
        assertEquals(VerifiedStartupMenuProgression.Action.WAIT, VerifiedStartupMenuProgression.decide(
            evidence(active = PowerLogActiveMatchProbe.State.ACTIVE_MATCH),
        ))
        assertEquals(VerifiedStartupMenuProgression.Action.WAIT, VerifiedStartupMenuProgression.decide(
            evidence(active = PowerLogActiveMatchProbe.State.TERMINAL),
        ))
        assertEquals(VerifiedStartupMenuProgression.Action.WAIT, VerifiedStartupMenuProgression.decide(
            evidence(active = PowerLogActiveMatchProbe.State.UNREADABLE),
        ))
        assertEquals(VerifiedStartupMenuProgression.Action.WAIT, VerifiedStartupMenuProgression.decide(evidence(lineage = true)))
    }

    @Test
    fun `overlay gets one dismissal attempt and fresh home evidence is required`() {
        val overlay = VerifiedStartupMenuProgression.Screen.HOME_TASK_OVERLAY
        assertEquals(VerifiedStartupMenuProgression.Action.DISMISS_OVERLAY, VerifiedStartupMenuProgression.decide(evidence(screen = overlay)))
        assertEquals(VerifiedStartupMenuProgression.Action.WAIT, VerifiedStartupMenuProgression.decide(
            evidence(screen = overlay, overlayDismissals = 1),
        ))
        assertEquals(VerifiedStartupMenuProgression.Action.ENTER_HUB, VerifiedStartupMenuProgression.decide(evidence()))
    }

    @Test
    fun `startup menu authority never authorizes queue and fails closed on incomplete evidence`() {
        assertEquals(VerifiedStartupMenuProgression.Action.BLOCK, VerifiedStartupMenuProgression.decide(evidence(ready = true)))
        assertEquals(VerifiedStartupMenuProgression.Action.BLOCK, VerifiedStartupMenuProgression.decide(evidence(confidence = 84)))
        assertEquals(VerifiedStartupMenuProgression.Action.BLOCK, VerifiedStartupMenuProgression.decide(
            evidence().copy(foregroundAndPixelsVerified = false),
        ))
        assertEquals(VerifiedStartupMenuProgression.Action.BLOCK, VerifiedStartupMenuProgression.decide(
            evidence().copy(configuredTournament = false),
        ))
        assertEquals(VerifiedStartupMenuProgression.Action.BLOCK, VerifiedStartupMenuProgression.decide(
            evidence().copy(manuallyPaused = true),
        ))
        assertEquals(VerifiedStartupMenuProgression.Action.BLOCK, VerifiedStartupMenuProgression.decide(
            evidence().copy(processLineageVerified = false),
        ), "missing Power.log is NO_MATCH only with verified process lineage")
    }
}
