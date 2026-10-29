package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.listener.WorkTimeListener
import java.awt.Point
import java.awt.Rectangle
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StartupQuestOverlayDismissPolicyTest {
    @Test
    fun `matching live screenshot fixture is classified as exact quest overlay`() {
        val image = ImageIO.read(
            requireNotNull(javaClass.getResourceAsStream(
                "/offline-ocr/screen-recovery/startup-active-quests-overlay-20261028.png",
            )),
        )
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)
        val detection = ScreenStateRecovery.recoveryTransitionForTest("你的活动任务 每周任务 进度")
        assertEquals("HOME_TASK_OVERLAY", detection?.screen)
        assertEquals("DISMISS_HOME_TASK_OVERLAY", detection?.action)
    }

    @Test
    fun `fresh exact overlay authorizes at most three taps and then stops`() {
        val policy = StartupQuestOverlayDismissPolicy
        for (taps in 0 until policy.MAX_TAPS) {
            val decision = policy.decide(taps, observation())
            assertEquals(StartupQuestOverlayDismissPolicy.Action.TAP_TOP_LEFT, decision.action)
            assertEquals(taps + 1, decision.tapNumber)
        }
        val exhausted = policy.decide(policy.MAX_TAPS, observation())
        assertEquals(StartupQuestOverlayDismissPolicy.Action.STOP, exhausted.action)
        assertEquals("max-taps-reached", exhausted.reason)
    }

    @Test
    fun `fresh observation stops on overlay disappearance or any safe screen transition`() {
        val home = StartupQuestOverlayDismissPolicy.decide(
            1,
            observation(exactQuestOverlay = false, safeTransition = true),
        )
        assertEquals(StartupQuestOverlayDismissPolicy.Action.RESOLVE_SAFE_TRANSITION, home.action)
        assertEquals("safe-screen-transition", home.reason)

        val unknown = StartupQuestOverlayDismissPolicy.decide(
            1,
            observation(exactQuestOverlay = false, confidence = 0, safeTransition = false),
        )
        assertEquals(StartupQuestOverlayDismissPolicy.Action.STOP, unknown.action)
        assertEquals("exact-quest-overlay-not-confirmed", unknown.reason)
    }

    @Test
    fun `stale untrusted pid hwnd and low confidence captures never authorize a tap`() {
        val invalid = listOf(
            observation(nowMs = 10_000L, captureAtMs = 1_000L) to "capture-stale",
            observation(captureTrusted = false) to "capture-untrusted",
            observation(currentPid = 9L) to "pid-mismatch",
            observation(currentWindow = "other-window") to "window-mismatch",
            observation(confidence = 91) to "confidence-below-threshold",
        )
        invalid.forEach { (evidence, reason) ->
            val decision = StartupQuestOverlayDismissPolicy.decide(0, evidence)
            assertEquals(StartupQuestOverlayDismissPolicy.Action.STOP, decision.action)
            assertEquals(reason, decision.reason)
        }
    }

    @Test
    fun `recent matchmaking and game intent reject quest overlay click`() {
        val matchmaking = StartupQuestOverlayDismissPolicy.decide(
            0,
            observation(noRecentMatchmakingIntent = false),
        )
        assertEquals("recent-matchmaking-intent", matchmaking.reason)
        assertEquals(StartupQuestOverlayDismissPolicy.Action.STOP, matchmaking.action)

        val gameplay = StartupQuestOverlayDismissPolicy.decide(0, observation(noGameIntent = false))
        assertEquals("game-intent", gameplay.reason)
        assertEquals(StartupQuestOverlayDismissPolicy.Action.STOP, gameplay.action)
    }

    @Test
    fun `startup readiness exception is limited to a consumed overlay permit`() {
        val action = StartupQuestOverlayDismissPolicy.DISPATCH_ACTION
        fun allowed(permit: Boolean) = StartupInputAuthorizationPolicy.allows(
            action = action,
            currentSessionReady = false,
            freshOnlineMenuReady = false,
            startupQuestOverlayDismissPermitValid = permit,
        )
        assertFalse(allowed(false))
        assertTrue(allowed(true))
    }

    @Test
    fun `top-left point stays inside the captured client bounds`() {
        assertEquals(
            Point(108, 208),
            StartupQuestOverlayDismissPolicy.topLeftPoint(Rectangle(100, 200, 1920, 1080)),
        )
        assertEquals(null, StartupQuestOverlayDismissPolicy.topLeftPoint(Rectangle(0, 0, 8, 20)))
    }

    @Test
    fun `missing or reused permit cannot dispatch while unresolved fence stays unpaused`() {
        val store = StartupQuestOverlayDismissPermitStore()
        val point = Point(8, 8)
        val valid = assertNotNull(store.issue(44, "window-55", point, 1_000L))
        assertFalse(store.isValid(null, 44, "window-55", point, 1_001L))
        assertFalse(store.isValid(valid, 45, "window-55", point, 1_001L))
        assertFalse(store.isValid(valid, 44, "window-56", point, 1_001L))
        assertFalse(store.isValid(valid, 44, "window-55", Point(9, 8), 1_001L))
        assertTrue(store.consume(valid, 44, "window-55", point, 1_001L))
        assertFalse(store.consume(valid, 44, "window-55", point, 1_002L))

        val wasWorking = WorkTimeListener.working
        val wasPaused = PauseStatus.isPause
        val wasAutomaticPause = PauseStatus.isAutomaticPause
        try {
            WorkTimeListener.working = true
            PauseStatus.setAutomaticPause(false)
            StartupQuestOverlayRecoveryFence.begin()
            repeat(StartupQuestOverlayDismissPolicy.MAX_TAPS) {
                StartupQuestOverlayRecoveryFence.recordTapAttempt()
            }
            assertEquals(
                "max-taps-reached",
                StartupQuestOverlayDismissPolicy.decide(
                    StartupQuestOverlayRecoveryFence.tapsAttempted(),
                    observation(),
                ).reason,
            )
            assertFalse(PauseStatus.isPause, "overlay recovery must never auto-pause")
            assertFalse(ActionDispatchGate.allow("queue.retry"), "unresolved overlay fences ordinary actions")

            var dispatchRan = false
            assertFalse(
                ActionDispatchGate.dispatchStartupQuestOverlayDismiss(
                    null,
                    44,
                    "window-55",
                    point,
                    1_001L,
                ) {
                    dispatchRan = true
                    true
                },
            )
            assertFalse(dispatchRan)
            assertTrue(StartupQuestOverlayRecoveryFence.isActive())
            assertFalse(PauseStatus.isPause)
        } finally {
            StartupQuestOverlayRecoveryFence.resetForTest()
            WorkTimeListener.working = wasWorking
            when {
                wasPaused && wasAutomaticPause -> PauseStatus.setAutomaticPause(true)
                wasPaused -> PauseStatus.setManualPause(true)
                else -> PauseStatus.setAutomaticPause(false)
            }
        }
    }

    private fun observation(
        nowMs: Long = 10_000L,
        captureAtMs: Long = 9_900L,
        captureTrusted: Boolean = true,
        currentPid: Long? = 44L,
        expectedPid: Long? = 44L,
        currentWindow: String? = "window-55",
        expectedWindow: String? = "window-55",
        exactQuestOverlay: Boolean = true,
        confidence: Int = 96,
        noRecentMatchmakingIntent: Boolean = true,
        noGameIntent: Boolean = true,
        safeTransition: Boolean = false,
    ) = StartupQuestOverlayDismissPolicy.Observation(
        nowMs = nowMs,
        captureAtMs = captureAtMs,
        captureTrusted = captureTrusted,
        currentPid = currentPid,
        expectedPid = expectedPid,
        currentWindow = currentWindow,
        expectedWindow = expectedWindow,
        exactQuestOverlay = exactQuestOverlay,
        confidence = confidence,
        noRecentMatchmakingIntent = noRecentMatchmakingIntent,
        noGameIntent = noGameIntent,
        safeTransition = safeTransition,
    )
}
