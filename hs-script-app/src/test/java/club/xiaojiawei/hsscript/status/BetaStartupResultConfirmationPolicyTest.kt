package club.xiaojiawei.hsscript.status

import java.awt.Point
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
