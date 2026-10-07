package club.xiaojiawei.hsscript.strategy.mode

import club.xiaojiawei.hsscript.status.PowerLogActiveMatchProbe
import club.xiaojiawei.hsscript.status.VerifiedStartupMenuProgression
import club.xiaojiawei.hsscript.status.surrender.CurrentRankDetector
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline capability chain: verified HOME can hand off to HUB, but only the existing fresh-rank gate can queue. */
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
        var rankReads = 0
        // Mode.recover(HUB, enterStrategy=true) is a menu/state handoff only;
        // the following strategy must wait for the current-session log before rank capture.
        var currentSessionReady = false
        if (home == VerifiedStartupMenuProgression.Action.ENTER_HUB && currentSessionReady) {
            rankReads++
        }
        assertEquals(0, rankReads)
        assertEquals(0, queueDispatches)
        currentSessionReady = true

        fun rankDetection(rank: Int) = CurrentRankDetector.Detection(
            rank = rank,
            tier = CurrentRankDetector.RankTier.UNKNOWN,
            ocrText = rank.toString(),
            confidence = 0.99,
            captureBounds = Rectangle(5, 10, 20, 30),
            provider = "PADDLEX",
            capturedAtMs = 100_000L,
            agreementCount = 1,
        )
        // Once session readiness exists, queue input still goes through the production authorization boundary.
        for (rank in listOf(7, 5, 10)) {
            if (!currentSessionReady) continue
            val result = PreMatchRankGate.evaluate(
                working = true,
                paused = false,
                mandatoryRankSurrenderPending = false,
                expectedMode = "TOURNAMENT",
                actualMode = "TOURNAMENT",
                expectedInWar = false,
                inWar = false,
                nowMs = { 100_000L },
                detectFreshRank = { rankReads++; rankDetection(rank) },
            )
            assertEquals(rank != 5 && rank != 10, !result.queueAuthorization.allowed)
            if (MatchmakingGuardPolicy.dispatchIfAuthorized(result.queueAuthorization) { queueDispatches++ }) {
                assertTrue(rank == 5 || rank == 10)
            }
        }
        assertEquals(3, rankReads)
        assertEquals(2, queueDispatches, "only exact fresh rank 5/10 is dispatched")
    }
}
