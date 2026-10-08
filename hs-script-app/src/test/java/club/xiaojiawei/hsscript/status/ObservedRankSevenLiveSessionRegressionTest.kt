package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.status.surrender.CurrentRankDetector
import club.xiaojiawei.hsscript.status.surrender.MulliganRankDispatchBarrier
import club.xiaojiawei.hsscript.status.surrender.RankEligibilityPolicy
import club.xiaojiawei.hsscript.utils.GameWindowDiscoveryPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Rectangle
import java.time.LocalDateTime
import java.time.ZoneId

/** Regression fixture for the 2026-10-02 live Mulligan whose Power.log was not attached. */
class ObservedRankSevenLiveSessionRegressionTest {

    @Test
    fun `native PID fallback binds current game log and rank seven cannot release mulligan actions`() {
        val gamePid = GameWindowDiscoveryPolicy.selectDiagnosticPid(
            windowOwnerPid = null,
            nativeProcessPids = listOf(OBSERVED_GAME_PID),
        )
        assertEquals(OBSERVED_GAME_PID, gamePid)
        assertEquals(
            null,
            GameWindowDiscoveryPolicy.select(
                candidates = emptyList(),
                expectedProcessName = "Hearthstone.exe",
                preferredTitles = setOf("Hearthstone", "炉石传说"),
            ),
            "native PID lineage fallback must not fabricate a matching HWND",
        )

        val processStartedAt = GameWindowDiscoveryPolicy.selectProcessStartedAtMs(
            processHandleStartedAtMs = null,
            nativeStartedAtMs = localTime("2026-10-02T21:42:15"),
        )
        assertNotNull(processStartedAt, "native process-time query supplies lineage when ProcessHandle has no handle")
        val powerLogIsCurrent = PowerLogSessionBindingPolicy.isCurrentSession(
            powerLogPath = OBSERVED_POWER_LOG,
            gameLogsRoot = OBSERVED_LOG_ROOT,
            length = 442_678L,
            lastModifiedMs = localTime("2026-10-02T21:46:27"),
            processStartedAtMs = processStartedAt,
        )
        assertTrue(powerLogIsCurrent, "fresh Power.log belongs to the native-discovered live Hearthstone PID")

        val noProgress = NoProgressWatchdog(noProgressTimeoutMs = 1_000L).observe(
            NoProgressWatchdog.Snapshot(
                nowMs = 1_000_000L,
                mode = "STARTUP",
                expectedMode = "STARTUP",
                screen = NoProgressWatchdog.ScreenExpectation.MULLIGAN,
                processAlive = true,
                currentPid = gamePid,
                boundPid = gamePid,
                windowPresent = false,
                powerLogPath = OBSERVED_POWER_LOG,
                boundPowerLogPath = OBSERVED_POWER_LOG,
                powerLogPosition = 442_678L,
                powerLogLength = 442_678L,
                powerLogAgeMs = 900_000L,
                powerLogUsable = true,
                authoritativeLiveMatch = true,
                screenConfirmed = true,
            ),
        )
        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, noProgress.action)
        assertEquals("live-match-preserved", noProgress.reason)

        val detection = CurrentRankDetector.Detection(
            rank = 7,
            tier = CurrentRankDetector.RankTier.GOLD,
            ocrText = "7",
            confidence = 0.99,
            captureBounds = Rectangle(20, 930, 40, 48),
            provider = "PADDLEX",
            capturedAtMs = localTime("2026-10-02T21:45:28"),
            agreementCount = 1,
        )
        val rankDecision = RankEligibilityPolicy.evaluate(
            detection = detection,
            expectedMode = "GAMEPLAY",
            actualMode = "GAMEPLAY",
            expectedInWar = true,
            inWar = true,
            nowMs = localTime("2026-10-02T21:45:29"),
        )
        assertFalse(rankDecision.eligible)
        assertEquals("rank-not-5-or-10", rankDecision.reason)

        MulliganRankDispatchBarrier.resetForTest()
        try {
            val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
            assertFalse(MulliganRankDispatchBarrier.authorizeEligibleRank(ticket, detection.rank!!))
            val surrender = MulliganRankDispatchBarrier.requireSurrender(ticket)
            assertNotNull(surrender)
            assertEquals(
                MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED,
                MulliganRankDispatchBarrier.currentState(),
            )
            assertFalse(
                ActionDispatchGate.allowForState(
                    action = "mulligan.confirm",
                    paused = false,
                    working = true,
                    rankBarrierState = MulliganRankDispatchBarrier.currentState(),
                ),
            )
        } finally {
            MulliganRankDispatchBarrier.resetForTest()
        }
    }

    private fun localTime(value: String): Long =
        LocalDateTime.parse(value).atZone(ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli()

    private companion object {
        const val OBSERVED_GAME_PID = 73_060L
        const val OBSERVED_LOG_ROOT = "D:/Hearthstone/Logs"
        const val OBSERVED_POWER_LOG =
            "D:/Hearthstone/Logs/Hearthstone_2026_10_02_21_42_18/Power.log"
    }
}
