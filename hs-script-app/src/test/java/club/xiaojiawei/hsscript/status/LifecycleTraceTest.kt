package club.xiaojiawei.hsscript.status

import java.time.Instant
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscriptbase.const.BuildChannel
import club.xiaojiawei.hsscriptbase.const.BuildInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifecycleTraceTest {

    private val originalRecoveryEnabled = ConfigUtil.getBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED)

    @AfterTest
    fun restoreRecoverySetting() {
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, originalRecoveryEnabled, store = false)
    }

    @Test
    fun automaticPauseStartupRemainsObservableButManualPauseDoesNot() {
        assertEquals(
            true,
            BetaScreenRecoveryService.shouldObserveNoProgress(
                working = false,
                automaticPause = true,
                replaying = false,
                paused = true,
                recoveryPending = false,
            ),
        )
        assertEquals(
            false,
            BetaScreenRecoveryService.shouldObserveNoProgress(
                working = false,
                automaticPause = false,
                replaying = false,
                paused = true,
                recoveryPending = false,
            ),
        )
    }

    @Test
    fun startupRecoveryGraceDefersRecoveryOnlyDuringTheGraceWindow() {
        val now = 1_000_000L
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, true, store = false)

        BetaScreenRecoveryService.markStartupRequested("offline-test", now)

        assertEquals(3_000L, BetaScreenRecoveryService.startupRecoveryGraceRemainingMs(now))
        assertEquals(0L, BetaScreenRecoveryService.startupRecoveryGraceRemainingMs(now + 3_000L))
        assertEquals(0L, BetaScreenRecoveryService.startupRecoveryGraceRemainingMs(now + 30_000L))
    }

    @Test
    fun lifecycleStartSchedulesTheAlwaysOnBetaFailureMonitorWithOptionalExtensionsDisabled() {
        assertTrue(BuildChannel.isBetaDerived(BuildInfo.RELEASE_CHANNEL))
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, false, store = false)
        BetaScreenRecoveryService.onFeatureChanged(false)
        LifecycleTrace.stop("offline-test-setup")

        try {
            LifecycleTrace.start()
            assertTrue(
                BetaScreenRecoveryService.startupFailureMonitorScheduledForTest(),
                "normal LifecycleTrace.start must schedule the Beta startup-failure monitor even when optional extensions are off",
            )
        } finally {
            LifecycleTrace.stop("offline-test-cleanup")
        }
    }

    @Test
    fun hardCapStoppedGuardianStateRequiresMatchingLineageAndDeployment() {
        val processStart = Instant.parse("2026-11-02T16:00:00Z")
        val guardian = """
            {"intent":"STOPPED","hardCapRunId":"run-1","pid":4242,
             "processStartTimeUtc":"$processStart","deploymentId":"beta-v746"}
        """.trimIndent().replace("\n", "")

        assertTrue(
            LifecycleTrace.hardCapStoppedGuardianStateMatches(
                guardianJson = guardian,
                processId = 4242L,
                processStart = processStart,
                deploymentId = "beta-v746",
            ),
            "a matching STOPPED tombstone must be recognized",
        )
        assertFalse(
            LifecycleTrace.hardCapStoppedGuardianStateMatches(
                guardian.replace("\"intent\":\"STOPPED\"", "\"intent\":\"RUNNING\""),
                4242L,
                processStart,
                "beta-v746",
            ),
            "RUNNING state must never be treated as a hard-cap stop tombstone",
        )
        assertFalse(
            LifecycleTrace.hardCapStoppedGuardianStateMatches(
                guardian,
                4243L,
                processStart,
                "beta-v746",
            ),
            "a stale PID must not suppress a new lifecycle heartbeat",
        )
        assertFalse(
            LifecycleTrace.hardCapStoppedGuardianStateMatches(
                guardian,
                4242L,
                processStart.plusSeconds(1),
                "beta-v746",
            ),
            "a stale process start time must not suppress a new lifecycle heartbeat",
        )
        assertFalse(
            LifecycleTrace.hardCapStoppedGuardianStateMatches(
                guardian,
                4242L,
                processStart,
                "beta-v747",
            ),
            "a stale deployment id must not suppress a new lifecycle heartbeat",
        )
        assertFalse(
            LifecycleTrace.hardCapStoppedGuardianStateMatches(
                guardian.replace("\"hardCapRunId\":\"run-1\",", ""),
                4242L,
                processStart,
                "beta-v746",
            ),
            "a STOPPED state without a hard-cap run id is not authoritative",
        )
    }
}
