package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.ocr.OcrProviderMode
import club.xiaojiawei.hsscript.ocr.PaddleXOcrCancelledException
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class ScreenWatchdogTest {

    private val originalRecoveryEnabled = ConfigUtil.getBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED)
    private val originalWatchdogEnabled = ConfigUtil.getBoolean(ConfigEnum.SCREEN_WATCHDOG_ENABLED)
    private val originalSettingsProvider = OcrRuntime.settingsProvider
    private val originalProviderModeProvider = OcrRuntime.providerModeProvider

    @Test
    fun `upstream screen watchdog remains available when beta recovery is off`() {
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, false, store = false)
        ConfigUtil.putBoolean(ConfigEnum.SCREEN_WATCHDOG_ENABLED, true, store = false)
        var captures = 0
        var ocrCalls = 0

        val timing = ScreenWatchdog.shouldInspect(
            startedAt = 0L,
            attempts = 99,
            now = System.currentTimeMillis() + 60_000L,
            stuckMs = 0L,
            maxRetries = 1,
            cooldownMs = 0L,
        )
        val observation = ScreenWatchdog.inspectForSurrender(
            state = "mode=GAMEPLAY|warPhase=GAME_TURN",
            attempts = 99,
            captureProvider = { captures++; null },
            ocrProvider = { ocrCalls++; "失败 点击继续" },
        )

        assertTrue(timing.shouldInspect)
        assertEquals(1, captures)
        assertEquals(0, ocrCalls)
        assertEquals(ScreenWatchdogKind.CAPTURE_FAILED, observation.kind)
    }

    @Test
    fun `beta off routes active-game unknown OCR through exact upstream behavior`() {
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, false, store = false)
        val frame = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        val state = "mode=GAMEPLAY|inWar=true|warPhase=GAME_TURN|myTurn=true|warCount=82"
        val capture: () -> BufferedImage? = { frame }
        val ocr: (BufferedImage) -> String = { "" }

        val routed = ScreenWatchdog.inspectForSurrender(state, 9, captureProvider = capture, ocrProvider = ocr)
        val upstream = UpstreamScreenWatchdog.inspectForSurrender(state, 9, captureProvider = capture, ocrProvider = ocr)

        assertEquals(upstream.kind, routed.kind)
        assertEquals(upstream.action, routed.action)
        assertEquals(ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_CONTINUE_UNKNOWN, routed.action)
        assertEquals("ocr-classified", routed.reason)
    }

    @org.junit.jupiter.api.BeforeEach
    fun enableRecoveryForLegacyWatchdogTests() {
        ScreenWatchdog.resetTimingForTest()
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, true, store = false)
        ConfigUtil.putBoolean(ConfigEnum.SCREEN_WATCHDOG_ENABLED, true, store = false)
    }

    @AfterEach
    fun tearDown() {
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, originalRecoveryEnabled, store = false)
        ConfigUtil.putBoolean(ConfigEnum.SCREEN_WATCHDOG_ENABLED, originalWatchdogEnabled, store = false)
        OcrRuntime.settingsProvider = originalSettingsProvider
        OcrRuntime.providerModeProvider = originalProviderModeProvider
    }

    @Test
    fun `lost result page stops surrender and records loss`() {
        val kind = ScreenWatchdog.classifyForTest("败北 点击继续")
        assertEquals(ScreenWatchdogKind.LOST, kind)
        assertEquals(
            ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECORD_LOSS,
            ScreenWatchdog.decideForTest(kind),
        )
    }

    @Test
    fun `won result page stops surrender and records win`() {
        val kind = ScreenWatchdog.classifyForTest("胜利 点击继续")
        assertEquals(ScreenWatchdogKind.WIN, kind)
        assertEquals(
            ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECORD_WIN,
            ScreenWatchdog.decideForTest(kind),
        )
    }

    @Test
    fun `unknown screen stops surrender without pausing bounded recovery`() {
        val kind = ScreenWatchdog.classifyForTest("一些无法判定的文字")
        assertEquals(ScreenWatchdogKind.UNKNOWN, kind)
        assertEquals(
            ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_CONTINUE_UNKNOWN,
            ScreenWatchdog.decideForTest(kind),
        )
    }

    @Test
    fun `capture failure is explicit and stops surrender`() {
        val observation = ScreenWatchdog.inspectForSurrender(
            state = "test-state",
            attempts = 9,
            captureProvider = { null },
            ocrProvider = { error("should not OCR without capture") },
        )
        assertEquals(ScreenWatchdogKind.CAPTURE_FAILED, observation.kind)
        assertEquals(ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_CONTINUE_UNKNOWN, observation.action)
        assertEquals(null, observation.screenshotPath)
    }

    @Test
    fun `authoritative active gameplay is recognized from the watchdog state`() {
        assertTrue(
            ScreenWatchdog.isAuthoritativeActiveGameplayForTest(
                "mode=GAMEPLAY|inWar=true|warPhase=GAME_TURN|myTurn=true|warCount=82",
            ),
        )
        assertFalse(
            ScreenWatchdog.isAuthoritativeActiveGameplayForTest(
                "mode=GAMEPLAY|inWar=true|warPhase=GAME_TURN|myTurn=false|warCount=82",
            ),
        )
    }

    @Test
    fun `unknown OCR during authoritative active gameplay resumes normal gameplay`() {
        val observation = ScreenWatchdog.inspectForSurrender(
            state = "mode=GAMEPLAY|inWar=true|warPhase=GAME_TURN|myTurn=true|warCount=82",
            attempts = 9,
            captureProvider = { BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB) },
            ocrProvider = { "" },
        )

        assertEquals(ScreenWatchdogKind.UNKNOWN, observation.kind)
        assertEquals(ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RESUME_GAMEPLAY, observation.action)
    }

    @Test
    fun `capture failure during authoritative active gameplay resumes normal gameplay`() {
        val observation = ScreenWatchdog.inspectForSurrender(
            state = "mode=GAMEPLAY|inWar=true|warPhase=GAME_TURN|myTurn=true|warCount=82",
            attempts = 9,
            captureProvider = { null },
            ocrProvider = { error("should not OCR without capture") },
        )

        assertEquals(ScreenWatchdogKind.CAPTURE_FAILED, observation.kind)
        assertEquals(ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RESUME_GAMEPLAY, observation.action)
    }

    @Test
    fun `beta active gameplay extension is selected only when switch is enabled`() {
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, true, store = false)
        val observation = ScreenWatchdog.inspectForSurrender(
            state = "mode=GAMEPLAY|inWar=true|warPhase=GAME_TURN|myTurn=true|warCount=82",
            attempts = 9,
            captureProvider = { BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB) },
            ocrProvider = { "" },
        )

        assertEquals(ScreenWatchdogKind.UNKNOWN, observation.kind)
        assertEquals(ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RESUME_GAMEPLAY, observation.action)
    }

    @Test
    fun `result observation requires accepted dismissal postcheck before success`() {
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, true, store = false)
        val result = ScreenWatchdog.inspectForSurrender(
            state = "mode=GAMEPLAY|inWar=true|warPhase=FILL_DECK|myTurn=false|warCount=82",
            attempts = 9,
            captureProvider = { BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB) },
            ocrProvider = { "对战结束 点击继续" },
        )
        assertEquals(ScreenWatchdogKind.RESULT, result.kind)
        assertEquals(ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_CLEAR_RESULT, result.action)

        val click = ResultPageDismissalPolicy.decide(
            inWar = true,
            resultPageVisible = true,
            attempt = 1,
            maxAttempts = 5,
        )
        val uncertainPostcheck = ResultPageDismissalPolicy.decide(
            inWar = true,
            resultPageVisible = null,
            attempt = 2,
            maxAttempts = 5,
        )
        val acceptedPostcheck = ResultPageDismissalPolicy.decide(
            inWar = true,
            resultPageVisible = false,
            attempt = 2,
            maxAttempts = 5,
        )

        assertEquals(ResultPageDismissalPolicy.Decision.DISPATCH_CLICK, click)
        assertEquals(ResultPageDismissalPolicy.Decision.BLOCKED_UNCONFIRMED_DURING_WAR, uncertainPostcheck)
        assertEquals(ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED, acceptedPostcheck)
    }

    @Test
    fun `timing gate waits until repeated action threshold`() {
        assertFalse(
            ScreenWatchdog.shouldInspect(
                startedAt = 1_000L,
                attempts = 2,
                now = 2_000L,
                stuckMs = 30_000L,
                maxRetries = 3,
                cooldownMs = 0L,
            ).shouldInspect,
        )
        assertTrue(
            ScreenWatchdog.shouldInspect(
                startedAt = 1_000L,
                attempts = 3,
                now = 40_000L,
                stuckMs = 30_000L,
                maxRetries = 3,
                cooldownMs = 0L,
            ).shouldInspect,
        )
    }

    @Test
    fun `screen probes remain cooldown bounded after retry threshold`() {
        ScreenWatchdog.resetTimingForTest()
        val first = ScreenWatchdog.shouldInspect(
            startedAt = 0L,
            attempts = 3,
            now = 100_000L,
            stuckMs = 30_000L,
            maxRetries = 3,
            cooldownMs = 1_000L,
        )
        val withinCooldown = ScreenWatchdog.shouldInspect(
            startedAt = 0L,
            attempts = 30,
            now = 100_500L,
            stuckMs = 30_000L,
            maxRetries = 3,
            cooldownMs = 1_000L,
        )
        val afterCooldown = ScreenWatchdog.shouldInspect(
            startedAt = 0L,
            attempts = 30,
            now = 101_001L,
            stuckMs = 30_000L,
            maxRetries = 3,
            cooldownMs = 1_000L,
        )

        assertTrue(first.shouldInspect)
        assertFalse(withinCooldown.shouldInspect)
        assertTrue(afterCooldown.shouldInspect)
    }

    @Test
    fun `mandatory surrender post-click probe bypasses only the global cooldown`() {
        ScreenWatchdog.resetTimingForTest()
        val postClickProbe = ScreenWatchdog.MandatorySurrenderPostClickProbe()
        val first = ScreenWatchdog.shouldInspect(
            startedAt = 0L,
            attempts = 3,
            now = 100_000L,
            stuckMs = 30_000L,
            maxRetries = 3,
            cooldownMs = 15_000L,
        )
        postClickProbe.markClickDispatched()

        // The recovery scheduler's next fixed-delay tick is 500 ms later.
        // A post-click visual transition check must not wait for the 15s
        // process-wide watchdog cooldown to expire.
        val fastPostClick = ScreenWatchdog.shouldInspect(
            startedAt = 0L,
            attempts = 4,
            now = 100_500L,
            stuckMs = 30_000L,
            maxRetries = 3,
            cooldownMs = 15_000L,
            bypassCooldownForMandatorySurrenderPostClick = postClickProbe.shouldBypassCooldown(),
        )
        if (fastPostClick.shouldInspect) postClickProbe.markProbeStarted()
        val ordinaryProbe = ScreenWatchdog.shouldInspect(
            startedAt = 0L,
            attempts = 5,
            now = 101_000L,
            stuckMs = 30_000L,
            maxRetries = 3,
            cooldownMs = 15_000L,
            bypassCooldownForMandatorySurrenderPostClick = postClickProbe.shouldBypassCooldown(),
        )

        assertTrue(first.shouldInspect)
        assertTrue(fastPostClick.shouldInspect, fastPostClick.reason)
        assertTrue(fastPostClick.reason.startsWith("mandatory-surrender-post-click"))
        assertFalse(postClickProbe.shouldBypassCooldown(), "one click must authorize only one fast probe")
        assertFalse(ordinaryProbe.shouldInspect)
        assertTrue(ordinaryProbe.reason.startsWith("cooldown"))
    }

    @Test
    fun `mandatory surrender initial probe bypasses stuck threshold but remains cooldown bounded`() {
        ScreenWatchdog.resetTimingForTest()
        val initial = ScreenWatchdog.shouldInspect(
            startedAt = 100_000L,
            attempts = 1,
            now = 100_020L,
            stuckMs = 30_000L,
            maxRetries = 3,
            cooldownMs = 15_000L,
            bypassThresholdForMandatorySurrenderInitial = true,
        )
        val withinCooldown = ScreenWatchdog.shouldInspect(
            startedAt = 100_000L,
            attempts = 3,
            now = 100_500L,
            stuckMs = 30_000L,
            maxRetries = 3,
            cooldownMs = 15_000L,
        )

        assertTrue(initial.shouldInspect, initial.reason)
        assertTrue(initial.reason.startsWith("mandatory-surrender-initial"))
        assertFalse(withinCooldown.shouldInspect)
        assertTrue(withinCooldown.reason.startsWith("cooldown"))
    }

    @Test
    fun `rank nine incident settings screenshot is probed at three seconds with bounded uncertain recheck`() {
        val screenshot = javaClass.getResourceAsStream(
            "/fixtures/screen-recovery/rank9-settings-overlay-20261026-084643.png",
        )?.use(ImageIO::read) ?: error("missing captured v4.16.697 rank-nine settings screenshot")
        val state = "mode=GAMEPLAY|inWar=true|warPhase=REPLACE_CARD|myTurn=false|myMulliganInput=true|warCount=0"
        assertTrue(ScreenWatchdog.hasSettingsOverlayVisualForTest(screenshot))
        assertTrue(ScreenWatchdog.hasVerifiedGameMenuOverlay(screenshot))
        assertEquals(ScreenWatchdogKind.SETTINGS, ScreenWatchdog.fastVisualKindForSurrenderForTest(state, screenshot))

        val probe = ScreenWatchdog.MandatorySurrenderPostClickProbe()
        val gearClickAt = 100_000L
        probe.markSettingsClickDispatched(gearClickAt)
        val beforeSettle = probe.settingsOverlayProbeTiming(gearClickAt + 2_999L)
        assertFalse(beforeSettle!!.shouldInspect)
        assertTrue(beforeSettle.reason.contains("remainingMs=1"))

        val dueProbe = probe.settingsOverlayProbeTiming(gearClickAt + 3_000L)
        assertTrue(dueProbe!!.shouldInspect)
        assertTrue(dueProbe.reason.startsWith("mandatory-surrender-settings-overlay-probe"))
        assertEquals(3_000L, SettingsOverlaySettlePolicy.settleMs("beta"))
        assertEquals(5_000L, SettingsOverlaySettlePolicy.settleMs("stable"))
        assertEquals(3_000L, ScreenWatchdog.MandatorySurrenderPostClickProbe.SETTINGS_OVERLAY_SETTLE_MS)
        assertEquals(3_600L, ScreenWatchdog.MandatorySurrenderPostClickProbe.SETTINGS_OVERLAY_MAX_PROBE_LATENCY_MS)

        // If the fresh frame is not yet decisive, retry observation after two
        // seconds; never wait for the generic 30-second screen watchdog here.
        probe.finishSettingsOverlayProbe(settingsConfirmed = false, now = gearClickAt + 3_000L)
        assertFalse(probe.settingsOverlayProbeTiming(gearClickAt + 4_999L)!!.shouldInspect)
        assertTrue(probe.settingsOverlayProbeTiming(gearClickAt + 5_000L)!!.shouldInspect)
        probe.finishSettingsOverlayProbe(settingsConfirmed = true, now = gearClickAt + 5_000L)
        assertEquals(null, probe.settingsOverlayProbeTiming(gearClickAt + 5_001L))
    }

    @Test
    fun `watchdog uses local OCR even when PaddleX is selected`() {
        OcrRuntime.providerModeProvider = { OcrProviderMode.PADDLEX_ONLY }

        val observation = ScreenWatchdog.inspectForSurrender(
            state = "mode=GAMEPLAY|warPhase=DRAWN_INIT_CARD",
            attempts = 4,
            captureProvider = { BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB) },
            ocrProvider = { "失败 点击继续" },
        )

        assertEquals("LEGACY", observation.provider)
        assertEquals(ScreenWatchdogKind.LOST, observation.kind)
        assertEquals(ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_RECORD_LOSS, observation.action)
    }

    @Test
    fun `cancelled OCR stops surrender without pausing`() {
        val observation = ScreenWatchdog.inspectForSurrender(
            state = "mode=GAMEPLAY|warPhase=GAME_OVER",
            attempts = 4,
            captureProvider = { BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB) },
            ocrProvider = { throw PaddleXOcrCancelledException("terminal cleanup") },
        )

        assertEquals(ScreenWatchdogKind.UNKNOWN, observation.kind)
        assertEquals(ScreenWatchdogRecoveryAction.STOP_SURRENDER_NO_ACTION, observation.action)
        assertEquals("ocr-cancelled", observation.reason)
    }
}
