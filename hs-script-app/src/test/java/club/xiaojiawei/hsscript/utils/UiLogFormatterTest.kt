package club.xiaojiawei.hsscript.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiLogFormatterTest {

    @Test
    fun `screenshot messages show the useful context instead of an absolute path`() {
        val message = UiLogFormatter.format(
            "MULLIGAN_SCREENSHOT stage=post-confirm game=17 " +
                "path=C:\\Users\\yzjsh\\Documents\\Codex\\Hearthstone Script\\log\\mulligan\\game-0017.png"
        )

        assertEquals("换牌截图已保存 · 第17局 · 确认后", message)
        assertFalse(message.contains("C:\\"))
    }

    @Test
    fun `rank messages retain the readable result but hide OCR candidate noise`() {
        val message = UiLogFormatter.format(
            "RANK_OCR text=8 candidates=939|51|191|91 visualTenHint=true tier=SILVER rank=10"
        )

        assertEquals("等级识别 · OCR=8 · 白银10级", message)
        assertFalse(message.contains("candidates"))
        assertFalse(message.contains("visualTenHint"))
    }

    @Test
    fun `uncertain rank keeps only the short OCR and tier summary`() {
        val message = UiLogFormatter.format(
            "RANK_OCR text=1 candidates=1|1|39 visualTenHint=true tier=SILVER rank=UNKNOWN"
        )

        assertEquals("等级识别 · OCR=1 · 白银 · 等级待确认", message)
        assertFalse(message.contains("candidates"))
        assertFalse(message.contains("visualTenHint"))
    }

    @Test
    fun `long MCTS card diagnostics become a short operational summary`() {
        val message = UiLogFormatter.format(
            "MCTS_NEW_DECK_CARD count=16 manualTuning=UNSET priorityStatus=UNSET " +
                "playStyleStatus=UNSET cards=VAC_929,VAC_938,VAC_926t"
        )

        assertEquals("MCTS · 新牌组已识别 · 16张牌 · 手动调优未设置", message)
        assertFalse(message.contains("VAC_929"))
    }

    @Test
    fun `ordinary Chinese messages remain readable`() {
        val message = UiLogFormatter.format("当前处于：特殊效果触发阶段")

        assertTrue(message.contains("特殊效果触发阶段"))
        assertFalse(message.contains("="))
    }

    @Test
    fun `persistent streak evidence stays in the file log only`() {
        val message =
            "PERSISTENT_STREAK_GUARD_TRIGGERED strategy=pirate rule=MAX_CONSECUTIVE_WINS " +
                "consecutiveWins=5 action=SURRENDER evidence=id=17:result=WIN:surrendered=false"

        assertTrue(UiLogFormatter.isHiddenFromUi(message))
        assertFalse(UiLogFormatter.isHiddenFromUi("当前处于：回合结束阶段"))
    }
}
