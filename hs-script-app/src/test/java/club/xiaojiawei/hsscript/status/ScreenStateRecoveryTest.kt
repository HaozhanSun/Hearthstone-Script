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
    fun `recognizes result action when OCR loses outcome title`() {
        assertTrue(ScreenStateRecovery.looksLikeResultText("本局结果 KennethSun 写击继续"))
        assertTrue(ScreenStateRecovery.looksLikeResultText("败北 点击继续"))
        assertFalse(ScreenStateRecovery.looksLikeResultText("选择套牌 狂野对战"))
    }

    @Test
    fun `recognizes grayscale result page when OCR is empty`() {
        // Measured from the durable defeat screenshot captured at 02:37:
        // the fixed continue band is 0.048 gray-light and the result banner
        // is 0.484 low-saturation.  A live gameplay screenshot measured
        // 0.007 and 0.066 respectively.
        assertTrue(ScreenStateRecovery.looksLikeResultVisual(0.048, 0.484))
        assertFalse(ScreenStateRecovery.looksLikeResultVisual(0.007, 0.066))
        assertFalse(ScreenStateRecovery.looksLikeResultVisual(0.048, 0.066))
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
    fun `automatic safety pause can be recovered but manual pause remains blocked`() {
        val wasPaused = PauseStatus.isPause
        try {
            PauseStatus.setManualPause(true)
            assertFalse(PauseStatus.canRunAutomaticRecovery())

            PauseStatus.setAutomaticPause(true)
            assertTrue(PauseStatus.canRunAutomaticRecovery())
            assertTrue(PauseStatus.isAutomaticPause)
            assertFalse(ActionDispatchGate.allowedForState(paused = true, working = true))
            assertTrue(PauseStatus.resumeAutomaticPause("offline-reconnect-test"))
            assertFalse(PauseStatus.isPause)
            assertTrue(ActionDispatchGate.allowedForState(paused = false, working = true))
        } finally {
            PauseStatus.setAutomaticPause(wasPaused)
        }
    }

    @Test
    fun `recognizes traditional and simplified matchmaking OCR variants`() {
        assertTrue(ScreenStateRecovery.looksLikeMatchmakingText("搜寻对手 取消"))
        assertTrue(ScreenStateRecovery.looksLikeMatchmakingText("寻找对手"))
        assertTrue(ScreenStateRecovery.looksLikeMatchmakingText("正在匹配"))
        assertFalse(ScreenStateRecovery.looksLikeMatchmakingText("还有未领取的奖励"))
        assertEquals("MATCHMAKING", ScreenStateRecovery.classifyForTest("搜寻对手 取消"))
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
    fun `reconnect failure dialog wins over the deck selection title`() {
        val dialogText = "发生错误由于你的对手无法连接游戏无法继续请再试"
        assertTrue(ScreenStateRecovery.looksLikeReconnectFailureDialogRoiText(dialogText))
        assertEquals(
            "RECONNECT_FAILURE",
            ScreenStateRecovery.recoveryTransitionForTest(
                ocrText = "",
                targeted = mapOf(
                    ScreenStateRoiSelector.RECONNECT_DIALOG_TITLE_ROI to "发生错误",
                    ScreenStateRoiSelector.DECK_SELECTION_TITLE_ROI to "选择套牌",
                    ScreenStateRoiSelector.RECONNECT_DIALOG_MESSAGE_ROI to dialogText,
                ),
            )?.screen,
        )
    }

    private fun loadFixture(name: String) = ImageIO.read(
        requireNotNull(javaClass.getResourceAsStream("/offline-ocr/screen-recovery/$name")),
    )

}
