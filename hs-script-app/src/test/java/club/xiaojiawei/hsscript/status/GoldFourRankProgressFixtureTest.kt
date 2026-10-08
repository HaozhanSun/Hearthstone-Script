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
    fun `rank page allows bounded target clicks and completes only after a fresh destination observation`() {
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
                PostResultRankProgressPolicy.Input.CENTER_CLICK,
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

    @Test
    fun `v597 post-Enter capture remains rank reward and permits only the remaining bounded click`() {
        val postEnter = loadImage(V597_POST_ENTER)
        assertEquals(V597_POST_ENTER_SHA256, sha256(fixtureBytes(V597_POST_ENTER)))
        assertEquals(1920, postEnter.width)
        assertEquals(1080, postEnter.height)
        val evidence = ScreenStateRecovery.resultVisualEvidenceForFixture(postEnter)
        assertMetricNear(evidence, "continue", 0.046)
        assertMetricNear(evidence, "banner", 0.096)
        assertMetricNear(evidence, "centerDark", 0.269)
        assertMetricNear(evidence, "bannerWarm", 0.598)
        assertEquals("RANK_PROGRESS_CONTINUATION", ScreenStateRecovery.classifyImageForResultFixture(postEnter), evidence)
        assertEquals("RANK_PROGRESS_CONTINUATION", UpstreamScreenStateRecovery.classifyImageForResultFixture(postEnter))

        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = assertNotNull(coordinator.begin().ticket)
        val afterPriorEnter = requireNotNull(coordinator.nextProbe(ticket))
        // The fixture is the authorized current-client capture after the old
        // KEYBOARD_ENTER dispatch. The unchanged reward page is not success.
        assertEquals(
            PostResultRankProgressPolicy.Action.CONTINUE,
            PostResultRankProgressPolicy.decide(true, true, true, coordinator.snapshot().rankProgressInputs),
        )
        assertEquals(PostResultRankProgressPolicy.Input.CENTER_CLICK, PostResultRankProgressPolicy.inputForAttempt(1))
        assertNotNull(coordinator.reserveRankProgressInput(ticket, PostResultRankProgressPolicy.MAX_CONTINUE_INPUTS))
        assertEquals(TerminalPageCleanupCoordinator.State.RUNNING, coordinator.snapshot().state)
        assertTrue(afterPriorEnter > 0)

        // Even if the next authorized capture still shows the same page, the
        // second rank-specific input is the final allowed dispatch; it cannot
        // mark cleanup complete or create an unbounded retry loop.
        val afterRetry = requireNotNull(coordinator.nextProbe(ticket))
        assertEquals("RANK_PROGRESS_CONTINUATION", ScreenStateRecovery.classifyImageForResultFixture(postEnter))
        assertEquals(
            PostResultRankProgressPolicy.Action.CONTINUE,
            PostResultRankProgressPolicy.decide(true, true, true, coordinator.snapshot().rankProgressInputs),
        )
        assertEquals(PostResultRankProgressPolicy.Input.CENTER_CLICK, PostResultRankProgressPolicy.inputForAttempt(2))
        assertNotNull(coordinator.reserveRankProgressInput(ticket, PostResultRankProgressPolicy.MAX_CONTINUE_INPUTS))
        assertEquals(
            PostResultRankProgressPolicy.Action.INPUT_BUDGET_EXHAUSTED,
            PostResultRankProgressPolicy.decide(true, true, true, coordinator.snapshot().rankProgressInputs),
        )
        assertTrue(coordinator.fail(ticket, "rank-progress-input-budget-exhausted"))
        assertEquals(TerminalPageCleanupCoordinator.State.FAILED, coordinator.snapshot().state)
        assertTrue(afterRetry > afterPriorEnter)
    }

    @Test
    fun `v601 Gold 4 post-surrender reward is a safe rank-progress continuation despite garbled OCR`() {
        val bytes = fixtureBytes(V601_POST_SURRENDER)
        assertEquals(V601_POST_SURRENDER_SHA256, sha256(bytes))
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)

        val evidence = ScreenStateRecovery.resultVisualEvidenceForFixture(image)
        assertMetricNear(evidence, "continue", 0.043)
        assertMetricNear(evidence, "banner", 0.099)
        assertMetricNear(evidence, "centerDark", 0.263)
        assertMetricNear(evidence, "bannerWarm", 0.400)
        assertFalse(ResultPageEvidencePolicy.looksLikeResultVisual(0.043, 0.099, 0.263, 0.400))
        assertTrue(ResultPageEvidencePolicy.looksLikeRankProgressContinuationVisual(0.043, 0.099, 0.263, 0.400))

        assertEquals("RANK_PROGRESS_CONTINUATION", ScreenStateRecovery.classifyImageForResultFixture(image), evidence)
        assertEquals("RANK_PROGRESS_CONTINUATION", UpstreamScreenStateRecovery.classifyImageForResultFixture(image))
        val transition = requireNotNull(
            ScreenStateRecovery.recoveryTransitionForImageForTest(
                image = image,
                ocrText = "人人国人2W让(SEEAs入ss1本此击继续",
                targeted = emptyMap(),
            ),
        )
        assertEquals("RANK_PROGRESS_CONTINUATION", transition.screen)
        assertEquals("CONTINUE_WITH_TERMINAL_CAPABILITY", transition.action)
        assertFalse(transition.enterStrategy, "rank progress must wait for terminal cleanup rather than start ordinary play")
    }

    private fun fixtureBytes(path: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(path)) { "Missing screenshot fixture $path" }.use { it.readBytes() }

    private fun assertMetricNear(evidence: String, name: String, expected: Double) {
        val value = Regex("(?:^|\\s)${Regex.escape(name)}=([0-9]+\\.[0-9]+)")
            .find(evidence)?.groupValues?.get(1)?.toDouble()
        assertTrue(value != null && kotlin.math.abs(value - expected) < 0.001, "$name expected=$expected evidence=$evidence")
    }

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
        private const val V597_POST_ENTER =
            "/club/xiaojiawei/hsscript/status/surrender/v597-post-enter-same-rank-reward-20261007-205000-098.png"
        private const val V597_POST_ENTER_SHA256 =
            "BF175A7B2F9CCF4E14D082D5968F21F490F9B61A0206FE70462304E53BA422FE"
        private const val V601_POST_SURRENDER =
            "/club/xiaojiawei/hsscript/status/surrender/v601-gold4-rank-progress-20261007-232005-283.png"
        private const val V601_POST_SURRENDER_SHA256 =
            "73E37ECD7DA30E984444B036D858B3833AC09E66C942C6958A81F3BDE917BD8D"
    }
}
