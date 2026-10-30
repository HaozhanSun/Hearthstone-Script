package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BetaStartupResultConfirmationPolicyTest {
    @AfterTest
    fun reset() = StartupInputReadiness.resetForTest()

    @Test
    fun `only exact previous ranked win confirmation matches`() {
        assertTrue(BetaStartupResultConfirmationPolicy.matches("游戏结果 你在上一场排名对战的对战中获胜。 确定"))
        assertTrue(BetaStartupResultConfirmationPolicy.matches("游戏结果\n你在上一场排名对战的对战中获胜\n确定"))
        assertFalse(BetaStartupResultConfirmationPolicy.matches("游戏结果 你在上一场排名对战的对战中失败。 确定"))
        assertFalse(BetaStartupResultConfirmationPolicy.matches("游戏结果 你在上一场对战中获胜。 确定"))
        assertFalse(BetaStartupResultConfirmationPolicy.matches("其他弹窗 确定"))
    }

    @Test
    fun `acknowledgement is Beta startup only and defers unsafe or ambiguous context`() {
        fun allowed(
            beta: Boolean = true,
            purpose: ScreenRecoveryCapturePurpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            ready: Boolean = false,
            activeGame: Boolean = false,
            matchmaking: Boolean = false,
            mode: String? = "LOGIN",
            trusted: Boolean = true,
        ) = BetaStartupResultConfirmationPolicy.mayAcknowledge(
            beta, purpose, ready, activeGame, matchmaking, mode, trusted,
        )
        assertTrue(allowed())
        assertFalse(allowed(beta = false))
        assertFalse(allowed(ready = true))
        assertFalse(allowed(activeGame = true))
        assertFalse(allowed(matchmaking = true))
        assertFalse(allowed(mode = "GAMEPLAY"))
        assertFalse(allowed(trusted = false))
        assertFalse(allowed(purpose = ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE))
    }

    @Test
    fun `live startup result fixture is recognized from one bounded crop and idle probe never authorizes click`() {
        val fixture = javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/beta-previous-ranked-result-confirmation-live-20261030-033609.png",
        )?.use(ImageIO::read) ?: error("missing captured Beta result-confirmation fixture")
        val chineseModel = javaClass.getResource("/resources/tessdata/chi_sim.traineddata")
            ?: error("missing classpath Chinese Tesseract model")
        val tessData = File(chineseModel.toURI()).parentFile

        assertEquals(
            "BETA_RESULT_CONFIRMATION",
            UpstreamScreenStateRecovery.classifyBetaResultConfirmationImageForTest(fixture, tessData),
            "the captured live modal must classify through the same bounded OCR crop used by startup recovery",
        )
        assertTrue(BetaStartupResultConfirmationPolicy.matchesLiveModal(
            "人作和在上一场排名对战的对战中煞胜。",
            fixture,
            centeredModalVisible = true,
        ), "the live OCR's observed 煞/获 glyph confusion is accepted only with the structural modal evidence")
        assertFalse(BetaStartupResultConfirmationPolicy.matchesLiveModal(
            "你在上一场排名对战的对战中获胜",
            fixture,
            centeredModalVisible = false,
        ), "exact body OCR without a currently visible centered modal is insufficient")
        assertFalse(BetaStartupResultConfirmationPolicy.matchesLiveModal(
            "其他弹窗 确定",
            fixture,
            centeredModalVisible = true,
        ), "a generic confirmation modal cannot match the exact previous-ranked-win body")
        assertTrue(BetaStartupResultConfirmationPolicy.mayInspect(
            betaChannel = true,
            purpose = ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
            currentSessionReady = false,
            activeGame = false,
            activeMatchmaking = false,
            mode = "LOGIN",
            trustedCurrentWindow = true,
        ))
        assertFalse(BetaStartupResultConfirmationPolicy.mayAcknowledge(
            betaChannel = true,
            purpose = ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
            currentSessionReady = false,
            activeGame = false,
            activeMatchmaking = false,
            mode = "LOGIN",
            trustedCurrentWindow = true,
        ), "the original idle screenshot is recognition evidence only")
        assertTrue(BetaStartupResultConfirmationPolicy.mayAcknowledge(
            betaChannel = true,
            purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            currentSessionReady = false,
            activeGame = false,
            activeMatchmaking = false,
            mode = "LOGIN",
            trustedCurrentWindow = true,
        ), "only an independently refreshed startup capture may authorize confirmation")
        assertFalse(BetaStartupResultConfirmationPolicy.mayInspect(
            betaChannel = true,
            purpose = ScreenRecoveryCapturePurpose.IDLE_CLIENT_PROBE,
            currentSessionReady = false,
            activeGame = false,
            activeMatchmaking = true,
            mode = "LOGIN",
            trustedCurrentWindow = true,
        ), "a live queue must fence even the modal inspection path")
    }

    @Test
    fun `point is bounded and permit is one-shot bound to exact process window and point`() {
        assertNull(BetaStartupResultConfirmationPolicy.confirmButtonPoint(640, 480))
        val point = BetaStartupResultConfirmationPolicy.confirmButtonPoint(1920, 1080, 20, 30)
        assertEquals(Point(980, 667), point)
        assertTrue(StartupInputReadiness.issueBetaResultConfirmationPermit(43012, "hwnd", point!!, 1_000L))
        assertTrue(StartupInputReadiness.hasBetaResultConfirmationPermit(43012, "hwnd", point, 1_500L))
        assertFalse(StartupInputReadiness.hasBetaResultConfirmationPermit(43013, "hwnd", point, 1_500L))
        assertFalse(StartupInputReadiness.hasBetaResultConfirmationPermit(43012, "other", point, 1_500L))
        assertFalse(StartupInputReadiness.hasBetaResultConfirmationPermit(43012, "hwnd", Point(point.x + 1, point.y), 1_500L))
        assertFalse(StartupInputAuthorizationPolicy.allows(BetaStartupResultConfirmationPolicy.DISPATCH_ACTION, false, false))
        assertTrue(StartupInputAuthorizationPolicy.allows(
            BetaStartupResultConfirmationPolicy.DISPATCH_ACTION, false, false,
            betaResultConfirmationPermitValid = true,
        ))
        assertTrue(StartupInputReadiness.consumeBetaResultConfirmationPermit(43012, "hwnd", point, 1_500L))
        assertFalse(StartupInputReadiness.hasBetaResultConfirmationPermit(43012, "hwnd", point, 1_500L))
    }
}
