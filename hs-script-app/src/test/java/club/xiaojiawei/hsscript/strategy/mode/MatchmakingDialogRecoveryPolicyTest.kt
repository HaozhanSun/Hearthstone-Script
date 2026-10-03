package club.xiaojiawei.hsscript.strategy.mode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import club.xiaojiawei.hsscript.ocr.OcrHealth
import club.xiaojiawei.hsscript.ocr.OcrRecognition
import club.xiaojiawei.hsscript.ocr.OcrProviderKind
import club.xiaojiawei.hsscript.ocr.OcrProviderMode
import club.xiaojiawei.hsscript.ocr.OcrRuntime
import club.xiaojiawei.hsscript.ocr.OcrTextBridge
import club.xiaojiawei.hsscript.ocr.PaddleXOcrSettings
import club.xiaojiawei.hsscript.status.ScreenStateRecovery
import club.xiaojiawei.hsscript.status.ScreenStateRoiSelector
import javax.imageio.ImageIO

class MatchmakingDialogRecoveryPolicyTest {
    private val active = MatchmakingDialogRecoveryPolicy.Context(
        paused = false,
        tournamentMode = true,
        gameStarted = false,
    )

    @Test
    fun `only the exact start game error with confirm label is positive evidence`() {
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE,
            StartGameErrorDialogClassifier.classify("发生错误", "开始游戏时发生了错误请等待几分钟然后再试", "确定"),
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Probe.NO_ERROR_DIALOG,
            StartGameErrorDialogClassifier.classify("选择套牌", "狂野对战", "开始"),
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Probe.NO_ERROR_DIALOG,
            StartGameErrorDialogClassifier.classify("连接中断", "请重新连接", "确定"),
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
            StartGameErrorDialogClassifier.classify("", "", ""),
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
            StartGameErrorDialogClassifier.classify("发生错误", "", "确定"),
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
            StartGameErrorDialogClassifier.classify("", "", "确定"),
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE,
            StartGameErrorDialogClassifier.classifyModalText(
                "发生错误 由于你的对手无法连接，游戏无法继续。请再试一次。确定",
                0.91,
                modalVisible = true,
            ),
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE,
            StartGameErrorDialogClassifier.classifyModalText(
                "发生错误 开始游戏时发生了错误。请等待几分钟然后再试。确定",
                0.90,
                modalVisible = true,
            ),
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
            StartGameErrorDialogClassifier.classifyModalText(
                "发生错误 无法重新连接到游戏 确定",
                0.92,
                modalVisible = true,
            ),
            "a reconnect modal must not be clicked as a start-game error",
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN,
            StartGameErrorDialogClassifier.classifyModalText(
                "发生错误 由于你的对手无法连接，游戏无法继续。请再试一次。确定",
                0.99,
                modalVisible = false,
            ),
            "text without a visible modal is not click authorization",
        )
    }

    @Test
    fun `provided screenshot fixture selects and accepts only the exact confirm click`() {
        val fixture = requireNotNull(
            javaClass.getResourceAsStream("/offline-ocr/screen-recovery/start-game-error-dialog-over-deck-selection.png"),
        ).use(ImageIO::read)
        assertEquals(1919, fixture.width)
        assertEquals(1079, fixture.height)

        val transcript = mapOf(
            ScreenStateRoiSelector.START_GAME_ERROR_MODAL_ROI to OcrRecognition(
                "发生错误 由于你的对手无法连接，游戏无法继续。请再试一次。 确定",
                0.96,
            ),
        )
        val originalSettingsProvider = OcrRuntime.settingsProvider
        val originalBridgeFactory = OcrRuntime.paddleXBridgeFactory
        val originalProviderModeProvider = OcrRuntime.providerModeProvider
        val observedRois = mutableListOf<String>()
        val probe = try {
            OcrRuntime.providerModeProvider = { OcrProviderMode.PADDLEX_ONLY }
            OcrRuntime.settingsProvider = {
                PaddleXOcrSettings(
                    enabled = true,
                    pythonExecutable = "offline-fixture",
                    modulePath = "offline-fixture",
                    device = "cpu",
                    modelCachePath = "",
                    timeoutMs = 4_000L,
                )
            }
            OcrRuntime.paddleXBridgeFactory = {
                object : OcrTextBridge {
                    override fun recognize(image: java.awt.image.BufferedImage, desc: String): String =
                        error("fixture should use confidence-aware OCR")

                    override fun recognizeWithConfidence(
                        image: java.awt.image.BufferedImage,
                        desc: String,
                        roi: String?,
                        timeoutMs: Long?,
                    ): OcrRecognition {
                        assertEquals(4_000L, timeoutMs)
                        return transcript.getValue(requireNotNull(roi))
                    }

                    override fun healthCheck(): OcrHealth =
                        OcrHealth(true, OcrProviderKind.PADDLEX, "offline fixture")
                }
            }
            ScreenStateRecovery.probeStartGameErrorDialogForImage(
                image = fixture,
                screenshot = "fixture:start-game-error-dialog-over-deck-selection.png",
            ) { crop, roi ->
                assertTrue(crop.width > 0 && crop.height > 0)
                observedRois += roi
                OcrRuntime.recognizeResult(
                    image = crop,
                    desc = "fixture-start-game-error-$roi",
                    roi = roi,
                    timeoutMs = 4_000L,
                ) {
                    error("PADDLEX_ONLY fixture must not fall back to host OCR")
                }
            }
        } finally {
            OcrRuntime.settingsProvider = originalSettingsProvider
            OcrRuntime.paddleXBridgeFactory = originalBridgeFactory
            OcrRuntime.providerModeProvider = originalProviderModeProvider
        }
        assertEquals("PADDLEX", probe.provider)
        assertEquals(MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE, probe.state)
        assertEquals(setOf(ScreenStateRoiSelector.START_GAME_ERROR_MODAL_ROI), observedRois.toSet())

        val decision = MatchmakingDialogRecoveryPolicy.decide(active, probe.state, 0, false)
        assertEquals(MatchmakingDialogRecoveryPolicy.Action.CLICK_CONFIRM, decision.action)
        var harnessAcceptedClicks = 0
        val accepted = MatchmakingDialogRecoveryPolicy.dispatchConfirm(decision) {
            harnessAcceptedClicks++
            true
        }
        assertEquals(true, accepted)
        assertEquals(1, harnessAcceptedClicks, "the test input adapter must accept one actual dispatch")
    }

    @Test
    fun `low confidence and capture failure remain unknown and never dispatch confirm`() {
        val lowConfidence = StartGameErrorDialogClassifier.classify(
            title = "发生错误",
            body = "开始游戏时发生了错误请等待几分钟然后再试",
            confirm = "确定",
            confidences = listOf(0.9, 0.9, 0.21),
        )
        assertEquals(MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN, lowConfidence)
        val missingCapture = ScreenStateRecovery.probeStartGameErrorDialogForImage(
            image = null,
            recognize = { _, _ -> error("recognizer must not run without a screenshot") },
        )
        assertEquals(MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN, missingCapture.state)

        var dispatched = false
        val decision = MatchmakingDialogRecoveryPolicy.decide(active, lowConfidence, 0, false)
        assertEquals(MatchmakingDialogRecoveryPolicy.Action.WAIT_AND_RETRY, decision.action)
        assertEquals(null, MatchmakingDialogRecoveryPolicy.dispatchConfirm(decision) { dispatched = true; true })
        assertFalse(dispatched)
    }

    @Test
    fun `single modal crop includes title body and confirm in the supplied client frame`() {
        val rois = ScreenStateRoiSelector.selectStartGameError(1258, 947).associateBy { it.name }
        val modal = rois.getValue(ScreenStateRoiSelector.START_GAME_ERROR_MODAL_ROI).bounds

        assertTrue(modal.contains(610, 345), "error heading should be in the modal crop")
        assertTrue(modal.contains(500, 420), "error sentence should be in the modal crop")
        assertTrue(modal.contains(585, 558), "visible 确定 button should be in the modal crop")
        assertEquals(1, rois.size, "the modal must require one bounded OCR request")
    }

    @Test
    fun `ordinary deck selection and unrelated modal never dispatch a click`() {
        val deck = StartGameErrorDialogClassifier.classify("选择套牌", "狂野对战", "开始")
        val unrelated = StartGameErrorDialogClassifier.classify("更新提示", "客户端需要更新", "确定")
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Action.STOP_NOT_PRESENT,
            MatchmakingDialogRecoveryPolicy.decide(active, deck, 0, false).action,
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Action.STOP_NOT_PRESENT,
            MatchmakingDialogRecoveryPolicy.decide(active, unrelated, 0, false).action,
        )
    }

    @Test
    fun `input dispatch is not confirmation until a fresh probe sees disappearance`() {
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Action.CLICK_CONFIRM,
            MatchmakingDialogRecoveryPolicy.decide(
                active,
                MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE,
                0,
                false,
            ).action,
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Action.CLICK_CONFIRM,
            MatchmakingDialogRecoveryPolicy.decide(
                active,
                MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE,
                1,
                true,
            ).action,
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Action.CONFIRMED_DISMISSED,
            MatchmakingDialogRecoveryPolicy.decide(
                active,
                MatchmakingDialogRecoveryPolicy.Probe.NO_ERROR_DIALOG,
                1,
                true,
            ).action,
        )
    }

    @Test
    fun `unknown evidence never clicks and retries only within bounded budget`() {
        val unknown = MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Action.WAIT_AND_RETRY,
            MatchmakingDialogRecoveryPolicy.decide(active, unknown, 0, false).action,
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Action.EXHAUSTED,
            MatchmakingDialogRecoveryPolicy.decide(
                active,
                unknown,
                MatchmakingDialogRecoveryPolicy.MAX_ATTEMPTS,
                false,
            ).action,
        )
    }

    @Test
    fun `visible dialog permits at most twenty clicks before exhaustion`() {
        val visible = MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Action.CLICK_CONFIRM,
            MatchmakingDialogRecoveryPolicy.decide(
                active,
                visible,
                MatchmakingDialogRecoveryPolicy.MAX_ATTEMPTS - 1,
                true,
            ).action,
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Action.EXHAUSTED,
            MatchmakingDialogRecoveryPolicy.decide(
                active,
                visible,
                MatchmakingDialogRecoveryPolicy.MAX_ATTEMPTS,
                true,
            ).action,
        )
    }

    @Test
    fun `pause mode change or started game cancels before any click`() {
        val visible = MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Action.CANCEL,
            MatchmakingDialogRecoveryPolicy.decide(active.copy(paused = true), visible, 0, false).action,
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Action.CANCEL,
            MatchmakingDialogRecoveryPolicy.decide(active.copy(tournamentMode = false), visible, 0, false).action,
        )
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Action.CANCEL,
            MatchmakingDialogRecoveryPolicy.decide(active.copy(gameStarted = true), visible, 0, false).action,
        )
    }
}
