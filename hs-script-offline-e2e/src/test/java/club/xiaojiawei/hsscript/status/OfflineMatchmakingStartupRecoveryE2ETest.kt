package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.strategy.mode.MatchmakingDialogRecoveryPolicy
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingDialogRecoveryRetrySupervisor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** Offline workflow replay over the same pure policies used by the Windows app. */
class OfflineMatchmakingStartupRecoveryE2ETest {
    @Serializable
    private data class Fixture(
        val scenario: String,
        val gameWindowVerified: Boolean,
        val attachedPowerLogPath: String?,
        val currentSessionPowerLogPath: String?,
        val powerLogLength: Long,
        val exactQueueModalPurpose: Boolean,
        val tournamentMode: Boolean,
        val activeGame: Boolean,
        val mulligan: Boolean,
        val terminal: Boolean,
        val dialogProbe: String,
        val confirmDispatchAccepted: Boolean,
        val freshScreenKind: String,
        val freshScreenConfidence: Int,
        val freshScreenEvidence: String,
        val freshObservation: Boolean,
        val maxAttempts: Int,
        val cooldownMs: Long,
    )

    @Test
    fun `unbound startup modal uses scoped capture one accepted confirm and fresh deck screen`() {
        val fixture = readFixture()
        assertEquals("startup-opponent-error-modal-with-unbound-zero-byte-powerlog", fixture.scenario)
        assertFalse(
            CurrentGameScreenReadinessPolicy.isReady(
                gameWindowVerified = fixture.gameWindowVerified,
                attachedPowerLogPath = fixture.attachedPowerLogPath,
                currentSessionPowerLogPath = fixture.currentSessionPowerLogPath,
                powerLogLength = fixture.powerLogLength,
            ),
            "unbound/empty Power.log must remain unready for ordinary game-state capture",
        )

        assertTrue(
            PreSessionQueueModalCapturePolicy.isAuthorized(
                exactQueueModalPurpose = fixture.exactQueueModalPurpose,
                tournamentMode = fixture.tournamentMode,
                activeGame = fixture.activeGame,
                mulligan = fixture.mulligan,
                terminal = fixture.terminal,
            ),
            "only the exact queue-modal purpose may use the constrained pre-session exception",
        )
        assertFalse(
            PreSessionQueueModalCapturePolicy.isAuthorized(
                exactQueueModalPurpose = false,
                tournamentMode = fixture.tournamentMode,
                activeGame = fixture.activeGame,
                mulligan = fixture.mulligan,
                terminal = fixture.terminal,
            ),
        )

        val decision = MatchmakingDialogRecoveryPolicy.decide(
            context = MatchmakingDialogRecoveryPolicy.Context(
                paused = false,
                tournamentMode = fixture.tournamentMode,
                gameStarted = fixture.activeGame,
            ),
            probe = MatchmakingDialogRecoveryPolicy.Probe.valueOf(fixture.dialogProbe),
            completedAttempts = 0,
            priorClickSent = false,
        )
        assertEquals(MatchmakingDialogRecoveryPolicy.Action.CLICK_CONFIRM, decision.action)
        var dispatchCount = 0
        val accepted = MatchmakingDialogRecoveryPolicy.dispatchConfirm(decision) {
            dispatchCount++
            fixture.confirmDispatchAccepted
        }
        assertEquals(1, dispatchCount)
        assertEquals(true, accepted, "an action decision must be separated from adapter acceptance")

        val freshDeckSelectionConfirmed = FreshDeckSelectionConfirmationPolicy.isConfirmed(
            screenKind = fixture.freshScreenKind,
            confidence = fixture.freshScreenConfidence,
            evidence = fixture.freshScreenEvidence,
            freshObservation = fixture.freshObservation,
        )
        assertTrue(
            freshDeckSelectionConfirmed,
            "the accepted dialog click is followed by a fresh positive deck-selection observation",
        )
        assertFalse(
            FreshDeckSelectionConfirmationPolicy.isConfirmed(
                screenKind = fixture.freshScreenKind,
                confidence = fixture.freshScreenConfidence,
                evidence = fixture.freshScreenEvidence,
                freshObservation = false,
            ),
            "a stale deck-selection observation cannot confirm the recovery",
        )
        val confirmed = MatchmakingDialogRecoveryPolicy.decide(
            context = MatchmakingDialogRecoveryPolicy.Context(false, fixture.tournamentMode, gameStarted = false),
            probe = if (freshDeckSelectionConfirmed) {
                MatchmakingDialogRecoveryPolicy.Probe.NO_ERROR_DIALOG
            } else {
                MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN
            },
            completedAttempts = 1,
            priorClickSent = accepted == true && freshDeckSelectionConfirmed,
        )
        assertEquals(MatchmakingDialogRecoveryPolicy.Action.CONFIRMED_DISMISSED, confirmed.action)
        assertFalse(confirmed.action == MatchmakingDialogRecoveryPolicy.Action.CLICK_CONFIRM)
    }

