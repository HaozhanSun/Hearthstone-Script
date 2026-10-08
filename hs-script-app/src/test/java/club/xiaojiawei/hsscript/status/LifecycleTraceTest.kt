package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscriptbase.const.BuildChannel
import club.xiaojiawei.hsscriptbase.const.BuildInfo
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun unresolvedScreenRecoveryRequestsBackoffOnTheExactSecondFailureForTheSameState() {
        val tracker = ScreenRecoveryAttemptTracker(maxUnresolvedAttempts = 2)

        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldBackoff = false),
            tracker.record("HUB|TOURNAMENT", recovered = false),
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 2, shouldBackoff = true),
            tracker.record("HUB|TOURNAMENT", recovered = false),
            "the configured threshold requests backoff on, not after, the second unresolved attempt",
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 3, shouldBackoff = true),
            tracker.record("HUB|TOURNAMENT", recovered = false),
            "the tracker reports the actual attempt count used by the backoff log",
        )
    }

    @Test
    fun terminalCleanupDefersAndResetsGenericUnresolvedRecoveryBudget() {
        val tracker = ScreenRecoveryAttemptTracker(maxUnresolvedAttempts = 2)
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldBackoff = false),
            tracker.record("GAMEPLAY|FILL_DECK|war-1", recovered = false),
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 0, shouldBackoff = false),
            tracker.deferForTerminalCleanup("GAMEPLAY|FILL_DECK|war-1"),
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldBackoff = false),
            tracker.record("GAMEPLAY|FILL_DECK|war-1", recovered = false),
            "after terminal cleanup ends, generic recovery starts a fresh bounded observation window",
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 2, shouldBackoff = true),
            tracker.record("GAMEPLAY|FILL_DECK|war-1", recovered = false),
            "ordinary unresolved screens trigger bounded retry backoff without pausing",
        )
    }

    @Test
    fun verifiedLiveQueueDefersGenericPauseWithoutDispatchingInput() {
        val tracker = ScreenRecoveryAttemptTracker(maxUnresolvedAttempts = 2)
        val queue = MatchmakingQueueLifecycle()
        val pid = 95400L
        val observed = queue.observeScreen(
            observedPid = pid,
            currentPid = pid,
            evidence = MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING,
            confidence = 95,
            captureAuthorized = true,
            processAlive = true,
            nowNanos = 1_000_000L,
        )
        assertTrue(observed.isPending)
        assertEquals(
            MatchmakingQueueLifecycle.TimeoutDisposition.DEFER_WITHOUT_INPUT,
            queue.timeoutDisposition(pid, processAlive = true, nowNanos = 2_000_000L),
            "a fresh, PID-bound queue is progress to wait for, not a reason to click or pause",
        )

        tracker.record("TOURNAMENT|FILL_DECK|war-1", recovered = false)
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 0, shouldBackoff = false),
            tracker.deferForExpectedProgress("TOURNAMENT|FILL_DECK|war-1"),
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldBackoff = false),
            tracker.record("TOURNAMENT|FILL_DECK|war-1", recovered = false),
            "after queue evidence expires or terminates, ordinary recovery gets a fresh bounded budget",
        )
    }

    @Test
    fun verifiedQueueExitResetsOldFailuresAndDeckScreenCountsAsRecovery() {
        val tracker = ScreenRecoveryAttemptTracker(maxUnresolvedAttempts = 2)
        val queue = MatchmakingQueueLifecycle()
        val pid = 102492L
        assertTrue(
            queue.observeScreen(
                observedPid = pid,
                currentPid = pid,
                evidence = MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING,
                confidence = 95,
                captureAuthorized = true,
                processAlive = true,
                nowNanos = 1_000_000L,
            ).isPending,
        )
        assertEquals(
            MatchmakingQueueLifecycle.Phase.QUEUE_TERMINAL,
            queue.observeScreen(
                observedPid = pid,
                currentPid = pid,
                evidence = MatchmakingQueueLifecycle.ScreenEvidence.QUEUE_TERMINAL,
                confidence = 100,
                captureAuthorized = true,
                processAlive = true,
                nowNanos = 2_000_000L,
            ).phase,
        )

        tracker.record("TOURNAMENT|FILL_DECK|NONE|0", recovered = false)
        val queueExitKey = "${pid}:${queue.snapshotFor(pid, true, 3_000_000L).startedAtNanos}:${queue.snapshotFor(pid, true, 3_000_000L).phase}"
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 0, shouldBackoff = false),
            tracker.observeQueueExit("TOURNAMENT|FILL_DECK|NONE|0", queueExitKey),
            "LifecycleTrace performs a one-time unresolved-budget reset on queue exit",
        )
        assertNull(tracker.observeQueueExit("TOURNAMENT|FILL_DECK|NONE|0", queueExitKey))
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 0, shouldBackoff = false),
            tracker.record("TOURNAMENT|FILL_DECK|NONE|0", recovered = true),
            "the fresh, classified rank-five deck screen is successful recovery evidence, not another unresolved attempt",
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldBackoff = false),
            tracker.record("TOURNAMENT|FILL_DECK|NONE|0", recovered = false),
            "a later distinct unresolved capture still starts a bounded attempt count",
        )
    }

    @Test
    fun recoveryAndStateChangeResetTheUnresolvedAttemptStreak() {
        val tracker = ScreenRecoveryAttemptTracker(maxUnresolvedAttempts = 2)
        tracker.record("HUB|TOURNAMENT", recovered = false)
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 0, shouldBackoff = false),
            tracker.record("HUB|TOURNAMENT", recovered = true),
            "a recovered screen clears the failure streak",
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldBackoff = false),
            tracker.record("HUB|TOURNAMENT", recovered = false),
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldBackoff = false),
            tracker.record("HOME|TOURNAMENT", recovered = false),
            "a changed state gets a fresh bounded budget",
        )
        tracker.reset()
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldBackoff = false),
            tracker.record("HOME|TOURNAMENT", recovered = false),
            "an explicit lifecycle reset clears both fingerprint and count",
        )
    }

    @Test
    fun unresolvedRecoveryBackoffBudgetIncludingMonitorPollingIsAtMostOneMinute() {
        assertEquals(60_000L, LifecycleTrace.unresolvedRecoveryPauseBoundMsForTest())
        assertTrue(LifecycleTrace.unresolvedRecoveryPauseBoundMsForTest() <= 60_000L)
    }

    @Test
    fun sameDeckSelectionRankDeniedRecoveryIsTrackedAsNoProgressAndBackedOff() {
        val tracker = ScreenRecoveryAttemptTracker(maxUnresolvedAttempts = 2)
        val screenKind = "DECK_SELECTION"
        val mode = "TOURNAMENT"
        val phase = "FILL_DECK"
        val fingerprint = "$screenKind|$mode|$phase|rank-denied"
        val recovered = DeckSelectionRecoveryPolicy.shouldApply(screenKind, mode, phase)

        assertFalse(recovered, "identical deck-selection state must not claim recovery progress")
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 1, shouldBackoff = false),
            tracker.record(fingerprint, recovered),
        )
        assertEquals(
            ScreenRecoveryAttemptTracker.Decision(unresolvedAttempts = 2, shouldBackoff = true),
            tracker.record(fingerprint, recovered),
            "the unchanged rank-denied fingerprint reaches the bounded backoff threshold",
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
        // The monitor is deliberately Beta-only. Local developer artifacts do
        // not carry a Beta channel, so this is a channel-specific integration
        // assertion rather than a failure of the local unit-test environment.
        assumeTrue(BuildChannel.isBetaDerived(BuildInfo.RELEASE_CHANNEL))
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
