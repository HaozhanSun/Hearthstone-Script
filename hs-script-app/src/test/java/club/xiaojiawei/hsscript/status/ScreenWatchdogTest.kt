package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.ocr.OcrProviderMode
import club.xiaojiawei.hsscript.ocr.PaddleXOcrCancelledException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

class ScreenWatchdogTest {

    private val originalSettingsProvider = OcrRuntime.settingsProvider
    private val originalProviderModeProvider = OcrRuntime.providerModeProvider

    @AfterEach
    fun tearDown() {
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
            ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_HANDOFF_NORMAL_FLOW,
            ScreenWatchdog.decideForTest(kind),
        )
    }

    @Test
    fun `Chinese mulligan screen hands control back to the normal phase listener`() {
        val kind = ScreenWatchdog.classifyForTest("起始手牌 保留或替换卡牌 确认")
        assertEquals(ScreenWatchdogKind.MULLIGAN, kind)
        assertEquals(
            ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_HANDOFF_NORMAL_FLOW,
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
        assertEquals(ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_HANDOFF_NORMAL_FLOW, observation.action)
        assertEquals(null, observation.screenshotPath)
        assertEquals(null, observation.roi)
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
    fun `watchdog uses selected OCR provider and reports bounded ROI`() {
        OcrRuntime.providerModeProvider = { OcrProviderMode.PADDLEX_ONLY }

        val observation = ScreenWatchdog.inspectForSurrender(
            state = "mode=GAMEPLAY|warPhase=DRAWN_INIT_CARD",
            attempts = 4,
            captureProvider = { BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB) },
            ocrProvider = { "失败 点击继续" },
        )

        assertEquals("PADDLEX", observation.provider)
        assertEquals("screen-watchdog-center", observation.roi)
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
        assertEquals(ScreenWatchdogRecoveryAction.STOP_SURRENDER_AND_HANDOFF_NORMAL_FLOW, observation.action)
        assertEquals("ocr-cancelled", observation.reason)
        assertEquals("screen-watchdog-center", observation.roi)
    }
}
