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
