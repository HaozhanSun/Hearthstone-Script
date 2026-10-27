package club.xiaojiawei.hsscript.status

import java.awt.Point
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartupNoticeRecoveryPolicyTest {
    private fun fixture(name: String): File? = listOf(
        File("src/test/resources/fixtures/screen-recovery/$name"),
        File("hs-script-app/src/test/resources/fixtures/screen-recovery/$name"),
    ).firstOrNull(File::exists)

    private fun tessData(): File? = listOf(
        File("src/main/resources/resources/tessdata"),
        File("hs-script-app/src/main/resources/resources/tessdata"),
    ).firstOrNull(File::exists)

    @AfterTest
    fun clearPermits() {
        StartupInputReadiness.resetForTest()
    }

    @Test
    fun `runtime Amazon notice with garbage OCR matches exact visual signature`() {
        val imageFile = fixture("amazon-appstore-sunset-notice-runtime-20261026-123653.png")
        assertNotNull(imageFile, "keep the 2026-10-26 startup notice screenshot as a durable regression fixture")
        val image = ImageIO.read(imageFile)

        assertTrue(ScreenStateRecovery.looksLikeAmazonAppstoreSunsetNoticeVisual(image), "runtime notice must match visual signature")
        assertFalse(ScreenStateRecovery.looksLikeReconnectDialogVisual(image), "Amazon notice must not match reconnect dialog")
        assertEquals(
            "AMAZON_APPSTORE_NOTICE",
            UpstreamScreenStateRecovery.classifyImageForTest("亞馬遜应用商店支援調整乱码", image),
            "the visual signature must classify the exact notice despite OCR noise",
        )
    }

    @Test
    fun `reconnect incident image matches structural signature and classifies despite OCR noise`() {
        val reconnectFile = fixture("offline-reconnect-modal-startup-20261026-105925.png")
        assertNotNull(reconnectFile, "keep the 2026-10-26 reconnect-over-notice screenshot as a durable fixture")
        val reconnect = ImageIO.read(reconnectFile)
        assertTrue(ScreenStateRecovery.looksLikeReconnectDialogVisual(reconnect), "incident reconnect frame matches structural signature")
        assertFalse(ScreenStateRecovery.looksLikeAmazonAppstoreSunsetNoticeVisual(reconnect), "reconnect must not match notice signature")
        assertEquals(
            "RECONNECT",
            UpstreamScreenStateRecovery.classifyImageForTest("乱码", reconnect),
        )
    }

    @Test
    fun `notice authorization requires exact title and Close plus verified idle startup context`() {
        assertTrue(StartupNoticeRecoveryPolicy.isAmazonAppstoreSunsetNotice("亚马逊应用商店支持调整 关闭"))
        assertFalse(StartupNoticeRecoveryPolicy.isAmazonAppstoreSunsetNotice("其他应用商店支持调整 关闭"))
        assertFalse(StartupNoticeRecoveryPolicy.isAmazonAppstoreSunsetNotice("亚马逊应用商店支持调整 了解更多"))
        assertFalse(StartupNoticeRecoveryPolicy.isAmazonAppstoreSunsetNotice(""))

        fun allowed(
            currentSessionReady: Boolean = false,
            activeGame: Boolean = false,
            activeMatchmaking: Boolean = false,
            gameplayMode: Boolean = false,
            trustedCurrentWindow: Boolean = true,
        ) = StartupNoticeRecoveryPolicy.mayDismiss(
            purpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            currentSessionReady = currentSessionReady,
            activeGame = activeGame,
            activeMatchmaking = activeMatchmaking,
            gameplayMode = gameplayMode,
            trustedCurrentWindow = trustedCurrentWindow,
        )
        assertTrue(allowed())
        assertTrue(
            StartupNoticeRecoveryPolicy.mayDismiss(
                purpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY,
                currentSessionReady = true,
                activeGame = false,
                activeMatchmaking = false,
                gameplayMode = false,
                trustedCurrentWindow = true,
            ),
            "Power.log readiness must not block dismissal of the exact client startup modal",
        )
        assertTrue(allowed(currentSessionReady = true))
        assertFalse(allowed(activeGame = true))
        assertFalse(allowed(activeMatchmaking = true))
        assertFalse(allowed(gameplayMode = true))
        assertFalse(allowed(trustedCurrentWindow = false))
        assertFalse(
            StartupNoticeRecoveryPolicy.mayDismiss(
                ScreenRecoveryCapturePurpose.STARTUP_YOGG_PROBE, false, false, false, false, true,
            ),
        )
    }

    @Test
    fun `exact fresh screenshot can dispatch only its close point then establish menu proof`() {
        val imageFile = fixture("amazon-appstore-sunset-notice-runtime-20261026-123653.png")
        assertNotNull(imageFile)
        val image = ImageIO.read(imageFile)
        assertEquals(
            "AMAZON_APPSTORE_NOTICE",
            UpstreamScreenStateRecovery.classifyImageForTest("OCR乱码", image),
            "fixture is the captured fresh client frame used to authorize this test path",
        )
        val point = assertNotNull(StartupNoticeRecoveryPolicy.closeButtonPoint(image.width, image.height))
        assertEquals(Point(969, 783), point)
        val pid = 14624
        val hwnd = "native@0xe4098e"
        assertTrue(StartupInputReadiness.issueNoticeDismissPermit(pid, hwnd, point, 10_000L))

        assertTrue(
            ActionDispatchGate.allowForState(
                action = StartupNoticeRecoveryPolicy.DISPATCH_ACTION,
                paused = false,
                working = true,
                currentSessionReady = true,
                startupNoticeDismissPermitValid = StartupInputReadiness.hasNoticeDismissPermit(
                    pid, hwnd, point, 10_001L,
                ),
            ),
            "the exact startup notice permit must pass the central gate even if Power.log is ready",
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "mouse.left",
                paused = false,
                working = true,
                currentSessionReady = false,
                startupNoticeDismissPermitValid = StartupInputReadiness.hasNoticeDismissPermit(
                    pid, hwnd, point, 10_001L,
                ),
            ),
            "the notice permit must not authorize ordinary gameplay input",
        )
        assertTrue(StartupInputReadiness.consumeNoticeDismissPermit(pid, hwnd, point, 10_002L))
        StartupInputReadiness.recordOnlineMenu(pid, hwnd, "HOME", modalVisible = false, now = 10_100L)
        assertTrue(StartupInputReadiness.isMenuReady(pid, hwnd, "HUB"), "fresh clean menu proof follows dismissal")
        assertFalse(
            ActionDispatchGate.allowForState(
                action = StartupNoticeRecoveryPolicy.DISPATCH_ACTION,
                paused = false,
                working = true,
                currentSessionReady = false,
                startupNoticeDismissPermitValid = StartupInputReadiness.hasNoticeDismissPermit(
                    pid, hwnd, point, 10_101L,
                ),
            ),
            "the same permit cannot dispatch a second close click",
        )
    }

    @Test
    fun `notice click permit is exact process window coordinate short lived and one shot`() {
        val point = assertNotNull(StartupNoticeRecoveryPolicy.closeButtonPoint(1920, 1080))
        assertEquals(Point(969, 783), point)
        assertNull(StartupNoticeRecoveryPolicy.closeButtonPoint(20, 20))
        assertFalse(
            StartupInputAuthorizationPolicy.allows(
                StartupNoticeRecoveryPolicy.DISPATCH_ACTION,
                currentSessionReady = false,
                freshOnlineMenuReady = false,
            ),
        )

        assertTrue(StartupInputReadiness.issueNoticeDismissPermit(33012, "hwnd-live", point, 1_000L))
        assertTrue(
            StartupInputAuthorizationPolicy.allows(
                StartupNoticeRecoveryPolicy.DISPATCH_ACTION,
                currentSessionReady = false,
                freshOnlineMenuReady = false,
                startupNoticeDismissPermitValid = StartupInputReadiness.hasNoticeDismissPermit(
                    33012, "hwnd-live", point, 1_001L,
                ),
            ),
        )
        assertFalse(StartupInputReadiness.hasNoticeDismissPermit(33012, "other-window", point, 1_001L))
        assertFalse(StartupInputReadiness.hasNoticeDismissPermit(33012, "hwnd-live", Point(10, 10), 1_001L))
        assertFalse(StartupInputReadiness.hasNoticeDismissPermit(33012, "hwnd-live", point, 3_001L))
        assertTrue(StartupInputReadiness.consumeNoticeDismissPermit(33012, "hwnd-live", point, 1_500L))
        assertFalse(StartupInputReadiness.hasNoticeDismissPermit(33012, "hwnd-live", point, 1_501L))
        assertFalse(StartupInputReadiness.consumeNoticeDismissPermit(33012, "hwnd-live", point, 1_501L))
    }

    @Test
    fun `successful online menu proof clears its matching notice dismiss permit`() {
        val point = assertNotNull(StartupNoticeRecoveryPolicy.closeButtonPoint(1920, 1080))
        assertTrue(StartupInputReadiness.issueNoticeDismissPermit(33012, "hwnd-live", point, 1_000L))
        assertTrue(StartupInputReadiness.hasNoticeDismissPermit(33012, "hwnd-live", point, 1_001L))

        StartupInputReadiness.recordOnlineMenu(
            pid = 33012,
            hwnd = "hwnd-live",
            screen = "HOME",
            modalVisible = false,
            now = 1_500L,
        )

        assertFalse(StartupInputReadiness.hasNoticeDismissPermit(33012, "hwnd-live", point, 1_501L))
        assertTrue(StartupInputReadiness.isMenuReady(33012, "hwnd-live", "HUB"))
    }

    @Test
    fun `notice startup dispatch needs its permit and remains behind work pause and terminal fences`() {
        assertFalse(
            ActionDispatchGate.allowForState(
                action = StartupNoticeRecoveryPolicy.DISPATCH_ACTION,
                paused = false,
                working = true,
                currentSessionReady = false,
                startupNoticeDismissPermitValid = false,
            ),
        )
        assertTrue(
            ActionDispatchGate.allowForState(
                action = StartupNoticeRecoveryPolicy.DISPATCH_ACTION,
                paused = false,
                working = true,
                currentSessionReady = false,
                startupNoticeDismissPermitValid = true,
            ),
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                action = StartupNoticeRecoveryPolicy.DISPATCH_ACTION,
                paused = true,
                working = true,
                currentSessionReady = false,
                startupNoticeDismissPermitValid = true,
            ),
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                action = StartupNoticeRecoveryPolicy.DISPATCH_ACTION,
                paused = false,
                working = false,
                currentSessionReady = false,
                startupNoticeDismissPermitValid = true,
            ),
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                action = StartupNoticeRecoveryPolicy.DISPATCH_ACTION,
                paused = false,
                working = true,
                currentSessionReady = false,
                terminalCleanupPending = true,
                startupNoticeDismissPermitValid = true,
            ),
        )
    }
}
