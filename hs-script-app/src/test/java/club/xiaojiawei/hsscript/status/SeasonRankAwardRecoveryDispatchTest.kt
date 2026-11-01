package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeasonRankAwardRecoveryDispatchTest {
    @Test
    fun `blank five chest monthly reward screen notifies once and pauses without input or restart`() {
        val frame = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/season-rank-five-chests-blank-continue-live-20261101-105355.png",
        )))
        val screen = requireNotNull(UpstreamScreenStateRecovery.classifyImageForTest("", frame))
        val visual = SeasonRankAwardRewardsVisualClassifier.inspect(frame)
        assertEquals("SEASON_RANK_AWARD_REWARDS", screen)
        assertEquals("season-rank-award-five-chests-continue-not-visible",
            UpstreamScreenStateRecovery.classificationEvidenceForImageTest("", frame))
        assertTrue(visual.isRewardPresentation)
        assertFalse(visual.isRewardContinueReady)

        val policy = SeasonRankAwardHumanInterventionPolicy()
        var notifications = 0
        var pauses = 0
        var inputDispatches = 0
        var restarts = 0
        fun observe(
            screenKind: String?,
            authorized: Boolean = true,
            rewardVisible: Boolean = visual.isRewardPresentation,
            continueVisible: Boolean = visual.isRewardContinueReady,
        ) = policy.observe(
            screenKind = screenKind,
            rewardPresentationVisible = rewardVisible,
            continueVisible = continueVisible,
            freshAuthorizedCapture = authorized,
            stateStillCurrent = true,
            notifyUser = { notifications++ },
            holdAutomation = { pauses++ },
        )

        val first = observe(screen)
        val repeated = observe(screen)
        assertTrue(first.holdAutomation)
        assertTrue(first.notifyUser)
        assertTrue(repeated.holdAutomation)
        assertFalse(repeated.notifyUser, "repeated probes on the same modal must not spam the notice")
        assertEquals(1, notifications)
        assertEquals(2, pauses, "a manual resume while the modal remains must be held again")
        assertEquals(0, inputDispatches)
        assertEquals(0, restarts)
        val classifierUnknown = observe(null)
        assertTrue(classifierUnknown.holdAutomation,
            "an OCR/classifier UNKNOWN on the same exact visual must retain the hold")
        assertFalse(classifierUnknown.notifyUser, "UNKNOWN must not duplicate an active occurrence notification")
        assertEquals(3, pauses)
        assertEquals(1, notifications)

        assertFalse(observe("HOME", rewardVisible = false).holdAutomation,
            "a fresh different screen ends this occurrence")
        assertTrue(observe(screen).notifyUser, "a later occurrence must be notified again")
        assertEquals(2, notifications)
        assertEquals(0, inputDispatches)
        assertEquals(0, restarts)

        val untrusted = SeasonRankAwardHumanInterventionPolicy().observe(
            screenKind = screen,
            rewardPresentationVisible = visual.isRewardPresentation,
            continueVisible = visual.isRewardContinueReady,
            freshAuthorizedCapture = false,
            stateStillCurrent = true,
            notifyUser = { notifications++ },
            holdAutomation = { pauses++ },
        )
        assertFalse(untrusted.holdAutomation, "stale/unauthorized captures cannot trigger a user pause")
        assertEquals(2, notifications)
        assertEquals(0, inputDispatches)
        assertEquals(0, restarts)
    }

    @Test
    fun `visible Continue on reward screen does not trigger blank modal manual pause`() {
        var notifications = 0
        var pauses = 0
        val observation = SeasonRankAwardHumanInterventionPolicy().observe(
            screenKind = "SEASON_RANK_AWARD_REWARDS",
            rewardPresentationVisible = true,
            continueVisible = true,
            freshAuthorizedCapture = true,
            stateStillCurrent = true,
            notifyUser = { notifications++ },
            holdAutomation = { pauses++ },
        )

        assertFalse(observation.holdAutomation)
        assertFalse(observation.notifyUser)
        assertEquals(0, notifications)
        assertEquals(0, pauses)
    }

    @Test
    fun `fresh restart with empty current Power log admits only the exact rank award Continue`() {
        val frame = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/season-rank-award-fresh-restart-live-20261101-041948.png",
        )))
        val screen = requireNotNull(UpstreamScreenStateRecovery.classifyImageForTest("", frame))
        assertEquals("SEASON_RANK_AWARD", screen)
        assertTrue(SeasonRankAwardVisualClassifier.isSeasonRankAward(frame))

        assertTrue(SeasonRankAwardRecoveryPolicy.allowsDedicatedStartupRecovery(
            screenKind = screen,
            detectionEvidence = "season-rank-award-structural-visual",
            visualAwardVerified = SeasonRankAwardVisualClassifier.isSeasonRankAward(frame),
            purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            captureForegroundAuthorized = true,
            processWindowVerified = true,
            mode = "HUB",
            currentGameSessionReady = false,
            activeGame = false,
            activeQueue = false,
            terminalOrResult = false,
            working = true,
            paused = false,
            stateStillCurrent = true,
        ), "the GameStarter fresh-restart probe remains eligible while current Power.log is empty")

        assertFalse(StartupInputAuthorizationPolicy.allows(
            action = SeasonRankAwardRecoveryPolicy.DISPATCH_ACTION,
            currentSessionReady = false,
            freshOnlineMenuReady = false,
        ), "classification alone does not authorize dispatch")
        assertTrue(StartupInputAuthorizationPolicy.allows(
            action = SeasonRankAwardRecoveryPolicy.DISPATCH_ACTION,
            currentSessionReady = false,
            freshOnlineMenuReady = false,
            seasonRankAwardContinuePermitValid = true,
        ), "the exact one-use Continue permit supplies only this reward action")
        for (action in listOf("mouse.left", "strategy.card.play", "matchmaking.start", "surrender.request")) {
            assertFalse(StartupInputAuthorizationPolicy.allows(
                action = action,
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                seasonRankAwardContinuePermitValid = true,
            ), "the Continue permit cannot authorize $action")
        }
        val clicked = mutableListOf<Point>()
        val dispatched = SeasonRankAwardRecoveryPolicy.dispatchFromUpstreamRecoveryCallsite(
            screenKind = screen,
            detectionEvidence = "season-rank-award-structural-visual",
            windowBounds = Rectangle(0, 0, 1920, 1080),
            captureAuthorityValid = true,
            foregroundVerified = true,
            mode = "HUB",
            currentGameSessionReady = false,
            activeGame = false,
            activeQueue = false,
            terminalOrResult = false,
            working = true,
            paused = false,
        ) { clicked += it; true }
        assertTrue(dispatched.accepted)
        assertEquals(Point(960, 885), clicked.single())
        assertTrue(ActionDispatchGate.dispatchSeasonRankAwardContinue("HUB") {
            assertTrue(ActionDispatchGate.isSeasonRankAwardContinueDispatchScoped())
            assertEquals("HUB", ActionDispatchGate.seasonRankAwardDispatchMode())
            true
        })
    }

    @Test
    fun `blank Continue on five closed chests is not visual authority for reward dispatch`() {
        val frame = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/season-rank-reward-chests-live-20261101-053007.png",
        )))
        val evidence = SeasonRankAwardRewardsVisualClassifier.inspect(frame)
        assertTrue(evidence.isRewardPresentation, "the closed-chest layout retains its dedicated modal identity")
        assertFalse(evidence.isRewardContinueReady,
            "a gold outer rim cannot substitute for the blank inner Continue label: $evidence")
        assertFalse(StartupQuestOverlayVisualClassifier.isQuestOverlay(frame),
            "the five-chest page is not an event-quest modal")
        assertEquals("SEASON_RANK_AWARD_REWARDS", UpstreamScreenStateRecovery.classifyImageForTest("", frame),
            "retain the reward-modal identity so the broad Event Quest classifier cannot claim this frame")
        assertEquals("season-rank-award-five-chests-continue-not-visible",
            UpstreamScreenStateRecovery.classificationEvidenceForImageTest("", frame))
        val screen = "SEASON_RANK_AWARD_REWARDS"
        val stageGate = SeasonRankAwardRecoveryAttemptGate()
        val awardAction = SeasonRankAwardRecoveryPolicy.DISPATCH_ACTION
        val chestAction = SeasonRankAwardRecoveryPolicy.OPEN_CHEST_ACTION
        val rewardsAction = SeasonRankAwardRecoveryPolicy.REWARDS_CONTINUE_ACTION
        assertTrue(stageGate.tryBegin(53952L, "hwnd-live", awardAction))
        stageGate.resolve(53952L, "hwnd-live", awardAction, accepted = true)
        assertTrue(stageGate.tryBegin(53952L, "hwnd-live", chestAction),
            "the fresh chest stage is independent of the accepted award Continue")
        stageGate.resolve(53952L, "hwnd-live", chestAction, accepted = true)
        assertFalse(stageGate.tryBegin(53952L, "hwnd-live", awardAction),
            "accepted award Continue remains non-repeatable")
        assertFalse(stageGate.tryBegin(53952L, "hwnd-live", chestAction),
            "accepted chest-open remains non-repeatable")
        assertTrue(stageGate.tryBegin(53952L, "hwnd-live", rewardsAction),
            "the fresh exact reward-presentation stage has its own action budget")
        assertFalse(stageGate.tryBegin(53952L, "hwnd-live", rewardsAction),
            "parallel reward probes cannot duplicate the same stage input")
        val questFixture = fixture("event-quest-overlay-deck-selection-20261029.png")
        assertTrue(StartupQuestOverlayVisualClassifier.isQuestOverlay(questFixture))
        assertEquals("EVENT_QUEST_OVERLAY", UpstreamScreenStateRecovery.classifyImageForTest("", questFixture))
        val earlierRewardFrame = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/season-rank-reward-chests-live-20261101-045150.png",
        )))
        assertTrue(SeasonRankAwardRewardsVisualClassifier.inspect(earlierRewardFrame).isRewardPresentation)
        assertFalse(SeasonRankAwardRewardsVisualClassifier.inspect(earlierRewardFrame).isRewardContinueReady)
        val initialRewardFrame = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/season-rank-reward-chests-live-20261101-041328.png",
        )))
        assertTrue(SeasonRankAwardRewardsVisualClassifier.inspect(initialRewardFrame).isRewardPresentation)
        assertFalse(SeasonRankAwardRewardsVisualClassifier.inspect(initialRewardFrame).isRewardContinueReady)

        // The remaining assertions exercise the policy's exact-action boundary
        // with a positive authority token. Real blank-button captures never
        // receive this token from the production visual classifier.
        val visualProof = true
        assertTrue(SeasonRankAwardRecoveryPolicy.allowsDedicatedRewardsContinueStartupRecovery(
            screenKind = screen,
            detectionEvidence = "season-rank-award-rewards-five-chests-and-labeled-continue-structural-visual",
            visualRewardsVerified = visualProof,
            purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            captureForegroundAuthorized = true,
            processWindowVerified = true,
            mode = "HUB",
            currentGameSessionReady = false,
            activeGame = false,
            activeQueue = false,
            terminalOrResult = false,
            working = true,
            paused = false,
            stateStillCurrent = true,
        ), "fresh exact reward visual is eligible without current-session Power.log readiness")

        val clicked = mutableListOf<Point>()
        val result = SeasonRankAwardRecoveryPolicy.dispatchRewardsContinueFromUpstreamRecoveryCallsite(
            screenKind = screen,
            detectionEvidence = "season-rank-award-rewards-five-chests-and-labeled-continue-structural-visual",
            visualRewardsVerified = visualProof,
            windowBounds = Rectangle(0, 0, 1920, 1080),
            captureAuthorityValid = true,
            foregroundVerified = true,
            mode = "HUB",
            currentGameSessionReady = false,
            activeGame = false,
            activeQueue = false,
            terminalOrResult = false,
            working = true,
            paused = false,
        ) { clicked += it; true }
        assertTrue(result.accepted)
        assertEquals(Point(960, 885), clicked.single(), "only the visible bottom-center Continue is clicked")
        stageGate.resolve(53952L, "hwnd-live", rewardsAction, accepted = result.accepted)
        assertFalse(stageGate.tryBegin(53952L, "hwnd-live", rewardsAction),
            "accepted reward Continue is not retried until a verified fresh HUB chooser")

        assertFalse(StartupInputAuthorizationPolicy.allows(
            action = SeasonRankAwardRecoveryPolicy.REWARDS_CONTINUE_ACTION,
            currentSessionReady = false,
            freshOnlineMenuReady = false,
        ))
        assertTrue(StartupInputAuthorizationPolicy.allows(
            action = SeasonRankAwardRecoveryPolicy.REWARDS_CONTINUE_ACTION,
            currentSessionReady = false,
            freshOnlineMenuReady = false,
            seasonRankAwardRewardsContinuePermitValid = true,
        ))
        for (action in listOf("mouse.left", "strategy.card.play", "matchmaking.start", "surrender.request")) {
            assertFalse(StartupInputAuthorizationPolicy.allows(
                action = action,
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                seasonRankAwardRewardsContinuePermitValid = true,
            ), "reward Continue authority cannot authorize $action")
        }
        StartupQuestOverlayRecoveryFence.begin()
        try {
            assertTrue(ActionDispatchGate.dispatchSeasonRankAwardRewardsContinue("HUB") {
                assertTrue(ActionDispatchGate.isSeasonRankAwardRewardsContinueDispatchScoped())
                assertFalse(ActionDispatchGate.isSeasonRankAwardContinueDispatchScoped())
                assertEquals("HUB", ActionDispatchGate.seasonRankAwardDispatchMode())
                assertFalse(ActionDispatchGate.isStartupQuestRecoveryFenceBlocking(
                    SeasonRankAwardRecoveryPolicy.REWARDS_CONTINUE_ACTION,
                ), "the exact reward action alone may pass its event-overlay fence")
                assertTrue(ActionDispatchGate.isStartupQuestRecoveryFenceBlocking("mouse.left"))
                assertTrue(ActionDispatchGate.isStartupQuestRecoveryFenceBlocking("matchmaking.start"))
                assertTrue(StartupInputAuthorizationPolicy.allows(
                    action = SeasonRankAwardRecoveryPolicy.REWARDS_CONTINUE_ACTION,
                    currentSessionReady = false,
                    freshOnlineMenuReady = false,
                    seasonRankAwardRewardsContinuePermitValid =
                        ActionDispatchGate.isSeasonRankAwardRewardsContinueDispatchScoped(),
                ))
                assertFalse(StartupInputAuthorizationPolicy.allows(
                    action = "matchmaking.start",
                    currentSessionReady = false,
                    freshOnlineMenuReady = false,
                    seasonRankAwardRewardsContinuePermitValid = true,
                ))
                true
            })
            assertFalse(ActionDispatchGate.isSeasonRankAwardRewardsContinueDispatchScoped(),
                "the exact reward action scope expires immediately")
        } finally {
            StartupQuestOverlayRecoveryFence.resolve()
        }
        assertEquals("game-queue-or-terminal-fence", SeasonRankAwardRecoveryPolicy
            .dispatchRewardsContinueFromUpstreamRecoveryCallsite(
                screenKind = screen,
                detectionEvidence = "season-rank-award-rewards-five-chests-and-labeled-continue-structural-visual",
                visualRewardsVerified = visualProof,
                windowBounds = Rectangle(0, 0, 1920, 1080),
                captureAuthorityValid = true,
                foregroundVerified = true,
                mode = "HUB",
                currentGameSessionReady = false,
                activeGame = false,
                activeQueue = true,
                terminalOrResult = false,
                working = true,
                paused = false,
            ) { error("active queue must not dispatch") }.reason)

        fun blockedRewardAttempt(
            kind: String = screen,
            proof: String = "season-rank-award-rewards-five-chests-and-labeled-continue-structural-visual",
            visual: Boolean = true,
            capture: Boolean = true,
            foreground: Boolean = true,
            mode: String? = "HUB",
            sessionReady: Boolean = false,
            game: Boolean = false,
            queue: Boolean = false,
            terminal: Boolean = false,
            working: Boolean = true,
            paused: Boolean = false,
        ) = SeasonRankAwardRecoveryPolicy.dispatchRewardsContinueFromUpstreamRecoveryCallsite(
            screenKind = kind,
            detectionEvidence = proof,
            visualRewardsVerified = visual,
            windowBounds = Rectangle(0, 0, 1920, 1080),
            captureAuthorityValid = capture,
            foregroundVerified = foreground,
            mode = mode,
            currentGameSessionReady = sessionReady,
            activeGame = game,
            activeQueue = queue,
            terminalOrResult = terminal,
            working = working,
            paused = paused,
        ) { error("blocked reward context must not dispatch") }
        assertEquals("reward-screen-identity-unverified", blockedRewardAttempt(kind = "EVENT_QUEST_OVERLAY").reason)
        assertEquals("reward-screen-identity-unverified", blockedRewardAttempt(proof = "weak-ocr-only").reason)
        assertEquals("reward-screen-identity-unverified", blockedRewardAttempt(visual = false).reason)
        assertEquals("capture-authority-invalid", blockedRewardAttempt(capture = false).reason)
        assertEquals("foreground-unverified", blockedRewardAttempt(foreground = false).reason)
        assertEquals("unsafe-menu-or-current-session", blockedRewardAttempt(mode = "LOGIN").reason)
        assertEquals("unsafe-menu-or-current-session", blockedRewardAttempt(sessionReady = true).reason)
        assertEquals("game-queue-or-terminal-fence", blockedRewardAttempt(game = true).reason)
        assertEquals("game-queue-or-terminal-fence", blockedRewardAttempt(terminal = true).reason)
        assertEquals("not-working-or-paused", blockedRewardAttempt(working = false).reason)
        assertEquals("not-working-or-paused", blockedRewardAttempt(paused = true).reason)
    }

    @Test
    fun `latest live five chest captures reject blank Continue and send no input`() {
        val liveCaptures = listOf(
            "season-rank-five-chests-blank-continue-live-20261101-084012.png",
            "season-rank-five-chests-blank-continue-live-20261101-084729.png",
        ).map(::fixture)

        liveCaptures.forEachIndexed { index, frame ->
            val visual = SeasonRankAwardRewardsVisualClassifier.inspect(frame)
            assertTrue(visual.rewardChestRatios.count { it >= .018 } >= 4,
                "capture $index contains the five chest layout: $visual")
            assertTrue(visual.activeContinueRatio < .25,
                "capture $index has no visible inner Continue label: $visual")
            assertTrue(visual.isRewardPresentation,
                "the screen identity remains known, protecting it from broad overlay recovery")
            assertFalse(visual.isRewardContinueReady,
                "a warm outer rim around a blank control must not authorize the reward Continue")
            assertEquals("SEASON_RANK_AWARD_REWARDS", UpstreamScreenStateRecovery.classifyImageForTest("", frame))
            assertEquals("season-rank-award-five-chests-continue-not-visible",
                UpstreamScreenStateRecovery.classificationEvidenceForImageTest("", frame))

            val clicked = mutableListOf<Point>()
            val result = SeasonRankAwardRecoveryPolicy.dispatchRewardsContinueFromUpstreamRecoveryCallsite(
                screenKind = "SEASON_RANK_AWARD_REWARDS",
                detectionEvidence = "season-rank-award-rewards-five-chests-and-labeled-continue-structural-visual",
                visualRewardsVerified = visual.isRewardContinueReady,
                windowBounds = Rectangle(0, 0, 1920, 1080),
                captureAuthorityValid = true,
                foregroundVerified = true,
                mode = "HUB",
                currentGameSessionReady = false,
                activeGame = false,
                activeQueue = false,
                terminalOrResult = false,
                working = true,
                paused = false,
            ) { clicked += it; true }
            assertFalse(result.attempted, "capture $index cannot authorize an unlabeled button click")
            assertEquals("reward-screen-identity-unverified", result.reason)
            assertTrue(clicked.isEmpty(), "capture $index must produce no input")
        }
    }

    @Test
    fun `fresh exact award Continue passes final boundary before current Power log exists`() {
        val fixture = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/season-rank-award-20261101.png",
        )))
        val detection = requireNotNull(UpstreamScreenStateRecovery.classifyImageForTest("", fixture))
        assertEquals("SEASON_RANK_AWARD", detection)
        assertTrue(SeasonRankAwardVisualClassifier.inspect(fixture).isSeasonRankAward)

        val context = safeContext()
        assertFalse(context.currentGameSessionReady, "the current Power.log is empty")
        assertNull(SeasonRankAwardRecoveryPolicy.continueDispatchBlockReason(context))

        val clicked = mutableListOf<Point>()
        val result = SeasonRankAwardRecoveryPolicy.dispatchFromUpstreamRecoveryCallsite(
            screenKind = detection,
            detectionEvidence = "season-rank-award-structural-visual",
            windowBounds = Rectangle(0, 0, 1920, 1080),
            captureAuthorityValid = true,
            foregroundVerified = true,
            mode = "HUB",
            currentGameSessionReady = false,
            activeGame = false,
            activeQueue = false,
            terminalOrResult = false,
            working = true,
            paused = false,
        ) { point -> clicked += point; true }

        assertTrue(result.attempted)
        assertTrue(result.accepted)
        assertEquals(Point(960, 885), clicked.single(), "only the captured Continue target is dispatched")
    }

    @Test
    fun `final boundary reports every safety fence and preserves active game and queue rejection`() {
        val safe = safeContext()
        assertNull(SeasonRankAwardRecoveryPolicy.continueDispatchBlockReason(safe))
        assertEquals("exact-award-continue-scope-missing",
            SeasonRankAwardRecoveryPolicy.continueDispatchBlockReason(safe.copy(scopedToExactAwardContinue = false)))
        assertEquals("process-changed",
            SeasonRankAwardRecoveryPolicy.continueDispatchBlockReason(safe.copy(currentProcessId = 9)))
        assertEquals("active-game",
            SeasonRankAwardRecoveryPolicy.continueDispatchBlockReason(safe.copy(activeGame = true)))
        assertEquals("next-mode-gameplay",
            SeasonRankAwardRecoveryPolicy.continueDispatchBlockReason(safe.copy(nextModeGameplay = true)))
        assertEquals("active-matchmaking-screen",
            SeasonRankAwardRecoveryPolicy.continueDispatchBlockReason(safe.copy(activeQueueScreen = true)))
        assertEquals("active-matchmaking-entry-guard",
            SeasonRankAwardRecoveryPolicy.continueDispatchBlockReason(safe.copy(activeQueueEntryGuard = true)))
        assertEquals("rank-surrender-pending",
            SeasonRankAwardRecoveryPolicy.continueDispatchBlockReason(safe.copy(rankSurrenderPending = true)))
        assertEquals("paused",
            SeasonRankAwardRecoveryPolicy.continueDispatchBlockReason(safe.copy(paused = true)))
    }

    @Test
    fun `continue boundary has an explicit reason for every ordered rejection branch`() {
        val safe = safeContext()
        val cases = listOf(
            "wrong-action" to safe.copy(action = "mouse.left"),
            "exact-award-continue-scope-missing" to safe.copy(scopedToExactAwardContinue = false),
            "process-changed" to safe.copy(expectedProcessId = 0),
            "process-changed" to safe.copy(currentProcessId = null),
            "window-invalid" to safe.copy(windowValid = false),
            "window-owner-mismatch" to safe.copy(windowVerified = false),
            "unsafe-menu-or-current-session" to safe.copy(mode = "LOGIN"),
            "unsafe-menu-or-current-session" to safe.copy(currentGameSessionReady = true),
            "active-game" to safe.copy(activeGame = true),
            "current-mode-gameplay" to safe.copy(currentModeGameplay = true),
            "next-mode-gameplay" to safe.copy(nextModeGameplay = true),
            "terminal-or-result" to safe.copy(terminalOrResult = true),
            "active-matchmaking-screen" to safe.copy(activeQueueScreen = true),
            "active-matchmaking-entry-guard" to safe.copy(activeQueueEntryGuard = true),
            "rank-surrender-pending" to safe.copy(rankSurrenderPending = true),
            "terminal-cleanup-pending" to safe.copy(terminalCleanupPending = true),
            "not-working" to safe.copy(working = false),
            "paused" to safe.copy(paused = true),
        )
        cases.forEach { (expected, context) ->
            assertEquals(
                expected,
                SeasonRankAwardRecoveryPolicy.continueDispatchBlockReason(context),
                "ordered Continue rejection reason for context=$context",
            )
        }
    }

    @Test
    fun `Continue exception cannot authorize generic mouse matchmaking or rank input`() {
        val specialPermit = true
        assertTrue(StartupInputAuthorizationPolicy.allows(
            action = SeasonRankAwardRecoveryPolicy.DISPATCH_ACTION,
            currentSessionReady = false,
            freshOnlineMenuReady = false,
            seasonRankAwardContinuePermitValid = specialPermit,
        ))
        for (action in listOf("mouse.left", "game.card.play", "matchmaking.start", "surrender.request")) {
            assertFalse(StartupInputAuthorizationPolicy.allows(
                action = action,
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                seasonRankAwardContinuePermitValid = specialPermit,
            ), "the award Continue permit must not authorize $action")
        }

        val rejected = SeasonRankAwardRecoveryPolicy.dispatchFromUpstreamRecoveryCallsite(
            screenKind = "SEASON_RANK_AWARD",
            detectionEvidence = "season-rank-award-structural-visual",
            windowBounds = Rectangle(0, 0, 1920, 1080),
            captureAuthorityValid = false,
            foregroundVerified = true,
            mode = "HUB",
            currentGameSessionReady = false,
            activeGame = false,
            activeQueue = false,
            terminalOrResult = false,
            working = true,
            paused = false,
        ) { error("no click is allowed without fresh capture authority") }
        assertFalse(rejected.attempted)
        assertEquals("capture-authority-invalid", rejected.reason)
    }

    @Test
    fun `fresh award chest image is distinguished from first award and AppStore notice`() {
        val chest = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/season-rank-award-chest-20261101.png",
        )))
        val award = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/season-rank-award-20261101.png",
        )))
        val appStore = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/fixtures/screen-recovery/amazon-appstore-sunset-notice-runtime-20261026-123653.png",
        )))

        assertEquals("SEASON_RANK_AWARD_CHEST", UpstreamScreenStateRecovery.classifyImageForTest("", chest))
        assertEquals("SEASON_RANK_AWARD", UpstreamScreenStateRecovery.classifyImageForTest("", award))
        assertEquals("AMAZON_APPSTORE_NOTICE", UpstreamScreenStateRecovery.classifyImageForTest("", appStore))
        assertFalse(SeasonRankAwardRewardsVisualClassifier.isRewardPresentation(chest),
            "the single chest-open screen must not be mistaken for the five-chest Continue presentation")
        assertFalse(SeasonRankAwardRewardsVisualClassifier.isRewardPresentation(award),
            "the first rank-award presentation must not be mistaken for the post-open reward page")
        assertFalse(SeasonRankAwardRewardsVisualClassifier.isRewardPresentation(appStore),
            "the AppStore notice must not be mistaken for a season reward page")
        val evidence = SeasonRankAwardChestVisualClassifier.inspect(chest)
        assertTrue(evidence.isSeasonRankAwardChest)
        assertFalse(SeasonRankAwardChestVisualClassifier.inspect(award).isSeasonRankAwardChest)
        assertFalse(SeasonRankAwardChestVisualClassifier.inspect(appStore).isSeasonRankAwardChest)
    }

    @Test
    fun `fresh chest open action targets only chest and retains all startup safety fences`() {
        val fixture = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/season-rank-award-chest-20261101.png",
        )))
        assertEquals("SEASON_RANK_AWARD_CHEST", UpstreamScreenStateRecovery.classifyImageForTest("", fixture))
        val clicked = mutableListOf<Point>()
        val result = SeasonRankAwardRecoveryPolicy.dispatchChestOpenFromUpstreamRecoveryCallsite(
            screenKind = "SEASON_RANK_AWARD_CHEST",
            detectionEvidence = "season-rank-award-chest-structural-visual",
            windowBounds = Rectangle(0, 0, 1920, 1080),
            captureAuthorityValid = true,
            foregroundVerified = true,
            mode = "HUB",
            currentGameSessionReady = false,
            activeGame = false,
            activeQueue = false,
            terminalOrResult = false,
            working = true,
            paused = false,
        ) { clicked += it; true }
        assertTrue(result.attempted)
        assertTrue(result.accepted)
        assertEquals(Point(960, 583), clicked.single(), "only the reward chest center is clicked")

        fun chestAttempt(
            capture: Boolean = true,
            foreground: Boolean = true,
            game: Boolean = false,
            queue: Boolean = false,
            terminal: Boolean = false,
            working: Boolean = true,
            paused: Boolean = false,
        ) = SeasonRankAwardRecoveryPolicy.dispatchChestOpenFromUpstreamRecoveryCallsite(
            screenKind = "SEASON_RANK_AWARD_CHEST",
            detectionEvidence = "season-rank-award-chest-structural-visual",
            windowBounds = Rectangle(0, 0, 1920, 1080),
            captureAuthorityValid = capture,
            foregroundVerified = foreground,
            mode = "HUB",
            currentGameSessionReady = false,
            activeGame = game,
            activeQueue = queue,
            terminalOrResult = terminal,
            working = working,
            paused = paused,
        ) { error("unsafe chest context must not dispatch") }
        assertEquals("capture-authority-invalid", chestAttempt(capture = false).reason)
        assertEquals("foreground-unverified", chestAttempt(foreground = false).reason)
        assertEquals("game-queue-or-terminal-fence", chestAttempt(game = true).reason)
        assertEquals("game-queue-or-terminal-fence", chestAttempt(queue = true).reason)
        assertEquals("game-queue-or-terminal-fence", chestAttempt(terminal = true).reason)
        assertEquals("not-working-or-paused", chestAttempt(working = false).reason)
        assertEquals("not-working-or-paused", chestAttempt(paused = true).reason)
        assertFalse(StartupInputAuthorizationPolicy.allows(
            action = SeasonRankAwardRecoveryPolicy.OPEN_CHEST_ACTION,
            currentSessionReady = false,
            freshOnlineMenuReady = false,
            seasonRankAwardContinuePermitValid = true,
        ), "Continue scope cannot authorize chest-open")
        assertFalse(StartupInputAuthorizationPolicy.allows(
            action = SeasonRankAwardRecoveryPolicy.DISPATCH_ACTION,
            currentSessionReady = false,
            freshOnlineMenuReady = false,
            seasonRankAwardChestOpenPermitValid = true,
        ), "chest-open scope cannot authorize Continue")

        assertTrue(ActionDispatchGate.dispatchSeasonRankAwardChestOpen {
            assertTrue(ActionDispatchGate.isSeasonRankAwardChestOpenDispatchScoped())
            assertFalse(ActionDispatchGate.isSeasonRankAwardContinueDispatchScoped())
            assertTrue(StartupInputAuthorizationPolicy.allows(
                action = SeasonRankAwardRecoveryPolicy.OPEN_CHEST_ACTION,
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                seasonRankAwardChestOpenPermitValid = ActionDispatchGate.isSeasonRankAwardChestOpenDispatchScoped(),
            ))
            assertFalse(StartupInputAuthorizationPolicy.allows(
                action = SeasonRankAwardRecoveryPolicy.DISPATCH_ACTION,
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                seasonRankAwardChestOpenPermitValid = true,
            ))
            true
        })
        assertFalse(ActionDispatchGate.isSeasonRankAwardChestOpenDispatchScoped(),
            "the chest capability must expire immediately after this dispatch")
    }

    @Test
    fun `fresh chest capture earns its dedicated startup recovery candidate without generic menu proof`() {
        val chest = fixture("season-rank-award-chest-20261101.png")
        val detection = requireNotNull(UpstreamScreenStateRecovery.classifyImageForTest("", chest))
        assertEquals("SEASON_RANK_AWARD_CHEST", detection)

        fun candidate(
            screenKind: String? = detection,
            evidence: String? = "season-rank-award-chest-structural-visual",
            visualVerified: Boolean = SeasonRankAwardChestVisualClassifier.isSeasonRankAwardChest(chest),
            purpose: ScreenRecoveryCapturePurpose = ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
            foregroundAuthorized: Boolean = true,
            processWindowVerified: Boolean = true,
            mode: String? = "HUB",
            currentSessionReady: Boolean = false,
            activeGame: Boolean = false,
            activeQueue: Boolean = false,
            terminal: Boolean = false,
            working: Boolean = true,
            paused: Boolean = false,
            stillCurrent: Boolean = true,
        ) = SeasonRankAwardRecoveryPolicy.allowsDedicatedChestOpenStartupRecovery(
            screenKind = screenKind,
            detectionEvidence = evidence,
            visualChestVerified = visualVerified,
            purpose = purpose,
            captureForegroundAuthorized = foregroundAuthorized,
            processWindowVerified = processWindowVerified,
            mode = mode,
            currentGameSessionReady = currentSessionReady,
            activeGame = activeGame,
            activeQueue = activeQueue,
            terminalOrResult = terminal,
            working = working,
            paused = paused,
            stateStillCurrent = stillCurrent,
        )

        assertTrue(candidate(), "an empty current Power.log does not block the exact, fresh chest action")
        val rejected = mutableListOf<Pair<String, () -> Boolean>>()
        rejected.add("wrong screen kind" to { candidate(screenKind = "AMAZON_APPSTORE_NOTICE") })
        rejected.add("wrong detection evidence" to { candidate(evidence = "generic-amazon-visual") })
        rejected.add("unverified chest visual" to { candidate(visualVerified = false) })
        val dedicatedPurposes = setOf(
            ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
            ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
        )
        enumValues<ScreenRecoveryCapturePurpose>().filterNot { it in dedicatedPurposes }.forEach { purpose ->
            rejected.add("disallowed probe purpose $purpose" to { candidate(purpose = purpose) })
        }
        rejected.add("foreground capture unauthorized" to { candidate(foregroundAuthorized = false) })
        rejected.add("process/window unverified" to { candidate(processWindowVerified = false) })
        rejected.add("wrong mode" to { candidate(mode = "LOGIN") })
        rejected.add("current session ready" to { candidate(currentSessionReady = true) })
        rejected.add("active game" to { candidate(activeGame = true) })
        rejected.add("active queue" to { candidate(activeQueue = true) })
        rejected.add("terminal/result" to { candidate(terminal = true) })
        rejected.add("not working" to { candidate(working = false) })
        rejected.add("paused" to { candidate(paused = true) })
        rejected.add("stale state" to { candidate(stillCurrent = false) })
        rejected.forEach { (reason, allows) -> assertFalse(allows(), reason) }

        assertFalse(StartupInputAuthorizationPolicy.allows(
            action = SeasonRankAwardRecoveryPolicy.OPEN_CHEST_ACTION,
            currentSessionReady = false,
            freshOnlineMenuReady = false,
        ), "candidate eligibility is not the one-use dispatch capability")
        for (action in listOf("mouse.left", "strategy.card.play", "matchmaking.start", "surrender.request")) {
            assertFalse(StartupInputAuthorizationPolicy.allows(
                action = action,
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                seasonRankAwardChestOpenPermitValid = true,
            ), "the chest-specific permit must not authorize $action")
        }
    }
    @Test
    fun `two reward overlays require a fresh hub chooser before matchmaking proof`() {
        val continueImage = fixture("season-rank-award-20261101.png")
        val chestImage = fixture("season-rank-award-chest-20261101.png")
        val chooserImage = fixture("hub-mode-chooser-live-20261029-035630.png")
        assertEquals("SEASON_RANK_AWARD", UpstreamScreenStateRecovery.classifyImageForTest("", continueImage))
        assertEquals("SEASON_RANK_AWARD_CHEST", UpstreamScreenStateRecovery.classifyImageForTest("", chestImage))
        assertEquals("TOURNAMENT", UpstreamScreenStateRecovery.classifyImageForTest("", chooserImage))

        val bounds = Rectangle(0, 0, 1920, 1080)
        val continueResult = SeasonRankAwardRecoveryPolicy.dispatchFromUpstreamRecoveryCallsite(
            screenKind = "SEASON_RANK_AWARD",
            detectionEvidence = "season-rank-award-structural-visual",
            windowBounds = bounds,
            captureAuthorityValid = true,
            foregroundVerified = true,
            mode = "HUB",
            currentGameSessionReady = false,
            activeGame = false,
            activeQueue = false,
            terminalOrResult = false,
            working = true,
            paused = false,
        ) { it == SeasonRankAwardRecoveryPolicy.continueTarget(bounds) }
        assertTrue(continueResult.accepted)
        val chestResult = SeasonRankAwardRecoveryPolicy.dispatchChestOpenFromUpstreamRecoveryCallsite(
            screenKind = "SEASON_RANK_AWARD_CHEST",
            detectionEvidence = "season-rank-award-chest-structural-visual",
            windowBounds = bounds,
            captureAuthorityValid = true,
            foregroundVerified = true,
            mode = "HUB",
            currentGameSessionReady = false,
            activeGame = false,
            activeQueue = false,
            terminalOrResult = false,
            working = true,
            paused = false,
        ) { it == SeasonRankAwardRecoveryPolicy.chestOpenTarget(bounds) }
        assertTrue(chestResult.accepted)

        StartupInputReadiness.resetForTest()
        try {
            assertFalse(StartupInputReadiness.isHubTraditionalBattleSelectionReady(41440, "hwnd", "HUB"),
                "award clicks do not mint hub chooser proof")
            assertTrue(StartupHubModeChooserPolicy.canAuthorizeTraditionalBattleSelection(
                purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
                currentSessionReady = false,
                screenKind = "TOURNAMENT",
                confidence = 94,
                evidence = "hub-mode-chooser-visual",
                currentMode = "HUB",
                modalVisible = false,
                activeGame = false,
                activeMatchmaking = false,
                trustedCurrentWindow = true,
            ))
            StartupInputReadiness.recordHubModeChooser(41440, "hwnd", now = 10_000L)
            assertTrue(StartupInputReadiness.isHubTraditionalBattleSelectionReady(41440, "hwnd", "HUB", 10_001L))
            assertFalse(StartupInputReadiness.isDeckSelectionStartReady(41440, "hwnd", now = 10_001L),
                "the hub chooser cannot authorize matchmaking Start; deck proof and rank preflight remain required")
        } finally {
            StartupInputReadiness.resetForTest()
        }
    }

    @Test
    fun `accepted reward action cannot repeat until a fresh hub chooser re-arms the stage`() {
        val gate = SeasonRankAwardRecoveryAttemptGate()
        val continueAction = SeasonRankAwardRecoveryPolicy.DISPATCH_ACTION
        val chestAction = SeasonRankAwardRecoveryPolicy.OPEN_CHEST_ACTION
        assertTrue(gate.tryBegin(41440L, "hwnd", continueAction))
        assertFalse(gate.tryBegin(41440L, "hwnd", continueAction), "parallel recovery cannot duplicate the click")
        gate.resolve(41440L, "hwnd", continueAction, accepted = false)
        assertTrue(gate.tryBegin(41440L, "hwnd", continueAction), "rejected OS input can be retried")
        gate.resolve(41440L, "hwnd", continueAction, accepted = true)
        assertFalse(gate.tryBegin(41440L, "hwnd", continueAction), "accepted SendInput is not a UI transition")
        assertTrue(gate.tryBegin(41440L, "hwnd", chestAction), "the distinct fresh chest frame has its own stage")
        gate.resolve(41440L, "hwnd", chestAction, accepted = true)
        assertFalse(gate.tryBegin(41440L, "hwnd", chestAction), "accepted chest input is not transition proof")
        gate.clearForFreshHubChooser(41440L, "other-window")
        assertFalse(gate.tryBegin(41440L, "hwnd", continueAction), "another HWND cannot reset the stage budget")
        gate.clearForFreshHubChooser(41440L, "hwnd")
        assertTrue(gate.tryBegin(41440L, "hwnd", continueAction), "only the fresh chooser releases this client budget")
    }

    @Test
    fun `rejected input retries same stage while new PID or HWND has an independent stage key`() {
        val gate = SeasonRankAwardRecoveryAttemptGate()
        val rewardsAction = SeasonRankAwardRecoveryPolicy.REWARDS_CONTINUE_ACTION
        assertEquals("process-unavailable", gate.tryBeginDetailed(null, "hwnd", rewardsAction).reason)
        assertEquals("window-unavailable", gate.tryBeginDetailed(41440L, null, rewardsAction).reason)
        assertEquals("unsupported-stage", gate.tryBeginDetailed(41440L, "hwnd", "generic.mouse").reason)

        assertTrue(gate.tryBeginDetailed(41440L, "hwnd", rewardsAction).allowed)
        gate.resolve(41440L, "hwnd", rewardsAction, accepted = false)
        assertTrue(gate.tryBeginDetailed(41440L, "hwnd", rewardsAction).allowed,
            "rejected SendInput does not consume this stage's one-use accepted-input budget")
        gate.resolve(41440L, "hwnd", rewardsAction, accepted = true)
        assertEquals("fresh-exact-screen-required", gate.tryBeginDetailed(41440L, "hwnd", rewardsAction).reason,
            "a reward retry requires a new exact authorized visual observation")
        assertTrue(gate.tryBeginDetailed(41441L, "hwnd", rewardsAction).allowed,
            "a new process gets a distinct client-stage key")
        assertTrue(gate.tryBeginDetailed(41440L, "new-hwnd", rewardsAction).allowed,
            "a new window gets a distinct client-stage key")
    }

    @Test
    fun `stuck five chest captures grant no reward Continue authority and retry gate remains bounded`() {
        val stalledCaptures = listOf(
            "season-rank-reward-chests-live-20261101-055733-stalled-after-accepted-input.png",
            "season-rank-reward-chests-live-20261101-062010-restarted-still-stuck.png",
            "season-rank-reward-chests-live-20261101-062342-post-chest-continue-stuck.png",
            "season-rank-five-chests-blank-continue-live-20261101-093648.png",
            "season-rank-five-chests-blank-continue-live-20261101-105355.png",
        ).map(::fixture)
        stalledCaptures.forEach { frame ->
            assertEquals("SEASON_RANK_AWARD_REWARDS", UpstreamScreenStateRecovery.classifyImageForTest("", frame))
            assertTrue(SeasonRankAwardRewardsVisualClassifier.isRewardPresentation(frame))
            assertFalse(SeasonRankAwardRewardsVisualClassifier.inspect(frame).isRewardContinueReady,
                "stalled five closed chest captures do not prove the bottom-center control is enabled")
        }

        // Exercise bounded attempt-gate mechanics independently from screenshot
        // authority. No current five-closed-chest capture supplies this token.
        val exactFreshAuthority = SeasonRankAwardRecoveryAttemptGate.hasFreshAuthorizedRewardsScreen(
            screenKind = "SEASON_RANK_AWARD_REWARDS",
            evidence = "season-rank-award-rewards-five-chests-and-labeled-continue-structural-visual",
            freshCaptureAuthorized = true,
            exactClientWindowVerified = true,
            foregroundVerified = true,
        )
        assertTrue(exactFreshAuthority)
        assertFalse(SeasonRankAwardRecoveryAttemptGate.hasFreshAuthorizedRewardsScreen(
            screenKind = "SEASON_RANK_AWARD_REWARDS",
            evidence = "season-rank-award-rewards-five-chests-and-labeled-continue-structural-visual",
            freshCaptureAuthorized = false,
            exactClientWindowVerified = true,
            foregroundVerified = true,
        ), "a stale/un-authorized capture cannot authorize a retry")
        assertFalse(SeasonRankAwardRecoveryAttemptGate.hasFreshAuthorizedRewardsScreen(
            screenKind = "SEASON_RANK_AWARD_REWARDS",
            evidence = "ocr-text",
            freshCaptureAuthorized = true,
            exactClientWindowVerified = true,
            foregroundVerified = true,
        ), "OCR or an unrelated screen cannot authorize a reward Continue retry")

        val gate = SeasonRankAwardRecoveryAttemptGate()
        val action = SeasonRankAwardRecoveryPolicy.REWARDS_CONTINUE_ACTION
        assertTrue(gate.tryBeginDetailed(54480L, "native@0x557b0970", action, 100_000L, exactFreshAuthority).allowed)
        gate.resolve(54480L, "native@0x557b0970", action, accepted = true, nowMs = 100_000L)

        assertEquals("accepted-reprobe-delay", gate.tryBeginDetailed(
            54480L, "native@0x557b0970", action, 129_999L, exactFreshAuthority,
        ).reason, "SendInput acceptance alone does not prove the Continue transition")
        val second = gate.tryBeginDetailed(54480L, "native@0x557b0970", action, 130_000L, exactFreshAuthority)
        assertTrue(second.allowed, "the same still-visible exact screen permits a bounded reattempt after 30s")
        gate.resolve(54480L, "native@0x557b0970", action, accepted = true, nowMs = 130_000L)

        assertTrue(gate.tryBeginDetailed(54480L, "native@0x557b0970", action, 160_000L, exactFreshAuthority).allowed)
        gate.resolve(54480L, "native@0x557b0970", action, accepted = true, nowMs = 160_000L)
        assertEquals("stage-attempt-limit", gate.tryBeginDetailed(
            54480L, "native@0x557b0970", action, 190_000L, exactFreshAuthority,
        ).reason, "three accepted-but-ineffective clicks cap recovery; no unbounded clicking")

        assertTrue(gate.tryBeginDetailed(35068L, "native@0x557b0970", action, 200_000L, exactFreshAuthority).allowed,
            "the post-restart capture is fresh for the new client process, while the old process budget stays capped")

        gate.clearForFreshHubChooser(54480L, "native@0x557b0970")
        assertTrue(gate.tryBeginDetailed(54480L, "native@0x557b0970", action, 190_001L).allowed,
            "only the independently observed hub chooser transition rearms the stage")
    }

    private fun fixture(name: String) = ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
        "/offline-ocr/screen-recovery/$name",
    )))

    private fun safeContext() = SeasonRankAwardRecoveryPolicy.ContinueDispatchContext(
        action = SeasonRankAwardRecoveryPolicy.DISPATCH_ACTION,
        scopedToExactAwardContinue = true,
        expectedProcessId = 41440,
        currentProcessId = 41440,
        windowVerified = true,
        mode = "HUB",
        currentGameSessionReady = false,
        activeGame = false,
        currentModeGameplay = false,
        nextModeGameplay = false,
        terminalOrResult = false,
        activeQueueScreen = false,
        activeQueueEntryGuard = false,
        rankSurrenderPending = false,
        terminalCleanupPending = false,
        working = true,
        paused = false,
        windowValid = true,
    )
}
