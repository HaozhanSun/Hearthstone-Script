package club.xiaojiawei.hsscript.strategy.mode

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatchmakingDialogRecoveryTaskGateTest {
    @Test
    fun `only one OCR-heavy recovery task owns the probe until it stops`() {
        val gate = MatchmakingDialogRecoveryTaskGate()

        assertTrue(gate.tryAcquire())
        assertTrue(gate.isOwned())
        assertFalse(gate.tryAcquire(), "duplicate matchmaking traces must not launch concurrent OCR loops")

        gate.release()
        assertFalse(gate.isOwned())
        assertTrue(gate.tryAcquire(), "a later queue attempt may acquire after the prior recovery stops")
    }
}
