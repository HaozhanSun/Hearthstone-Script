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
        assertFalse(ScreenStateRecovery.looksLikeReconnectText("登录 Battle.net"))
        assertFalse(ScreenStateRecovery.looksLikeReconnectText("正在重新连接"))
        assertFalse(ScreenStateRecovery.looksLikeReconnectText("选择套牌 狂野对战"))
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

        // A script that attaches after the previous process clicked reconnect
        // must arm the timer from its first explicit slow-reconnect warning.
        assertEquals(10_000L, ScreenStateRecovery.stalledReconnectAnchorForTest(10_000L, 20_000L, 30_000L))
        assertEquals(20_000L, ScreenStateRecovery.stalledReconnectAnchorForTest(0L, 20_000L, 30_000L))
        assertEquals(30_000L, ScreenStateRecovery.stalledReconnectAnchorForTest(0L, 0L, 30_000L))
    }

    private fun loadFixture(name: String) = ImageIO.read(
        requireNotNull(javaClass.getResourceAsStream("/offline-ocr/screen-recovery/$name")),
    )

}
