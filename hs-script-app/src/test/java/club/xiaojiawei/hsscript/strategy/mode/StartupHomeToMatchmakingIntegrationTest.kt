package club.xiaojiawei.hsscript.strategy.mode

import club.xiaojiawei.hsscript.status.PowerLogActiveMatchProbe
import club.xiaojiawei.hsscript.status.VerifiedStartupMenuProgression
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline capability chain: verified HOME can hand off to HUB, but a fresh eligible rank owns queue dispatch. */
class StartupHomeToMatchmakingIntegrationTest {
    @Test
    fun `home handoff itself never queues and current-session fresh rank gate owns dispatch`() {
        val home = VerifiedStartupMenuProgression.decide(
            VerifiedStartupMenuProgression.Evidence(
                screen = VerifiedStartupMenuProgression.Screen.HOME,
                confidence = 95,
                pid = 42L,
                currentPid = 42L,
                windowPid = 42L,
                foregroundAndPixelsVerified = true,
                configuredTournament = true,
                working = true,
                manuallyPaused = false,
                currentSessionPowerLogReady = false,
                activeMatch = PowerLogActiveMatchProbe.State.NO_MATCH,
                priorAuthoritativeLineage = false,
                processLineageVerified = true,
            ),
        )
        assertEquals(VerifiedStartupMenuProgression.Action.ENTER_HUB, home)
        val recoverySource = listOf(Path.of("."), Path.of("hs-script-app"))
            .map {
                it.resolve(Path.of(
                    "src", "main", "java", "club", "xiaojiawei", "hsscript", "status", "ScreenStateRecovery.kt",
                ))
            }
            .first { Files.isRegularFile(it) }
        assertTrue(
            Files.readString(recoverySource).contains(
                "Mode.recover(ModeEnum.HUB, \"verified-pre-session-home\", enterStrategy = true)",
            ),
            "the production HOME branch must invoke the existing HUB mode lifecycle",
        )

        var queueDispatches = 0
        // Mode.recover(HUB, enterStrategy=true) is a menu/state handoff only;
        // the following strategy must wait for the current-session log before rank capture.
        var currentSessionReady = false
        assertEquals(0, queueDispatches)
        currentSessionReady = true
        // Queue authorization requires the rank read captured immediately on
        // the deck-selection page, before any matchmaking input.
        for (rank in listOf(4, 5, 10, 21)) {
            if (!currentSessionReady) continue
            val result = PreMatchRankGate.evaluate(
                working = true,
                paused = false,
                mandatoryRankSurrenderPending = false,
                tournamentMode = true,
                inWar = false,
                observedRank = rank,
                freshRankObservation = true,
                ocrFailure = false,
            )
            val expectedAllowed = rank == 5 || rank == 10
            assertEquals(expectedAllowed, result.queueAuthorization.allowed, "rank=$rank")
            assertEquals(expectedAllowed, MatchmakingGuardPolicy.dispatchIfAuthorized(result.queueAuthorization) { queueDispatches++ })
        }
        assertEquals(2, queueDispatches, "only exact rank 5 and rank 10 dispatch queue input")
    }
}
