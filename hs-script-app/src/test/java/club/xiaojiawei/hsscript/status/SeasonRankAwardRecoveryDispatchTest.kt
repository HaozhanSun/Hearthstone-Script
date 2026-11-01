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
