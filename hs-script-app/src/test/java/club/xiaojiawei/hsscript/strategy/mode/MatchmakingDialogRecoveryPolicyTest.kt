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
import java.io.ByteArrayInputStream
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger

class MatchmakingDialogRecoveryPolicyTest {
    private val active = MatchmakingDialogRecoveryPolicy.Context(
        paused = false,
        tournamentMode = true,
        gameStarted = false,
    )

    @Test
    fun `verified queue modal is accepted visually even when OCR would fail`() {
        val image = BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(35, 35, 35)
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.color = Color(180, 125, 70)
        graphics.fillRect((image.width * 0.36).toInt(), (image.height * 0.13).toInt(),
            (image.width * 0.28).toInt(), (image.height * 0.12).toInt())
        graphics.color = Color(220, 40, 35)
        graphics.fillRect((image.width * 0.42).toInt(), (image.height * 0.30).toInt(),
            (image.width * 0.18).toInt(), (image.height * 0.35).toInt())
        graphics.color = Color(220, 130, 55)
        graphics.fillRect((image.width * 0.45).toInt(), (image.height * 0.80).toInt(),
            (image.width * 0.13).toInt(), (image.height * 0.09).toInt())
        graphics.dispose()

        val probe = ScreenStateRecovery.probeStartGameErrorDialogForImage(image, "fixture:queue-modal") { _, _ ->
            error("OCR must not run after the independent visual queue contract succeeds")
        }
        assertEquals(MatchmakingDialogRecoveryPolicy.Probe.NO_ERROR_DIALOG, probe.state)
        assertEquals(true, probe.queueSearchModalVisible)
        assertEquals("VISUAL", probe.provider)
        assertEquals("verified-queue-search-modal-visual", probe.reason)
    }

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
    fun `both v581 incident screenshots authorize only the exact modal and require fresh UI confirmation`() {
        val modalScreenshots = listOf(
            "/offline-ocr/screen-recovery/opponent-disconnect-user-attachment.png",
            "/offline-ocr/screen-recovery/v581-opponent-disconnect-modal-recovery-exhausted.png",
        )
        val deckSelection = fixture("/offline-ocr/screen-recovery/deck-selection-screen.png")
        val acceptedDispatches = AtomicInteger()

        for (path in modalScreenshots) {
            val screenshot = fixture(path)
            val probe = probeImage(screenshot, path, OPPONENT_DISCONNECT_TEXT)
            assertEquals(
                MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE,
                probe.state,
                "the real screenshot must pass the production visual-modal plus exact-text contract: $path",
            )
            val decision = MatchmakingDialogRecoveryPolicy.decide(active, probe.state, 0, false)
            assertEquals(MatchmakingDialogRecoveryPolicy.Action.CLICK_CONFIRM, decision.action)
            assertTrue(
                MatchmakingDialogRecoveryPolicy.dispatchConfirm(decision) {
                    acceptedDispatches.incrementAndGet()
                    true
                } == true,
                "the input adapter must accept the exact confirm dispatch before it is counted",
            )

            // This fresh, separate Hearthstone frame is the target-system evidence;
            // the accepted SendInput/Robot return value alone is not confirmation.
            val afterClick = probeImage(deckSelection, "fixture:deck-selection-after-confirm", "选择套牌 狂野对战 开始")
            assertEquals(MatchmakingDialogRecoveryPolicy.Probe.NO_ERROR_DIALOG, afterClick.state)
            assertEquals(
                MatchmakingDialogRecoveryPolicy.Action.CONFIRMED_DISMISSED,
                MatchmakingDialogRecoveryPolicy.decide(active, afterClick.state, 1, priorClickSent = true).action,
            )
            assertEquals(
                MatchmakingDialogRecoveryPolicy.Action.STOP_NOT_PRESENT,
                MatchmakingDialogRecoveryPolicy.decide(active, afterClick.state, 1, priorClickSent = false).action,
                "disappearance without an accepted dispatch is not attributed to our click",
            )

            val stillVisible = probeImage(screenshot, "fixture:dialog-still-visible", OPPONENT_DISCONNECT_TEXT)
            assertEquals(
                MatchmakingDialogRecoveryPolicy.Action.CLICK_CONFIRM,
                MatchmakingDialogRecoveryPolicy.decide(active, stillVisible.state, 1, priorClickSent = true).action,
                "a click request without visible state transition is not dismissal confirmation",
            )
        }
        assertEquals(modalScreenshots.size, acceptedDispatches.get())
    }