    @Test
    fun `unknown probe budget exhausts into bounded cooldown without pause or input`() {
        val fixture = readFixture()
        assertEquals(MatchmakingDialogRecoveryPolicy.MAX_ATTEMPTS, fixture.maxAttempts)
        var completedAttempts = 0
        var decision = MatchmakingDialogRecoveryPolicy.Decision(
            MatchmakingDialogRecoveryPolicy.Action.WAIT_AND_RETRY,
            "initial",
        )
        repeat(fixture.maxAttempts) {
            decision = MatchmakingDialogRecoveryPolicy.decide(
                context = MatchmakingDialogRecoveryPolicy.Context(false, tournamentMode = true, gameStarted = false),
                probe = MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
                completedAttempts = completedAttempts,
                priorClickSent = false,
            )
            assertEquals(MatchmakingDialogRecoveryPolicy.Action.WAIT_AND_RETRY, decision.action)
            completedAttempts++
        }
        decision = MatchmakingDialogRecoveryPolicy.decide(
            context = MatchmakingDialogRecoveryPolicy.Context(false, tournamentMode = true, gameStarted = false),
            probe = MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
            completedAttempts = completedAttempts,
            priorClickSent = false,
        )
        assertEquals(MatchmakingDialogRecoveryPolicy.Action.EXHAUSTED, decision.action)

        val retry = MatchmakingDialogRecoveryRetrySupervisor(fixture.cooldownMs)
        val cooldown = retry.beginCooldown(nowMs = 100L)
        assertEquals(MatchmakingDialogRecoveryRetrySupervisor.Action.WAIT_COOLDOWN, cooldown.action)
        assertEquals(100L + fixture.cooldownMs, cooldown.retryAfterMs)
        assertFalse(cooldown.pauseRequested)
        assertFalse(cooldown.clickAllowed)
        assertNull(MatchmakingDialogRecoveryPolicy.dispatchConfirm(decision) { error("must not dispatch") })

        val duringCooldown = retry.observe(
            nowMs = cooldown.retryAfterMs - 1,
            probe = MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE,
        )
        assertEquals(MatchmakingDialogRecoveryRetrySupervisor.Action.WAIT_COOLDOWN, duringCooldown.action)
        assertFalse(duringCooldown.pauseRequested)
        assertFalse(duringCooldown.clickAllowed)

        val expiredWithoutFreshDialog = retry.observe(
            nowMs = cooldown.retryAfterMs,
            probe = MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
        )
        assertEquals(MatchmakingDialogRecoveryRetrySupervisor.Action.WAIT_COOLDOWN, expiredWithoutFreshDialog.action)
        assertEquals("fresh-exact-dialog-evidence-required", expiredWithoutFreshDialog.reason)
        assertFalse(expiredWithoutFreshDialog.pauseRequested)
        assertFalse(expiredWithoutFreshDialog.clickAllowed)
    }

    private fun readFixture(): Fixture = Json.decodeFromString(
        javaClass.classLoader.getResourceAsStream(
            Path.of("offline-ocr", "screen-recovery", "startup-modal-recovery.json").toString()
                .replace('\\', '/'),
        )?.bufferedReader()?.use { it.readText() } ?: error("Missing startup modal replay fixture"),
    )
}
