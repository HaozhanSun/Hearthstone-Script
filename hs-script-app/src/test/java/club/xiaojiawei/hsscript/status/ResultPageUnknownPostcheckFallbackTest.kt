package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.TerminalPageCleanupCoordinator
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResultPageUnknownPostcheckFallbackTest {
    @Test
    fun `captured defeat page plus terminal proof permits only a bounded click-enter fallback after unknown postcheck`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream(DEFEAT_RESULT)).use { it.readBytes() }
        assertEquals(DEFEAT_RESULT_SHA256, sha256(bytes))
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)
        val metrics = ScreenStateRecovery.resultVisualEvidenceForFixture(image)
        assertEquals("RESULT", ScreenStateRecovery.classifyImageForResultFixture(image), metrics)
        assertEquals("RESULT", UpstreamScreenStateRecovery.classifyImageForResultFixture(image))
        assertTrue(metrics.contains("continue=0.04"), metrics)
        assertTrue(metrics.contains("banner=0.50"), metrics)
        assertTrue(metrics.contains("centerDark=0.42"), metrics)
        assertTrue(metrics.contains("bannerWarm=0.41"), metrics)
        assertFalse(ResultPageEvidencePolicy.looksLikeResultVisual(0.050, 0.096, 0.240, 0.437))
        assertTrue(ResultPageEvidencePolicy.looksLikeResultText("败北 点击继续"))

        val maxInputs = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS
        val firstUnknownProbe = ResultPageDismissalPolicy.decide(
            inWar = false,
            resultPageVisible = null,
            attempt = 3,
            maxAttempts = maxInputs,
            clickAttempts = 0,
            terminalCleanupAuthorized = true,
            captureAuthorized = true,
            priorResultPageConfirmed = true,
        )
        val secondUnknownProbe = ResultPageDismissalPolicy.decide(
            inWar = false,
            resultPageVisible = null,
            attempt = 4,
            maxAttempts = maxInputs,
            clickAttempts = 1,
            terminalCleanupAuthorized = true,
            captureAuthorized = true,
            priorResultPageConfirmed = true,
        )
        val thirdUnknownProbe = ResultPageDismissalPolicy.decide(
            inWar = false,
            resultPageVisible = null,
            attempt = 5,
            maxAttempts = maxInputs,
            clickAttempts = 2,
            terminalCleanupAuthorized = true,
            captureAuthorized = true,
            priorResultPageConfirmed = true,
        )

        assertEquals(ResultPageDismissalPolicy.Decision.DISPATCH_CLICK, firstUnknownProbe)
        assertEquals(ResultPageDismissalPolicy.Decision.DISPATCH_CLICK, secondUnknownProbe)
        assertEquals(ResultPageDismissalPolicy.Decision.EXHAUSTED, thirdUnknownProbe)
        assertEquals("CENTER_CLICK", GameUtil.terminalResultInputForAttempt(1)?.name)
        assertEquals("KEYBOARD_ENTER", GameUtil.terminalResultInputForAttempt(2)?.name)
        assertEquals(2, ResultPageDismissalPolicy.MAX_UNKNOWN_RESULT_FALLBACK_INPUTS)
        assertFalse(
            listOf(firstUnknownProbe, secondUnknownProbe, thirdUnknownProbe).contains(
                ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ),
            "an input dispatch is never proof that the client left the terminal page",
        )
    }

    @Test
    fun `unknown capture without both prior result evidence and terminal authorization never dispatches`() {
        fun decision(
            terminalProof: Boolean,
            freshCapture: Boolean,
            priorResult: Boolean,
        ) = ResultPageDismissalPolicy.decide(
            inWar = false,
            resultPageVisible = null,
            attempt = 1,
            maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
            clickAttempts = 0,
            terminalCleanupAuthorized = terminalProof,
            captureAuthorized = freshCapture,
            priorResultPageConfirmed = priorResult,
        )

        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            decision(terminalProof = false, freshCapture = true, priorResult = true),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            decision(terminalProof = true, freshCapture = false, priorResult = true),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            decision(terminalProof = true, freshCapture = true, priorResult = false),
        )
        assertFalse(
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = 1,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = 0,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                priorResultPageConfirmed = true,
            ) == ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02X".format(it) }

    companion object {
        private const val DEFEAT_RESULT =
            "/club/xiaojiawei/hsscript/status/surrender/v579-terminal-proof-unknown-postcheck-20261005-232525-573.png"
        private const val DEFEAT_RESULT_SHA256 =
            "67EF7F80F686D84ECC78B95F8CA4E61D930C5F74C2A568F087EB0054A6F17491"
    }
}
