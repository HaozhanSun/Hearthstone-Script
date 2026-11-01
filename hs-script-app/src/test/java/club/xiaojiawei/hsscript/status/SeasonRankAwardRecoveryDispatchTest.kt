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
