package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.status.ScreenWatchdog
import club.xiaojiawei.hsscript.status.ScreenWatchdogKind
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MandatoryRankSurrenderRecoveryPolicyTest {
    @Test
    fun `uncertain mandatory rank surrender must stay in recovery`() {
        assertTrue(MandatoryRankSurrenderRecoveryPolicy.shouldWaitForMoreEvidence(true, false))
    }

    @Test
    fun `ordinary strategy surrender retains existing recovery behavior`() {
        assertFalse(MandatoryRankSurrenderRecoveryPolicy.shouldWaitForMoreEvidence(false, false))
        assertFalse(MandatoryRankSurrenderRecoveryPolicy.shouldWaitForMoreEvidence(true, true))
    }

    @Test
    fun `settings overlay at surrender target permits only the settings surrender step`() {
        val screen = ScreenWatchdog.classifyForTest("设置 选项 投降 退出游戏")

        assertEquals(ScreenWatchdogKind.SETTINGS, screen)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SURRENDER,
            MandatoryRankSurrenderRecoveryPolicy.decide(screen).action,
        )
    }

    @Test
    fun `unknown screen can only be observed and never dispatches a guessed coordinate`() {
        val screen = ScreenWatchdog.classifyForTest("无法识别的画面")

        assertEquals(ScreenWatchdogKind.UNKNOWN, screen)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.OBSERVE_ONLY,
            MandatoryRankSurrenderRecoveryPolicy.decide(screen).action,
        )
    }

    @Test
    fun `each confirmed screen selects only its next surrender control`() {
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SETTINGS,
            MandatoryRankSurrenderRecoveryPolicy.decide(ScreenWatchdogKind.GAMEPLAY).action,
        )
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_CONFIRMATION,
            MandatoryRankSurrenderRecoveryPolicy.decide(ScreenWatchdogKind.SURRENDER_CONFIRMATION).action,
        )
        assertEquals(
            ScreenWatchdogKind.SURRENDER_CONFIRMATION,
            ScreenWatchdog.classifyForTest("确定要投降吗 取消 确认"),
        )
    }
}
