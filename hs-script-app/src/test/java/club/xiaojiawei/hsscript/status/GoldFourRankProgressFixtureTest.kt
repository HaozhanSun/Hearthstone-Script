package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.utils.TerminalPageCleanupCoordinator
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoldFourRankProgressFixtureTest {
    @Test
    fun `live Gold 4 screenshot is recognized by both production classifiers and remains an intermediate page`() {
        val bytes = fixtureBytes(GOLD_FOUR)
        assertEquals(GOLD_FOUR_SHA256, sha256(bytes))
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)

        val legacy = ScreenStateRecovery.classifyImageForResultFixture(image)
        val upstream = UpstreamScreenStateRecovery.classifyImageForResultFixture(image)
        assertEquals("RANK_PROGRESS_CONTINUATION", legacy, ScreenStateRecovery.resultVisualEvidenceForFixture(image))
        assertEquals("RANK_PROGRESS_CONTINUATION", upstream)
        assertEquals(null, ScreenStateRecovery.resultVisibilityForTest(legacy, 93))
        assertTrue(ResultPageEvidencePolicy.looksLikeRankProgressContinuationVisual(.049, .086, .318, .503))

        // Preserve the distinctions: defeat remains RESULT; the earlier Gold 3
        // reward remains rank progress under its original calibrated signature.
        val defeat = loadImage(DEFEAT)
        assertEquals("RESULT", ScreenStateRecovery.classifyImageForResultFixture(defeat))
        assertFalse(
            ResultPageEvidencePolicy.looksLikeRankProgressContinuationVisual(.040, .498, .431, .426),
            "the preceding defeat plaque must not be mistaken for rank progression",
        )
        assertEquals("RANK_PROGRESS_CONTINUATION", ScreenStateRecovery.classifyImageForResultFixture(loadImage(GOLD_THREE)))
    }

    @Test
    fun `rank page allows bounded click then enter and completes only after a fresh destination observation`() {
        val rankPage = loadImage(GOLD_FOUR)
        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = assertNotNull(coordinator.begin().ticket)

        repeat(PostResultRankProgressPolicy.MAX_CONTINUE_INPUTS) { index ->
            assertEquals("RANK_PROGRESS_CONTINUATION", ScreenStateRecovery.classifyImageForResultFixture(rankPage))
            val decision = PostResultRankProgressPolicy.decide(
                rankProgressVisible = true,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                rankProgressInputAttempts = coordinator.snapshot().rankProgressInputs,
            )
            assertEquals(PostResultRankProgressPolicy.Action.CONTINUE, decision)
            assertEquals(
                if (index == 0) PostResultRankProgressPolicy.Input.CENTER_CLICK
                else PostResultRankProgressPolicy.Input.KEYBOARD_ENTER,
                PostResultRankProgressPolicy.inputForAttempt(index + 1),
            )
            assertNotNull(coordinator.reserveRankProgressInput(ticket, PostResultRankProgressPolicy.MAX_CONTINUE_INPUTS))
            // The screenshot remains rank progress until a later fresh probe;
            // the queued input itself never confirms dismissal.
            assertEquals(TerminalPageCleanupCoordinator.State.RUNNING, coordinator.snapshot().state)
        }

        assertEquals(
            PostResultRankProgressPolicy.Action.INPUT_BUDGET_EXHAUSTED,
            PostResultRankProgressPolicy.decide(
                rankProgressVisible = true,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                rankProgressInputAttempts = coordinator.snapshot().rankProgressInputs,
            ),
        )
        val freshDestinationVisible = ScreenStateRecovery.resultVisibilityForTest("DECK_SELECTION", 90)
        assertEquals(false, freshDestinationVisible)
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = freshDestinationVisible,
                attempt = 4,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = coordinator.snapshot().inputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                destinationTransitionConfirmed = FreshPostResultDestinationPolicy.isConfirmed(
                    "DECK_SELECTION", 90, freshCaptureAuthorized = true,
                ),
            ),
        )
        assertTrue(coordinator.confirmDestination(ticket))
        assertEquals(TerminalPageCleanupCoordinator.State.COMPLETED, coordinator.snapshot().state)
    }

    private fun fixtureBytes(path: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(path)) { "Missing screenshot fixture $path" }.use { it.readBytes() }

    private fun loadImage(path: String) = requireNotNull(ImageIO.read(ByteArrayInputStream(fixtureBytes(path))))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02X".format(it) }

    companion object {
        private const val GOLD_FOUR =
            "/club/xiaojiawei/hsscript/status/surrender/v580-gold4-rank-progress-20261006-000035-857.png"
        private const val GOLD_FOUR_SHA256 =
            "11272906A1307E4398F61A51DFF08A2C14E55DF942B2BF89EECEF15AB4C162F2"
        private const val GOLD_THREE =
            "/club/xiaojiawei/hsscript/status/surrender/v577-rank-up-gold3-20261005-221531-323.png"
        private const val DEFEAT =
            "/club/xiaojiawei/hsscript/status/surrender/v579-terminal-proof-unknown-postcheck-20261005-232525-573.png"
    }
}
