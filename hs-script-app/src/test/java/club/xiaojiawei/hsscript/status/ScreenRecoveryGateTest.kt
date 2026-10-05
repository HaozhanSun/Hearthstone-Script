package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenRecoveryGateTest {

    @Test
    fun `missing or unverified Hearthstone authority blocks capture and recovery action`() {
        val captureCalls = AtomicInteger()
        val actionCalls = AtomicInteger()
        val invalidEvidence = listOf(
            ScreenRecoveryAuthorityEvidence(false, false, false, false, false),
            ScreenRecoveryAuthorityEvidence(true, false, false, true, false),
            ScreenRecoveryAuthorityEvidence(true, true, false, true, true),
            ScreenRecoveryAuthorityEvidence(true, true, true, false, true),
            ScreenRecoveryAuthorityEvidence(true, true, true, true, false),
        )

        invalidEvidence.forEach { evidence ->
            assertNull(ScreenRecoveryAuthorityGate.captureIfAuthorized(evidence) {
                captureCalls.incrementAndGet()
                "desktop-frame"
            })
            assertFalse(ScreenRecoveryAuthorityGate.dispatchIfAuthorized(evidence) {
                actionCalls.incrementAndGet()
                true
            })
        }

        assertEquals(0, captureCalls.get(), "desktop capture must not run without live verified foreground game HWND")
        assertEquals(0, actionCalls.get(), "recovery must not dispatch after authority is lost")
    }

    @Test
    fun `verified foreground Hearthstone HWND permits the normal capture and action route`() {
        val evidence = ScreenRecoveryAuthorityEvidence(
            processAlive = true,
            windowPresent = true,
            windowVerified = true,
            foregroundConfirmed = true,
            sameWindow = true,
            capturedPixelsVerified = true,
        )
        val captureCalls = AtomicInteger()
        val actionCalls = AtomicInteger()

        val frame = ScreenRecoveryAuthorityGate.captureIfAuthorized(evidence) {
            captureCalls.incrementAndGet()
            "hearthstone-frame"
        }
        val applied = ScreenRecoveryAuthorityGate.dispatchIfAuthorized(evidence) {
            actionCalls.incrementAndGet()
            true
        }

        assertEquals("hearthstone-frame", frame)
        assertTrue(applied)
        assertEquals(1, captureCalls.get())
        assertEquals(1, actionCalls.get())
    }

    @Test
    fun `pre-capture authority allows capture but cannot authorize dispatch until pixels are verified`() {
        val preCaptureEvidence = ScreenRecoveryAuthorityEvidence(
            processAlive = true,
            windowPresent = true,
            windowVerified = true,
            foregroundConfirmed = true,
            sameWindow = true,
        )
        val captureCalls = AtomicInteger()
        val actionCalls = AtomicInteger()

        val frame = ScreenRecoveryAuthorityGate.captureIfAuthorized(preCaptureEvidence) {
            captureCalls.incrementAndGet()
            "current-hearthstone-frame"
        }
        val prematurelyDispatched = ScreenRecoveryAuthorityGate.dispatchIfAuthorized(preCaptureEvidence) {
            actionCalls.incrementAndGet()
            true
        }

        assertEquals("current-hearthstone-frame", frame)
        assertFalse(prematurelyDispatched)
        assertEquals(1, captureCalls.get())
        assertEquals(0, actionCalls.get(), "capture permission is not pixel verification or action permission")

        val verified = preCaptureEvidence.copy(capturedPixelsVerified = true)
        assertTrue(ScreenRecoveryAuthorityGate.dispatchIfAuthorized(verified) { actionCalls.incrementAndGet(); true })
        assertEquals(1, actionCalls.get())
    }

    @Test
    fun `upstream fallback result postcheck requires a known post-result destination`() {
        assertEquals(true, UpstreamScreenStateRecovery.resultVisibilityForTest("RESULT", 90))
        assertEquals(false, UpstreamScreenStateRecovery.resultVisibilityForTest("DECK_SELECTION", 90))
        assertNull(UpstreamScreenStateRecovery.resultVisibilityForTest("UNKNOWN", 90))
        assertNull(UpstreamScreenStateRecovery.resultVisibilityForTest("HOME", 84))
    }

    @Test
    fun `upstream fallback OCR consumes the current deck selection anchor instead of missing center ROI`() {
        val roiNames = UpstreamScreenStateRecovery.screenRecoveryOcrRoiNamesForTest()

        assertTrue(roiNames.contains(ScreenStateRoiSelector.DECK_SELECTION_TITLE_ROI))
        assertTrue(roiNames.contains("screen-state-header"))
        assertTrue(roiNames.contains("screen-state-footer"))
        assertTrue(roiNames.contains(ScreenStateRoiSelector.RESULT_CONTINUE_ROI))
        assertFalse(roiNames.contains("screen-state-center"))
        assertEquals("DECK_SELECTION", UpstreamScreenStateRecovery.classifyForTest("选择套牌 狂野对战"))
        assertEquals("RESULT", UpstreamScreenStateRecovery.classifyForTest("点击继续"))
        assertEquals(
            false,
            UpstreamScreenStateRecovery.resultVisibilityForTest("DECK_SELECTION", 100),
        )
        assertNull(UpstreamScreenStateRecovery.classifyForTest(""), "OCR failure must remain UNKNOWN")
    }

    private val originalEnabled = ConfigUtil.getBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED)

    @AfterTest
    fun restoreFeatureSetting() {
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, originalEnabled, store = false)
    }

    @Test
    fun `off runtime does not execute an admitted beta recovery effect`() {
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, false, store = false)
        ScreenRecoveryRuntime.initialize()
        val effects = AtomicInteger()
        val admitted = ScreenRecoveryRuntime.runIfEnabled { effects.incrementAndGet() }

        assertFalse(admitted)
        assertEquals(0, effects.get())
    }

    @Test
    fun `beta-only capture pipeline returns before touching screen when switch is off`() {
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, false, store = false)
        ScreenRecoveryRuntime.initialize()

        assertEquals(
            ScreenStateRecovery.InspectionResult.DISABLED,
            ScreenStateRecovery.inspectBetaAndRecover(30_000, "test-state"),
        )
    }

    @Test
    fun `ConfigUtil live toggle admits then cancels queued beta work`() {
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, false, store = false)
        ScreenRecoveryRuntime.initialize()
        val effects = AtomicInteger()
        assertFalse(ScreenRecoveryRuntime.isEnabled())

        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, true, store = false)
        val token = requireNotNull(ScreenRecoveryRuntime.tokenOrNull())
        val pending = CompletableFuture<Void>()
        assertTrue(ScreenRecoveryRuntime.track(token, pending))

        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, false, store = false)

        assertTrue(pending.isCancelled || pending.isDone)
        assertFalse(ScreenRecoveryRuntime.isCurrent(token))
        assertFalse(ScreenRecoveryRuntime.runIfEnabled { effects.incrementAndGet() })
        assertEquals(0, effects.get())
    }

    @Test
    fun `off switch preserves the upstream screen classifier for normal lifecycle fallback`() {
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, false, store = false)
        ScreenRecoveryRuntime.initialize()

        assertEquals("DECK_SELECTION", UpstreamScreenStateRecovery.classifyForTest("选择套牌"))
        assertEquals("RESULT", UpstreamScreenStateRecovery.classifyForTest("胜利 点击继续"))
        assertEquals("MATCHMAKING", UpstreamScreenStateRecovery.classifyForTest("搜寻对手 取消"))
        assertNull(UpstreamScreenStateRecovery.classifyForTest(""))
        assertNull(
            UpstreamScreenStateRecovery.classifyWithVisualForTest(
                ocrText = "",
                centralDarkRatio = 0.499,
                warmRatio = 0.195,
                blueRatio = 0.01,
            ),
        )
    }

    @Test
    fun `stale generation cannot re-enter after disable and re-enable`() {
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, true, store = false)
        ScreenRecoveryRuntime.initialize()
        val staleToken = requireNotNull(ScreenRecoveryRuntime.tokenOrNull())

        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, false, store = false)
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, true, store = false)

        assertFalse(ScreenRecoveryRuntime.isCurrent(staleToken))
        assertTrue(ScreenRecoveryRuntime.isCurrent(ScreenRecoveryRuntime.tokenOrNull()))
    }
}
