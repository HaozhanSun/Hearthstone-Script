package club.xiaojiawei.hsscript.status

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
