package club.xiaojiawei.hsscript.status

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
    fun unresolvedScreenRecoveryTripsOnTheExactSecondFailureForTheSameState() {
        val tracker = ScreenRecoveryAttemptTracker(maxUnresolvedAttempts = 2)

        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldPause = false),
            tracker.record("HUB|TOURNAMENT", recovered = false),
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 2, shouldPause = true),
            tracker.record("HUB|TOURNAMENT", recovered = false),
            "the configured threshold trips on, not after, the second unresolved attempt",
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 3, shouldPause = true),
            tracker.record("HUB|TOURNAMENT", recovered = false),
            "the tracker reports the actual attempt count used by the pause log",
        )
    }

    @Test
    fun terminalCleanupDefersAndResetsGenericUnresolvedPauseBudget() {
        val tracker = ScreenRecoveryAttemptTracker(maxUnresolvedAttempts = 2)
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldPause = false),
            tracker.record("GAMEPLAY|FILL_DECK|war-1", recovered = false),
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 0, shouldPause = false),
            tracker.deferForTerminalCleanup("GAMEPLAY|FILL_DECK|war-1"),
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldPause = false),
            tracker.record("GAMEPLAY|FILL_DECK|war-1", recovered = false),
            "after terminal cleanup ends, generic recovery starts a fresh bounded observation window",
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 2, shouldPause = true),
            tracker.record("GAMEPLAY|FILL_DECK|war-1", recovered = false),
            "ordinary unresolved screens still trigger the existing safety pause threshold",
        )
    }

    @Test
    fun recoveryAndStateChangeResetTheUnresolvedAttemptStreak() {
        val tracker = ScreenRecoveryAttemptTracker(maxUnresolvedAttempts = 2)
        tracker.record("HUB|TOURNAMENT", recovered = false)
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 0, shouldPause = false),
            tracker.record("HUB|TOURNAMENT", recovered = true),
            "a recovered screen clears the failure streak",
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldPause = false),
            tracker.record("HUB|TOURNAMENT", recovered = false),
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldPause = false),
            tracker.record("HOME|TOURNAMENT", recovered = false),
            "a changed state gets a fresh bounded budget",
        )
        tracker.reset()
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldPause = false),
            tracker.record("HOME|TOURNAMENT", recovered = false),
            "an explicit lifecycle reset clears both fingerprint and count",
        )
    }

    @Test
    fun unresolvedRecoveryPauseBudgetIncludingMonitorPollingIsAtMostOneMinute() {
        assertEquals(60_000L, LifecycleTrace.unresolvedRecoveryPauseBoundMsForTest())
        assertTrue(LifecycleTrace.unresolvedRecoveryPauseBoundMsForTest() <= 60_000L)
    }

    @Test
    fun sameDeckSelectionRankDeniedRecoveryIsTrackedAsNoProgressAndBounded() {
        val tracker = ScreenRecoveryAttemptTracker(maxUnresolvedAttempts = 2)
        val screenKind = "DECK_SELECTION"
        val mode = "TOURNAMENT"
        val phase = "FILL_DECK"
        val fingerprint = "$screenKind|$mode|$phase|rank-denied"
        val recovered = DeckSelectionRecoveryPolicy.shouldApply(screenKind, mode, phase)

        assertFalse(recovered, "identical deck-selection state must not claim recovery progress")
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldPause = false),
            tracker.record(fingerprint, recovered),
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 2, shouldPause = true),
            tracker.record(fingerprint, recovered),
            "the unchanged rank-denied fingerprint reaches the existing bounded pause threshold",
        )
        assertTrue(LifecycleTrace.unresolvedRecoveryPauseBoundMsForTest() <= 60_000L)
        assertTrue(
            DeckSelectionRecoveryPolicy.shouldApply("HUB", mode, phase),
            "a genuinely changed screen remains eligible for the normal recovery action",
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
