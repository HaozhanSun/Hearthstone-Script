package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import kotlin.test.Test
import kotlin.test.assertEquals

class StartupQuestOverlayPolicyTest {
    @Test
    fun `the observed Your Quests modal dispatches a bounded close then enters hub only after fresh hub pixels`() {
        val detected = ScreenStateRecovery.recoveryTransitionForTest(
            "你的任务 召唤10个紫罗兰监狱的随从 4/10 1000 施放12个火焰或自然法术 0/12",
        )
        assertEquals("HOME_TASK_OVERLAY", detected?.screen)
        assertEquals(ModeEnum.HUB, detected?.mode)
        assertEquals("DISMISS_HOME_TASK_OVERLAY", detected?.action)
        assertEquals(false, detected?.enterStrategy)
        assertEquals("HOME", ScreenStateRecovery.recoveryTransitionForTest("任务 对战 收藏")?.screen)

        assertEquals(
            StartupQuestOverlayPolicy.Action.DISMISS_OVERLAY,
            StartupQuestOverlayPolicy.decide(
                StartupQuestOverlayPolicy.Observation.QUEST_OVERLAY,
                captureTrusted = true,
                dismissDispatches = 0,
            ),
        )
        // A successful SendInput return is not the UI accepting the click.
        // Until fresh OCR says HUB, a still-visible modal gets only one
        // bounded retry and never enters the ordinary mode strategy.
        assertEquals(
            StartupQuestOverlayPolicy.Action.DISMISS_OVERLAY,
            StartupQuestOverlayPolicy.decide(
                StartupQuestOverlayPolicy.Observation.QUEST_OVERLAY,
                captureTrusted = true,
                dismissDispatches = 1,
            ),
        )
        assertEquals(
            StartupQuestOverlayPolicy.Action.ENTER_HUB,
            StartupQuestOverlayPolicy.decide(
                StartupQuestOverlayPolicy.Observation.HUB,
                captureTrusted = true,
                dismissDispatches = 1,
            ),
        )
    }

    @Test
    fun `untrusted or unknown recapture blocks clicks and exhausted modal stays out of hub`() {
        assertEquals(
            StartupQuestOverlayPolicy.Action.BLOCK_UNTRUSTED,
            StartupQuestOverlayPolicy.decide(
                StartupQuestOverlayPolicy.Observation.HUB,
                captureTrusted = false,
                dismissDispatches = 0,
            ),
        )
        assertEquals(
            StartupQuestOverlayPolicy.Action.WAIT_FOR_TRUSTED_CAPTURE,
            StartupQuestOverlayPolicy.decide(
                StartupQuestOverlayPolicy.Observation.UNKNOWN,
                captureTrusted = true,
                dismissDispatches = 1,
            ),
        )
        assertEquals(
            StartupQuestOverlayPolicy.Action.EXHAUSTED,
            StartupQuestOverlayPolicy.decide(
                StartupQuestOverlayPolicy.Observation.QUEST_OVERLAY,
                captureTrusted = true,
                dismissDispatches = StartupQuestOverlayPolicy.MAX_DISMISS_DISPATCHES,
            ),
        )
    }
}
