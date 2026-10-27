package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BetaTerminalResultCleanupPolicyTest {
    @Test
    fun `fresh authorized result frame and same live terminal session permit only Continue`() {
        val allowed = validEvidence()

        assertTrue(BetaTerminalResultCleanupPolicy.allows(allowed))
        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(action = "recovery.left")))
        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(action = "strategy.card.play")))
        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(action = "queue.start")))
    }

    @Test
    fun `pending surrender or invalid same game terminal proof remains denied`() {
        val allowed = validEvidence()

        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(mandatoryRankSurrenderPending = true)))
        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(terminalCleanupPending = false)))
        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(terminalCleanupCapabilityValid = false)))
        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(currentPowerLogHasCompleteTerminalProof = false)))
        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(proofIdentity = "other-game")))
        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(currentSessionIdentity = "other-game")))
    }

    @Test
    fun `unknown stale or unbound capture and missing client remain denied`() {
        val allowed = validEvidence()

        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(captureAuthorized = false)))
        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(resultPageVisible = null)))
        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(resultPageVisible = false)))
        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(continueControlVisible = false)))
        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(captureAgeMs = 5_001)))
        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(gameProcessAlive = false)))
        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(currentWindowBoundToGame = false)))
    }

    @Test
    fun `stable and release candidate channels do not receive Beta promotion`() {
        val allowed = validEvidence()

        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(channel = "stable")))
        assertFalse(BetaTerminalResultCleanupPolicy.allows(allowed.copy(channel = "release-candidate")))
    }

    private fun validEvidence() = BetaTerminalResultCleanupPolicy.Evidence(
        channel = "beta",
        action = BetaTerminalResultCleanupPolicy.CONTINUE_ACTION,
        working = true,
        paused = false,
        gameProcessAlive = true,
        currentWindowBoundToGame = true,
        currentPowerLogHasCompleteTerminalProof = true,
        proofIdentity = "5:laz#12793",
        currentSessionIdentity = "5:laz#12793",
        mandatoryRankSurrenderPending = false,
        terminalCleanupPending = true,
        terminalCleanupCapabilityValid = true,
        captureAuthorized = true,
        resultPageVisible = true,
        continueControlVisible = true,
        captureAgeMs = 1,
    )
}
