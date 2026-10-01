package club.xiaojiawei.hsscript.strategy.mode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import club.xiaojiawei.hsscript.status.ScreenStateRoiSelector

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
    }

    @Test
    fun `dedicated title body and confirm crops include the dialog in the supplied client frame`() {
        val rois = ScreenStateRoiSelector.selectStartGameError(1258, 947).associateBy { it.name }
        val title = rois.getValue(ScreenStateRoiSelector.START_GAME_ERROR_TITLE_ROI).bounds
        val body = rois.getValue(ScreenStateRoiSelector.START_GAME_ERROR_BODY_ROI).bounds
        val confirm = rois.getValue(ScreenStateRoiSelector.START_GAME_ERROR_CONFIRM_ROI).bounds

        assertTrue(title.contains(610, 345), "error heading should be in the title crop")
        assertTrue(body.contains(500, 420), "error sentence should be in the body crop")
        assertTrue(confirm.contains(585, 558), "visible 确定 button should be in the confirm crop")
        assertEquals(3, rois.size)
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
