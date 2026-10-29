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
        assertTrue(ScreenStateRecovery.hasExactStartupQuestOverlayVisual(image), "fixture must pass the exact image classifier used by live recovery")
        val detection = ScreenStateRecovery.recoveryTransitionForImageForTest(
            image,
            "你的活动任务 每周任务 进度",
            emptyMap(),
        )
        assertEquals("HOME_TASK_OVERLAY", detection?.screen)
        assertEquals("DISMISS_HOME_TASK_OVERLAY", detection?.action)
        val underlayDeckRoi = ScreenStateRecovery.recoveryTransitionForImageForTest(
            image,
            "选择套牌 狂野对战",
            mapOf(ScreenStateRoiSelector.DECK_SELECTION_TITLE_ROI to "选择套牌 狂野对战"),
        )
        assertEquals("HOME_TASK_OVERLAY", underlayDeckRoi?.screen)
    }

    @Test
    fun `generic centered modal and quest-like underlay text remain unknown`() {
        assertEquals(
            null,
            ScreenStateRecovery.recoveryTransitionForTest(
                "你的活动任务 每周任务",
                centeredModalPanelVisual = true,
            ),
        )
        assertEquals(
            null,
            ScreenStateRecovery.recoveryTransitionForTest(
                "传统对战 酒馆战棋 竞技模式",
                centeredModalPanelVisual = true,
            ),
        )
        val genericModal = ImageIO.read(
            requireNotNull(javaClass.getResourceAsStream(
                "/offline-ocr/screen-recovery/start-game-error-dialog-over-deck-selection.png",
            )),
        )
        assertFalse(ScreenStateRecovery.hasExactStartupQuestOverlayVisual(genericModal))
        assertTrue(ScreenStateRecovery.hasCenteredModalPanel(genericModal))
        assertFalse(
            ScreenStateRecovery.recoveryTransitionForImageForTest(
                genericModal,
                "你的活动任务 每周任务",
                emptyMap(),
            )?.screen == "HOME_TASK_OVERLAY",
            "quest-like underlay OCR must not turn a different modal into the dismiss target",
        )
        assertEquals(
            StartupQuestOverlayDismissPolicy.Action.STOP,
            StartupQuestOverlayDismissPolicy.decide(
                0,
                observation(exactQuestOverlay = false, confidence = 0, safeTransition = false),
            ).action,
        )
    }

    @Test
    fun `client restart escalation waits for exact trusted same session and all safety guards`() {
        val policy = StartupQuestOverlayEscalationPolicy()
        val baseline = escalationEvidence(nowMs = 1_000L)
        assertEquals(
            "exact-trusted-current-session-required",
            policy.observe(baseline.copy(exactTrustedQuestOverlay = false)).reason,
        )
        assertEquals(
            "exact-trusted-current-session-required",
            policy.observe(baseline.copy(pid = null)).reason,
        )
        assertEquals("dismiss-tap-budget-not-exhausted", policy.observe(baseline.copy(tapsAttempted = 2)).reason)
        assertEquals("probe-budget-not-exhausted", policy.observe(baseline.copy(observations = 11)).reason)
        assertEquals("active-queue", policy.observe(baseline.copy(noActiveQueue = false)).reason)
        assertEquals("active-game-or-terminal", policy.observe(baseline.copy(noActiveGameOrTerminal = false)).reason)
        assertEquals("paused-by-user-or-operator", policy.observe(baseline.copy(paused = true)).reason)
        assertEquals("script-not-working", policy.observe(baseline.copy(working = false)).reason)
        assertEquals("stuck-threshold-not-reached", policy.observe(baseline.copy(nowMs = 60_999L)).reason)
        assertEquals(
            StartupQuestOverlayEscalationPolicy.Action.RESTART_CLIENT,
            policy.observe(baseline.copy(nowMs = 61_000L)).action,
        )
    }

    @Test
    fun `restart escalation is bounded and alerts once without clearing the fence or pausing`() {
        val policy = StartupQuestOverlayEscalationPolicy()
        val evidence = escalationEvidence(nowMs = 1_000L)
        assertEquals("stuck-threshold-not-reached", policy.observe(evidence).reason)
        assertEquals(StartupQuestOverlayEscalationPolicy.Action.RESTART_CLIENT, policy.observe(evidence.copy(nowMs = 61_000L)).action)
        assertEquals("restart-cooldown", policy.observe(evidence.copy(nowMs = 100_000L)).reason)
        assertEquals(StartupQuestOverlayEscalationPolicy.Action.RESTART_CLIENT, policy.observe(evidence.copy(nowMs = 181_000L)).action)
        assertEquals(StartupQuestOverlayEscalationPolicy.Action.ALERT_OPERATOR, policy.observe(evidence.copy(nowMs = 301_000L)).action)
        assertEquals(StartupQuestOverlayEscalationPolicy.Action.ALREADY_ALERTED, policy.observe(evidence.copy(nowMs = 311_000L)).action)

        val pausedBefore = PauseStatus.isPause
        val automaticPauseBefore = PauseStatus.isAutomaticPause
        val workingBefore = WorkTimeListener.working
        try {
            WorkTimeListener.working = true
            PauseStatus.setAutomaticPause(false)
            StartupQuestOverlayRecoveryFence.begin()
            StartupQuestOverlayRecoveryFence.observeSession(44L, "window-55")
            repeat(StartupQuestOverlayDismissPolicy.MAX_TAPS) { StartupQuestOverlayRecoveryFence.recordTapAttempt() }
            assertTrue(StartupQuestOverlayRecoveryFence.isActive(), "failed escalation does not clear the fence")
            assertFalse(PauseStatus.isPause, "recovery must not synthesize F2 or auto-pause")
        } finally {
            StartupQuestOverlayRecoveryFence.resetForTest()
            WorkTimeListener.working = workingBefore
            when {
                pausedBefore && automaticPauseBefore -> PauseStatus.setAutomaticPause(true)
                pausedBefore -> PauseStatus.setManualPause(true)
                else -> PauseStatus.setAutomaticPause(false)
            }
        }
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

    private fun escalationEvidence(nowMs: Long) = StartupQuestOverlayEscalationPolicy.Evidence(
        nowMs = nowMs,
        pid = 44L,
        window = "window-55",
        exactTrustedQuestOverlay = true,
        tapsAttempted = StartupQuestOverlayDismissPolicy.MAX_TAPS,
        observations = StartupQuestOverlayRecoveryFence.MAX_REOBSERVATIONS,
        noActiveQueue = true,
        noActiveGameOrTerminal = true,
        working = true,
        paused = false,
    )
}