    @Test
    fun `no progress timeout with an unbound log neither pauses nor consumes modal retry budget`() {
        val dialog = fixture("/offline-ocr/screen-recovery/opponent-disconnect-user-attachment.png")
        val exactProbe = probeImage(dialog, "fixture:pregame-dialog", OPPONENT_DISCONNECT_TEXT)
        assertEquals(MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE, exactProbe.state)

        val watchdog = club.xiaojiawei.hsscript.status.NoProgressWatchdog(noProgressTimeoutMs = 1_000L)
        fun unbound(nowMs: Long) = club.xiaojiawei.hsscript.status.NoProgressWatchdog.Snapshot(
            nowMs = nowMs,
            mode = "TOURNAMENT",
            expectedMode = "TOURNAMENT",
            screen = club.xiaojiawei.hsscript.status.NoProgressWatchdog.ScreenExpectation.STARTUP,
            processAlive = true,
            currentPid = 50204L,
            boundPid = 50204L,
            windowPresent = true,
            powerLogPath = null,
            boundPowerLogPath = null,
            powerLogPosition = Long.MIN_VALUE,
            powerLogLength = 0L,
            powerLogAgeMs = Long.MAX_VALUE,
            powerLogUsable = false,
        )

        val beforeTimeout = watchdog.observe(unbound(0L))
        val afterTimeout = watchdog.observe(unbound(180_000L))
        assertEquals(club.xiaojiawei.hsscript.status.NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, beforeTimeout.action)
        assertEquals(club.xiaojiawei.hsscript.status.NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, afterTimeout.action)
        assertEquals("power-log-unbound-or-unusable", afterTimeout.reason)
        assertEquals(
            MatchmakingDialogRecoveryPolicy.Action.CLICK_CONFIRM,
            MatchmakingDialogRecoveryPolicy.decide(active, exactProbe.state, 0, false).action,
            "the independent exact-modal probe remains bounded and actionable; timeout is not pause/click evidence",
        )
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
    fun `overlapping in-flight timer tick does not consume a completed probe attempt`() {
        var completedAttempts = 0
        repeat(20) {
            if (MatchmakingDialogRecoveryPolicy.countsTowardAttemptBudget("probe-in-flight")) {
                completedAttempts++
            }
        }
        assertEquals(0, completedAttempts)
        assertTrue(MatchmakingDialogRecoveryPolicy.countsTowardAttemptBudget("capture-unavailable"))
        assertTrue(MatchmakingDialogRecoveryPolicy.countsTowardAttemptBudget("ocr-unverified-or-low-confidence"))
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

    private fun fixture(resource: String) = requireNotNull(javaClass.getResourceAsStream(resource)).use(ImageIO::read)

    private fun probeImage(
        image: java.awt.image.BufferedImage,
        screenshot: String,
        text: String,
    ) = ScreenStateRecovery.probeStartGameErrorDialogForImage(image, screenshot) { crop, roi ->
        assertTrue(crop.width > 0 && crop.height > 0)
        assertEquals(ScreenStateRoiSelector.START_GAME_ERROR_MODAL_ROI, roi)
        OcrRecognition(text, 0.96)
    }

    companion object {
        private const val OPPONENT_DISCONNECT_TEXT =
            "发生错误 由于你的对手无法连接，游戏无法继续。请再试一次。确定"
    }
}
