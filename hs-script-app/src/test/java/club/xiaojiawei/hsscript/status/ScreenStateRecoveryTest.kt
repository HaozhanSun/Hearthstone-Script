package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import java.awt.Rectangle
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenStateRecoveryTest {

    @Test
    fun `result postcheck distinguishes terminal result from explicit post-result destination`() {
        assertEquals(true, ScreenStateRecovery.resultVisibilityForTest("RESULT", 90))
        assertEquals(false, ScreenStateRecovery.resultVisibilityForTest("DECK_SELECTION", 90))
        assertEquals(false, ScreenStateRecovery.resultVisibilityForTest("HOME", 90))
        assertNull(ScreenStateRecovery.resultVisibilityForTest("UNKNOWN", 90))
        assertNull(ScreenStateRecovery.resultVisibilityForTest("DECK_SELECTION", 84))
    }
    @Test
    fun `recognizes result action when OCR loses outcome title`() {
        assertTrue(ScreenStateRecovery.looksLikeResultText("本局结果 KennethSun 写击继续"))
        assertTrue(ScreenStateRecovery.looksLikeResultText("败北 点击继续"))
        assertFalse(ScreenStateRecovery.looksLikeResultText("选择套牌 狂野对战"))
    }

    @Test
    fun `recognizes grayscale result page when OCR is empty`() {
        // Measured from the durable rank-result screenshot: the fixed continue
        // band is 0.049 gray-light, banner low-saturation is 0.087, center
        // dark is 0.210, and banner warm ratio is 0.497. Menu frames fail the
        // warm-band check even if another region happens to look dim.
        assertFalse(ScreenStateRecovery.looksLikeResultVisual(0.049, 0.087, 0.210, 0.497))
        assertTrue(ResultPageEvidencePolicy.looksLikeRankProgressContinuationVisual(0.049, 0.087, 0.210, 0.497))
        assertFalse(ScreenStateRecovery.looksLikeResultVisual(0.007, 0.066, 0.210, 0.497))
        assertFalse(ScreenStateRecovery.looksLikeResultVisual(0.048, 0.066, 0.210, 0.497))
        assertFalse(ScreenStateRecovery.looksLikeResultVisual(0.145, 0.276, 0.259, 0.732))
    }

    @Test
    fun `recognizes the offline reconnect page without confusing login or reconnecting states`() {
        assertTrue(ScreenStateRecovery.looksLikeReconnectText("游戏连接中断。重新接..."))
        assertTrue(ScreenStateRecovery.looksLikeReconnectText("当前处于离线状态，请重新连接"))
        assertTrue(ScreenStateRecovery.looksLikeReconnectText("你已离线太久，需要重新连接"))
        assertTrue(ScreenStateRecovery.looksLikeReconnectText("You have been offline too long. You need to reconnect."))
        assertEquals("RECONNECT", ScreenStateRecovery.classifyForTest("你已离线太久，需要重新连接"))
        assertFalse(ScreenStateRecovery.looksLikeReconnectText("登录 Battle.net"))
        assertFalse(ScreenStateRecovery.looksLikeReconnectText("正在重新连接"))
        assertFalse(ScreenStateRecovery.looksLikeReconnectText("选择套牌 狂野对战"))
    }

    @Test
    fun `classifies reconnect spinner separately from offline prompt and ordinary loading`() {
        assertTrue(ScreenStateRecovery.looksLikeReconnectSpinnerText("正在重新连接"))
        assertTrue(ScreenStateRecovery.looksLikeReconnectSpinnerText("Reconnecting to server"))
        assertEquals("RECONNECT_SPINNER", ScreenStateRecovery.classifyForTest("正在重新连接"))
        assertFalse(ScreenStateRecovery.looksLikeReconnectSpinnerText("正在加载，请稍候"))
    }

    @Test
    fun `automatic pause request is suppressed while F2 pause remains blocked from recovery`() {
        val wasPaused = PauseStatus.isPause
        try {
            PauseStatus.setManualPauseForTest(false)
            assertTrue(PauseStatus.canRunAutomaticRecovery())

            assertFalse(PauseStatus.setAutomaticPause(true))
            assertTrue(PauseStatus.canRunAutomaticRecovery())
            assertFalse(PauseStatus.isPause)
            assertTrue(ActionDispatchGate.allowedForState(paused = false, working = true))

            PauseStatus.pauseFromF2()
            assertFalse(PauseStatus.canRunAutomaticRecovery())
            assertFalse(ActionDispatchGate.allowedForState(paused = true, working = true))
            assertFalse(PauseStatus.resumeAutomaticPause("offline-reconnect-test"))
            assertTrue(PauseStatus.isPause)
        } finally {
            PauseStatus.setManualPauseForTest(wasPaused)
        }
    }

    @Test
    fun `recognizes traditional and simplified matchmaking OCR variants`() {
        assertTrue(ScreenStateRecovery.looksLikeMatchmakingText("搜寻对手 取消"))
        assertTrue(ScreenStateRecovery.looksLikeMatchmakingText("寻找对手"))
        assertTrue(ScreenStateRecovery.looksLikeMatchmakingText("正在匹配"))
        assertFalse(ScreenStateRecovery.looksLikeMatchmakingText("还有未领取的奖励"))
        assertEquals("MATCHMAKING", ScreenStateRecovery.classifyForTest("搜寻对手 取消"))
        val queue = ScreenStateRecovery.recoveryTransitionForTest("搜寻对手 取消")
        assertEquals(ModeEnum.TOURNAMENT, queue?.mode)
        assertEquals("WAIT_FOR_GAMEPLAY", queue?.action)
        assertFalse(queue?.enterStrategy ?: true)
    }

    @Test
    fun `recovery screen fixtures map to the state machine action for each visible workflow`() {
        val home = ScreenStateRecovery.recoveryTransitionForTest("传统对战 酒馆战棋 竞技模式 其他模式")
        assertEquals("HOME", home?.screen)
        assertEquals(ModeEnum.HUB, home?.mode)
        assertEquals("ENTER_MODE_STRATEGY", home?.action)

        val deck = ScreenStateRecovery.recoveryTransitionForTest("选择套牌 狂野对战")
        assertEquals("DECK_SELECTION", deck?.screen)
        assertEquals(ModeEnum.TOURNAMENT, deck?.mode)
        assertEquals("START_MATCHING", deck?.action)

        val loading = ScreenStateRecovery.recoveryTransitionForTest("正在加载，请稍候")
        assertEquals("LOADING", loading?.screen)
        assertEquals(ModeEnum.STARTUP, loading?.mode)
        assertEquals("WAIT_FOR_CLIENT", loading?.action)

        val questOverlay = ScreenStateRecovery.recoveryTransitionForTest(
            "你的任务 召唤10个紫罗兰监狱的随从 4/10 施放12个火焰或自然法术 0/12",
        )
        assertEquals("HOME_TASK_OVERLAY", questOverlay?.screen)
        assertEquals(ModeEnum.HUB, questOverlay?.mode)
        assertEquals("DISMISS_HOME_TASK_OVERLAY", questOverlay?.action)
        assertEquals(false, questOverlay?.enterStrategy)
        assertEquals("HOME", ScreenStateRecovery.recoveryTransitionForTest("任务 对战 收藏")?.screen)
    }

    @Test
    fun `v599 rank five deck capture after queue exit remains actionable safe menu evidence`() {
        val image = loadFixture("v599-rank-five-deck-selection.png")
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)
        val transition = ScreenStateRecovery.recoveryTransitionForImageForTest(
            image = image,
            ocrText = "选择套牌 狂野对战 元素法",
            targeted = mapOf(ScreenStateRoiSelector.DECK_SELECTION_TITLE_ROI to "选择套牌"),
        )
        assertEquals("DECK_SELECTION", transition?.screen)
        assertEquals("START_MATCHING", transition?.action)
        assertEquals(ModeEnum.TOURNAMENT, transition?.mode)
        assertFalse(transition?.enterStrategy ?: true)
        assertTrue(
            ScreenRecoveryAuthorityGate.isSafeObservedMenuTransition(
                expectedScreen = transition!!.screen,
                observedScreen = "DECK_SELECTION",
                confidence = 100,
                freshCurrentWindowCapture = true,
            ),
            "a second fresh same-HWND deck observation confirms the safe recovery transition",
        )
        assertFalse(
            ScreenRecoveryAuthorityGate.isSafeObservedMenuTransition(
                expectedScreen = "DECK_SELECTION",
                observedScreen = "UNKNOWN",
                confidence = 0,
                freshCurrentWindowCapture = false,
            ),
        )
        assertEquals(
            false,
            ScreenStateRecovery.resultVisibilityForTest("DECK_SELECTION", 100),
            "a verified deck screen is not mistaken for a terminal result page",
        )
    }

    @Test
    fun `exact matchmaking error dialog is not confused with generic reconnect or loading`() {
        val probe = club.xiaojiawei.hsscript.strategy.mode.StartGameErrorDialogClassifier.classify(
            title = "发生错误",
            body = "开始游戏时发生了错误，请等待几分钟并再次尝试。",
            confirm = "确定",
        )
        assertEquals(
            club.xiaojiawei.hsscript.strategy.mode.MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE,
            probe,
        )
        val unknown = club.xiaojiawei.hsscript.strategy.mode.StartGameErrorDialogClassifier.classify(
            title = "发生错误",
            body = "无法识别的错误内容",
            confirm = "确定",
        )
        assertEquals(club.xiaojiawei.hsscript.strategy.mode.MatchmakingDialogRecoveryPolicy.Probe.UNKNOWN, unknown)
    }

    @Test
    fun `does not treat reward pack advertisement as pack opening`() {
        val reward = "还有未领取的奖励 5包标准卡牌包 确定"
        assertFalse(ScreenStateRecovery.looksLikePackOpeningText(reward))
        assertNull(ScreenStateRecovery.classifyForTest(reward))
        assertTrue(ScreenStateRecovery.looksLikePackOpeningText("打开卡牌包 点击打开"))
        assertEquals("PACK_OPENING", ScreenStateRecovery.classifyForTest("打开卡牌包 点击打开"))
    }

    @Test
    fun `prioritizes reconnect failure over generic login`() {
        val failure = "重新连接失败 无法重新连接。请重新启动《炉石传说》。退出游戏 取消"
        assertTrue(ScreenStateRecovery.looksLikeReconnectFailureText(failure))
        assertEquals("RECONNECT_FAILURE", ScreenStateRecovery.classifyForTest(failure))
        assertFalse(ScreenStateRecovery.looksLikeReconnectFailureText("登录 Battle.net"))
    }

    @Test
    fun `recognizes loading text and leaves blank OCR unresolved`() {
        assertTrue(ScreenStateRecovery.looksLikeLoadingText("正在加载，请稍候"))
        assertEquals("LOADING", ScreenStateRecovery.classifyForTest("正在加载，请稍候"))
        assertNull(ScreenStateRecovery.classifyForTest(""))
        assertTrue(ScreenStateRecovery.looksLikeLoadingVisual(0.499, 0.195, 0.01))
        assertFalse(ScreenStateRecovery.looksLikeLoadingVisual(0.069, 0.127, 0.54))
    }

    @Test
    fun `shop overlay visual signature is classified for recovery without becoming loading`() {
        // Extracted from the live Beta v4.16.375 false-positive run:
        // debug-20260917-014446-006-screen-recovery-90a1da5a-84cc-4c87-8a4d-18b1cb641286.png
        // The page is 英雄皮肤光格-拉面 with a 1168 price, not a loading screen.
        val shopOcr = "英雄皮肤光格-拉面 价格更新剩余时间 1168"
        assertTrue(ScreenStateRecovery.looksLikeLoadingVisual(0.618, 0.238, 0.017))
        assertEquals(
            "SHOP_OVERLAY",
            ScreenStateRecovery.classifyWithVisualForTest(
                ocrText = shopOcr,
                centralDarkRatio = 0.618,
                warmRatio = 0.238,
                blueRatio = 0.017,
            ),
        )
        assertNull(
            ScreenStateRecovery.classifyWithVisualForTest(
                ocrText = shopOcr,
                centralDarkRatio = 0.10,
                warmRatio = 0.04,
                blueRatio = 0.20,
            ),
        )
    }

    @Test
    fun `recognizes black market overlay as hub and rejects a bare market word`() {
        val blackMarket = "流浪者的店 黑市 价格更新剩余时间 活动剩余时间 库存"
        assertTrue(ScreenStateRecovery.looksLikeBlackMarketText(blackMarket))
        assertEquals("HOME", ScreenStateRecovery.classifyForTest(blackMarket))
        assertFalse(ScreenStateRecovery.looksLikeBlackMarketText("黑市"))
        assertNull(ScreenStateRecovery.classifyForTest("黑市"))
    }

    @Test
    fun `primary hub modes outrank the persistent collection navigation label`() {
        val hub = "传统对战 酒馆战棋 竞技模式 其他模式 开包 我的收藏 商店"
        val twoModesWithCollection = "传统对战 酒馆战棋 我的收藏 商店"
        val collection = "我的套牌 卡牌制作 查找 39/40 卡牌"

        assertTrue(ScreenStateRecovery.looksLikeHubText(hub))
        assertEquals("HOME", ScreenStateRecovery.classifyForTest(hub))
        assertTrue(ScreenStateRecovery.looksLikeHubText(twoModesWithCollection))
        assertEquals("HOME", ScreenStateRecovery.classifyForTest(twoModesWithCollection))
        assertFalse(ScreenStateRecovery.looksLikeCollectionText(hub))
        assertTrue(ScreenStateRecovery.classificationEvidenceForTest(hub)
            ?.contains("HOME/HUB：识别到传统对战、酒馆战棋、竞技模式、其他模式") == true)

        assertTrue(ScreenStateRecovery.looksLikeCollectionText(collection))
        assertEquals("COLLECTION", ScreenStateRecovery.classifyForTest(collection))
        assertTrue(ScreenStateRecovery.classificationEvidenceForTest(collection)
            ?.contains("收藏页：识别到我的套牌、卡牌制作") == true)
    }

    @Test
    fun `supplied recovery screenshots retain their two small capture local anchors`() {
        val traditional = loadFixture("traditional-battle-screen.png")
        val deckSelection = loadFixture("deck-selection-screen.png")
        val traditionalRoi = ScreenStateRoiSelector
            .selectTargeted(traditional.width, traditional.height)
            .first { it.name == ScreenStateRoiSelector.TRADITIONAL_BATTLE_ROI }
            .bounds
        val deckSelectionRoi = ScreenStateRoiSelector
            .selectTargeted(deckSelection.width, deckSelection.height)
            .first { it.name == ScreenStateRoiSelector.DECK_SELECTION_TITLE_ROI }
            .bounds

        assertEquals(Rectangle(873, 297, 173, 64), traditionalRoi)
        assertEquals(Rectangle(652, 107, 201, 65), deckSelectionRoi)
        assertTrue(traditionalRoi.contains(958, 329))
        assertTrue(deckSelectionRoi.contains(750, 135))
    }

    @Test
    fun `recovery has no broad center OCR fallback`() {
        val secondaryNames = ScreenStateRoiSelector
            .selectSecondary(1920, 1080)
            .map { it.name }

        assertFalse(secondaryNames.contains("screen-state-center"))
        assertEquals(
            listOf("screen-state-header", "screen-state-footer"),
            secondaryNames,
        )
    }

    @Test
    fun `targeted anchors drive the correct recovery transitions and do not cross trigger`() {
        val hubTransition = ScreenStateRecovery.recoveryTransitionForTest(
            ocrText = "",
            targeted = mapOf(
                ScreenStateRoiSelector.TRADITIONAL_BATTLE_ROI to "传统\n对战",
            ),
        )
        assertEquals("HOME", hubTransition?.screen)
        assertEquals(ModeEnum.HUB, hubTransition?.mode)
        assertEquals(true, hubTransition?.enterStrategy)
        assertEquals("ENTER_MODE_STRATEGY", hubTransition?.action)

        val deckTransition = ScreenStateRecovery.recoveryTransitionForTest(
            ocrText = "",
            targeted = mapOf(
                ScreenStateRoiSelector.DECK_SELECTION_TITLE_ROI to "选择 套牌",
            ),
        )
        assertEquals("DECK_SELECTION", deckTransition?.screen)
        assertEquals(ModeEnum.TOURNAMENT, deckTransition?.mode)
        assertEquals(false, deckTransition?.enterStrategy)
        assertEquals("START_MATCHING", deckTransition?.action)

        assertNull(
            ScreenStateRecovery.recoveryTransitionForTest(
                ocrText = "",
                targeted = mapOf(
                    ScreenStateRoiSelector.TRADITIONAL_BATTLE_ROI to "选择套牌",
                ),
            ),
        )
        assertNull(
            ScreenStateRecovery.recoveryTransitionForTest(
                ocrText = "",
                targeted = mapOf(
                    ScreenStateRoiSelector.DECK_SELECTION_TITLE_ROI to "传统对战",
                ),
            ),
        )
    }

    @Test
    fun `tolerates localized OCR noise in the dedicated traditional battle ROI`() {
        assertTrue(ScreenStateRecovery.looksLikeTraditionalBattleText("传统X寺虐"))
        assertEquals(
            "HOME",
            ScreenStateRecovery.recoveryTransitionForTest(
                ocrText = "",
                targeted = mapOf(
                    ScreenStateRoiSelector.TRADITIONAL_BATTLE_ROI to "传统X寺虐",
                ),
            )?.screen,
        )
        assertFalse(ScreenStateRecovery.looksLikeTraditionalBattleText("选择套牌"))
        assertFalse(ScreenStateRecovery.looksLikeTraditionalBattleText("传统"))
    }

    @Test
    fun `persistent collection navigation label alone is not an opened collection page`() {
        assertFalse(ScreenStateRecovery.looksLikeHubText("我的收藏"))
        assertFalse(ScreenStateRecovery.looksLikeCollectionText("我的收藏"))
        assertEquals(null, ScreenStateRecovery.classifyForTest("我的收藏"))
    }

    @Test
    fun `restarts a confirmed stalled reconnect warning after the recovery threshold`() {
        val warning = "本次连接较平常花费了更多时间。请检查你的网络连接。"
        assertTrue(ScreenStateRecovery.looksLikeStalledReconnectLoadingText(warning))
        assertEquals("LOADING", ScreenStateRecovery.classifyForTest(warning))
        assertFalse(ScreenStateRecovery.looksLikeStalledReconnectLoadingText("正在加载，请稍候"))

        val reconnectAt = 10_000L
        assertFalse(ScreenStateRecovery.shouldRestartStalledReconnectForTest(reconnectAt, 129_999L))
        assertTrue(ScreenStateRecovery.shouldRestartStalledReconnectForTest(reconnectAt, 130_000L))
        assertFalse(ScreenStateRecovery.shouldRestartStalledReconnectForTest(0L, 999_999L))
        assertFalse(ScreenStateRecovery.shouldRestartStalledLoadingForTest(reconnectAt, 129_999L))
        assertTrue(ScreenStateRecovery.shouldRestartStalledLoadingForTest(reconnectAt, 130_000L))
        assertFalse(ScreenStateRecovery.shouldRestartStalledLoadingForTest(0L, 999_999L))

        // A script that attaches after the previous process clicked reconnect
        // must arm the timer from its first explicit slow-reconnect warning.
        assertEquals(10_000L, ScreenStateRecovery.stalledReconnectAnchorForTest(10_000L, 20_000L, 30_000L))
        assertEquals(20_000L, ScreenStateRecovery.stalledReconnectAnchorForTest(0L, 20_000L, 30_000L))
        assertEquals(30_000L, ScreenStateRecovery.stalledReconnectAnchorForTest(0L, 0L, 30_000L))
    }

    @Test
    fun `recognizes Battle net service login failure instead of generic loading`() {
        val chinese = "无法通过暴雪战网服务进行登录。请等待几分钟并再次尝试。"
        val english = "Unable to log in to Blizzard Battle.net service. Please wait a few minutes and try again."

        assertTrue(ScreenStateRecovery.looksLikeReconnectFailureText(chinese))
        assertTrue(ScreenStateRecovery.looksLikeReconnectFailureText(english))
        assertEquals("RECONNECT_FAILURE", ScreenStateRecovery.classifyForTest(chinese))
        assertEquals("RECONNECT_FAILURE", ScreenStateRecovery.classifyForTest(english))
    }

    @Test
    fun `recognizes offline dialog from its bounded status and message ROIs`() {
        assertTrue(
            ScreenStateRecovery.looksLikeReconnectDialogRoiText(
                "当前处于离线状态距离你的上一次操作已经过了很长时间游戏连接中断",
            ),
        )
        assertEquals(
            "RECONNECT",
            ScreenStateRecovery.recoveryTransitionForTest(
                ocrText = "",
                targeted = mapOf(
                    ScreenStateRoiSelector.RECONNECT_DIALOG_STATUS_ROI to "当前处于离线状态",
                    ScreenStateRoiSelector.RECONNECT_DIALOG_MESSAGE_ROI to "游戏连接中断",
                ),
        )?.screen,
        )
    }

    @Test
    fun `opponent cannot connect start-game dialog is not misclassified as a client restart`() {
        val dialogText = "发生错误由于你的对手无法连接游戏无法继续请再试"
        assertFalse(ScreenStateRecovery.looksLikeReconnectFailureDialogRoiText(dialogText))
        val modal = loadFixture("start-game-error-dialog-over-deck-selection.png")
        assertNull(
            ScreenStateRecovery.recoveryTransitionForImageForTest(
                image = modal,
                ocrText = "选择套牌 狂野对战",
                targeted = mapOf(ScreenStateRoiSelector.DECK_SELECTION_TITLE_ROI to "选择套牌"),
            ),
            "the underlay must not restart the client or restart matchmaking while a modal is visible",
        )
        assertEquals("RECONNECT_FAILURE", ScreenStateRecovery.recoveryTransitionForTest("重新连接失败 请重新启动炉石传说")?.screen)
    }

    @Test
    fun `saved reconnect error screenshot with garbled dialog OCR remains unresolved instead of acting on underlay`() {
        val modal = loadFixture("reconnect-failure-dialog-over-deck-selection.png")
        val ordinaryDeck = loadFixture("deck-selection-screen.png")
        // Exact ROI transcript at hs_script.log:7833: the visible modal ROIs
        // were OCR-noise/empty while the title behind it was read as a deck page.
        val deployedOcr = mapOf(
            ScreenStateRoiSelector.DECK_SELECTION_TITLE_ROI to "选择套牌个",
            ScreenStateRoiSelector.RECONNECT_DIALOG_TITLE_ROI to "一人一",
            ScreenStateRoiSelector.RECONNECT_DIALOG_STATUS_ROI to "|AN二:括",
            ScreenStateRoiSelector.RECONNECT_DIALOG_MESSAGE_ROI to "人同人",
        )

        val blocked = ScreenStateRecovery.recoveryTransitionForImageForTest(modal, "", deployedOcr)
        assertNull(blocked, "visual shape alone cannot distinguish reconnect failure from another centered modal")

        val actualDeck = ScreenStateRecovery.recoveryTransitionForImageForTest(ordinaryDeck, "", deployedOcr)
        assertEquals("DECK_SELECTION", actualDeck?.screen)
        assertEquals("START_MATCHING", actualDeck?.action)
    }

    @Test
    fun `second reconnect modal after authoritative win stays unresolved when dialog OCR is inconclusive`() {
        val evidence = requireNotNull(
            javaClass.getResourceAsStream("/offline-ocr/screen-recovery/won-then-reconnect-modal-evidence.txt"),
        ).bufferedReader().use { it.readText() }
        val ownWin = evidence.indexOf("PLAYSTATE value=WON")
        val gameOver = evidence.indexOf("STEP value=FINAL_GAMEOVER")
        val modalCapture = evidence.indexOf("debug-20260926-131221-736-screen-recovery-c3e122a8")
        assertTrue(ownWin >= 0 && gameOver > ownWin && modalCapture > gameOver)

        // This is the second deployed capture, taken after the authoritative
        // WON/FINAL_GAMEOVER markers. At hs_script.log:12646 the same image was
        // OCRed as a deck title plus noise and incorrectly selected DECK_SELECTION.
        val afterWinModal = loadFixture("reconnect-failure-after-won-modal.png")
        val deployedOcr = mapOf(
            ScreenStateRoiSelector.DECK_SELECTION_TITLE_ROI to "选择套牌个",
            ScreenStateRoiSelector.RECONNECT_DIALOG_TITLE_ROI to "一人一",
            ScreenStateRoiSelector.RECONNECT_DIALOG_STATUS_ROI to "|AN",
            ScreenStateRoiSelector.RECONNECT_DIALOG_MESSAGE_ROI to "",
        )
        val transition = ScreenStateRecovery.recoveryTransitionForImageForTest(afterWinModal, "", deployedOcr)
        assertNull(transition)
    }

    private fun loadFixture(name: String) = ImageIO.read(
        requireNotNull(javaClass.getResourceAsStream("/offline-ocr/screen-recovery/$name")),
    )

}
