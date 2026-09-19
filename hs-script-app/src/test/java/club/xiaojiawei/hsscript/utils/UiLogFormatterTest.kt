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
    fun `screenshot diagnostics expose a clickable Windows file target`() {
        val target = UiLogFormatter.fileTarget(
            "DEBUG_SCREENSHOT event=screen-recovery " +
                "path=C:\\Users\\test user\\screenshots\\recovery.png " +
                "link=file:/C:/Users/test%20user/screenshots/recovery.png"
        )

        assertEquals("C:\\Users\\test user\\screenshots\\recovery.png", target)
    }

    @Test
    fun `screenshot diagnostics accept file URI when only link is present`() {
        val target = UiLogFormatter.fileTarget(
            "RANK_OCR_EVIDENCE link=file:/C:/Users/test%20user/screenshots/rank.png"
        )

        assertEquals("C:\\Users\\test user\\screenshots\\rank.png", target)
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
            "PERSISTENT_STREAK_GUARD_RECOVERY_CONTINUE strategy=pirate rule=MAX_CONSECUTIVE_WINS " +
                "consecutiveWins=5 action=CONTINUE evidence=id=17:result=WIN:surrendered=false"

        assertTrue(UiLogFormatter.isHiddenFromUi(message))
        assertFalse(UiLogFormatter.isHiddenFromUi("当前处于：回合结束阶段"))
    }

    @Test
    fun `native E2E skip diagnostics stay out of the user feed`() {
        val messages = listOf(
            "E2E_NATIVE_SKIP CreateMutex",
            "E2E_NATIVE_SKIP UpdateGameWindow polling",
            "E2E_NATIVE_SKIP system proxy lookup enabled=false",
        )

        assertTrue(messages.all(UiLogFormatter::isHiddenFromUi))
        assertTrue(messages.map(UiLogFormatter::format).all { it.startsWith("端到端诊断") })
    }

    @Test
    fun `window discovery has a short user-facing state message`() {
        assertEquals(
            "端到端 · 已发现游戏窗口 · PID=33552",
            UiLogFormatter.format(
                "E2E_WINDOW_DISCOVERY state=FOUND handle=native@0x47e0888 pid=33552 process=Hearthstone.exe"
            ),
        )
        assertEquals(
            "端到端 · 未发现游戏窗口",
            UiLogFormatter.format("E2E_WINDOW_DISCOVERY state=MISSING handle=null pid=0"),
        )
    }

    @Test
    fun `unrecognized card diagnostics show the Mandarin name and card id`() {
        val message = UiLogFormatter.format(
            "CARD_ACTION_UNRECOGNIZED cardName=钩手拖曳 cardId=CAP_105 " +
                "reason=card-db-missing action=FAIL_CLOSED"
        )

        assertEquals("未识别卡牌 · 钩手拖曳 · ID=CAP_105 · 原因=card-db-missing", message)
        assertFalse(UiLogFormatter.isHiddenFromUi("CARD_ACTION_UNRECOGNIZED cardName=钩手拖曳 cardId=CAP_105"))
    }

    @Test
    fun `safe termination audit stays in the file log only even when repeated`() {
        val repeated = List(3) { "E2E_SAFE_TERMINATE target=Hearthstone.exe pid=${1000 + it}" }

        assertTrue(repeated.all(UiLogFormatter::isHiddenFromUi))
        assertFalse(UiLogFormatter.isHiddenFromUi("E2E_PROCESS_CHECK expected=Hearthstone.exe tasklistFound=true"))
    }

    @Test
    fun `rank ROI evidence is retained in file logs but hidden from compact feed`() {
        assertTrue(UiLogFormatter.isHiddenFromUi("RANK_OCR_ROI provider=PADDLEX screenshot=rank.png"))
        assertFalse(UiLogFormatter.isHiddenFromUi("RANK_OCR_EVIDENCE provider=PADDLEX path=rank.png"))
    }

    @Test
    fun `rank probe diagnostics do not masquerade as an unresolved rank`() {
        val resolved = UiLogFormatter.format(
            "RANK_OCR provider=PADDLEX selectedRank=878 tier=LEGEND rank=878"
        )
        val probe =
            "RANK_OCR_PROBE provider=PADDLEX roi=smallRoi attempted=false " +
                "raw=<not-run> normalized=<not-run> parsedRank=UNKNOWN"

        assertEquals("等级识别 · 传说878级", resolved)
        assertTrue(UiLogFormatter.isHiddenFromUi(probe))
    }

    @Test
    fun `rank policy waiting and skip diagnostics stay out of compact feed`() {
        assertTrue(
            UiLogFormatter.isHiddenFromUi(
                "RANK_POLICY_WAITING_FOR_RANK reason=mulligan-input-not-confirmed action=WAIT"
            )
        )
        assertTrue(
            UiLogFormatter.isHiddenFromUi(
                "RANK_POLICY_SKIP reason=opponent-hero-surrender-already-requested action=SKIP"
            )
        )
    }

    @Test
    fun `surrender decision feed includes the rule and reason`() {
        val message = UiLogFormatter.format(
            "SURRENDER_POLICY_TRIGGERED stage=CURRENT_RANK_RESOLVED " +
                "rule=rank-ocr-unresolved-surrender reason=rank-unresolved-without-legendary " +
                "tier=UNKNOWN action=SURRENDER"
        )

        assertTrue(message.startsWith("等级策略 · 触发投降"))
        assertTrue(message.contains("rank-unresolved-without-legendary"))
    }
}
