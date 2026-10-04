package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscriptbase.const.BuildChannel
import club.xiaojiawei.hsscriptbase.const.BuildInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
