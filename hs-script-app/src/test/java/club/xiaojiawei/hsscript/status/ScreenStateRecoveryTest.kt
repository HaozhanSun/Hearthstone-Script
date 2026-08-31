package club.xiaojiawei.hsscript.status

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
        assertFalse(ScreenStateRecovery.looksLikePackOpeningText("传统对战 开包 我的收藏 商店"))
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
    fun `does not classify the hub collection navigation button as collection screen`() {
        val hub = "传统对战 酒馆战棋 竞技模式 其他模式 开包 我的收藏 商店"
        val collection = "我的套牌 卡牌制作 查找 39/40 卡牌"

        assertFalse(ScreenStateRecovery.looksLikeCollectionText(hub))
        assertTrue(ScreenStateRecovery.looksLikeHubText(hub))
        assertEquals("HOME", ScreenStateRecovery.classifyForTest(hub))
        assertTrue(ScreenStateRecovery.looksLikeCollectionText(collection))
        assertEquals("COLLECTION", ScreenStateRecovery.classifyForTest(collection))
    }
}
