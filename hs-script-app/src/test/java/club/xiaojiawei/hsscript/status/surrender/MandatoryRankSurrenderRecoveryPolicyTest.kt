package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.status.ScreenWatchdog
import club.xiaojiawei.hsscript.status.ScreenWatchdogKind
import club.xiaojiawei.hsscript.status.ActionDispatchGate
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.strategy.mode.MatchmakingGuardPolicy
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import club.xiaojiawei.hsscript.utils.GameUtil
import javax.imageio.ImageIO

class MandatoryRankSurrenderRecoveryPolicyTest {
    private val originalBetaRecoveryEnabled = ConfigUtil.getBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED)

    @AfterEach
    fun cleanup() {
        MandatoryRankSurrenderGuard.resetForTest()
        ConfigUtil.putBoolean(
            ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED,
            originalBetaRecoveryEnabled,
            store = false,
        )
    }

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
    fun `mandatory rank retry budget is finite and cannot restart in new batches`() {
        assertTrue(MandatoryRankSurrenderRecoveryPolicy.hasRetryBudget(0))
        assertTrue(MandatoryRankSurrenderRecoveryPolicy.hasRetryBudget(29))
        assertFalse(MandatoryRankSurrenderRecoveryPolicy.hasRetryBudget(30))
        assertEquals(30, MandatoryRankSurrenderRecoveryPolicy.MAX_RETRY_ATTEMPTS)
    }

    @Test
    fun `cooldown scheduler ticks do not consume mandatory rank inspection retry budget`() {
        var attemptsStarted = 0
        repeat(30) {
            attemptsStarted = MandatoryRankSurrenderRecoveryPolicy.attemptsAfterInspectionStart(
                attemptsStarted,
                inspectionStarted = false,
            )
        }

        assertEquals(0, attemptsStarted)
        assertTrue(MandatoryRankSurrenderRecoveryPolicy.hasRetryBudget(attemptsStarted))

        attemptsStarted = MandatoryRankSurrenderRecoveryPolicy.attemptsAfterInspectionStart(
            attemptsStarted,
            inspectionStarted = true,
        )
        assertEquals(1, attemptsStarted)
    }

    @Test
    fun `fresh rank denial dispatches the verified settings step without waiting for the generic stuck threshold`() {
        ScreenWatchdog.resetTimingForTest()
        val rankResolvedAt = 500_000L
        val timing = ScreenWatchdog.shouldInspect(
            startedAt = rankResolvedAt,
            attempts = 1,
            now = rankResolvedAt + 500L,
            stuckMs = 300_000L,
            maxRetries = 3,
            cooldownMs = 15_000L,
            bypassInitialDelayForMandatorySurrender =
                MandatoryRankSurrenderRecoveryPolicy.shouldInspectImmediatelyAfterRankResolution(0),
        )
        val mulligan = readFixture("rank6-live-mulligan-unknown-ocr.png")
        val observation = ScreenWatchdog.inspectForSurrender(
            state = "mode=GAMEPLAY|inWar=true|warPhase=REPLACE_CARD|myTurn=false|myMulliganInput=true",
            attempts = 1,
            mandatoryRankSurrender = true,
            captureProvider = { mulligan },
            ocrProvider = { error("a verified Mulligan visual must dispatch its safe Settings step without OCR") },
        )

        assertTrue(timing.shouldInspect, timing.reason)
        assertTrue(timing.reason.startsWith("mandatory-surrender-initial-dispatch"))
        assertEquals(ScreenWatchdogKind.MULLIGAN, observation.kind, observation.reason)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SETTINGS,
            MandatoryRankSurrenderRecoveryPolicy.decide(observation.kind).action,
        )
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
    fun `settings settle probe dispatches surrender only after fresh overlay evidence and quickly rechecks uncertainty`() {
        val probe = ScreenWatchdog.MandatorySurrenderPostClickProbe()
        val gearInputAt = 3_000_000L
        val settledAt = gearInputAt + ScreenWatchdog.MandatorySurrenderPostClickProbe.SETTINGS_OVERLAY_MAX_PROBE_LATENCY_MS
        probe.markSettingsClickDispatched(gearInputAt)
        assertTrue(requireNotNull(probe.settingsOverlayProbeTiming(settledAt)).shouldInspect)

        val state = "mode=GAMEPLAY|inWar=true|warPhase=REPLACE_CARD|myTurn=false|myMulliganInput=true"
        val uncertain = ScreenWatchdog.classifyForSurrenderForTest(
            "unreadable labels",
            state,
            BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB),
        )
        assertEquals(ScreenWatchdogKind.UNKNOWN, uncertain)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.OBSERVE_ONLY,
            MandatoryRankSurrenderRecoveryPolicy.decide(uncertain).action,
            "an uncertain post-gear frame cannot click surrender",
        )
        probe.finishSettingsOverlayProbe(settingsConfirmed = false, now = settledAt)
        val recheckAt = settledAt + ScreenWatchdog.MandatorySurrenderPostClickProbe.SETTINGS_OVERLAY_UNCERTAIN_RECHECK_MS
        assertTrue(requireNotNull(probe.settingsOverlayProbeTiming(recheckAt)).shouldInspect)

        val settings = readFixture("rank6-live-mulligan-settings-overlay.png")
        val confirmed = ScreenWatchdog.classifyForSurrenderForTest("unreadable labels", state, settings)
        assertEquals(ScreenWatchdogKind.SETTINGS, confirmed)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SURRENDER,
            MandatoryRankSurrenderRecoveryPolicy.decide(confirmed).action,
        )
        assertTrue(
            settledAt - gearInputAt <=
                ScreenWatchdog.MandatorySurrenderPostClickProbe.SETTINGS_OVERLAY_MAX_PROBE_LATENCY_MS,
            "fresh Settings evidence reaches the only surrender dispatch action within the post-gear latency bound",
        )
        probe.finishSettingsOverlayProbe(settingsConfirmed = true, now = recheckAt)
        assertEquals(null, probe.settingsOverlayProbeTiming(recheckAt + 1))
    }

    @Test
    fun `fresh settings overlay visual overrides mulligan background only when all menu buttons are present`() {
        val settings = readFixture("rank6-live-mulligan-settings-overlay.png")
        val preSettingsFrames = listOf(
            "rank6-live-mulligan-unknown-ocr.png",
            "rank6-live-mulligan-unknown-ocr-second-frame.png",
            "rank6-live-mulligan-pre-settings.png",
        ).map { it to readFixture(it) }
        val state = "mode=GAMEPLAY|inWar=true|warPhase=REPLACE_CARD|myTurn=false|myMulliganInput=true"
        val diagnostics = ScreenWatchdog.settingsOverlayDiagnosticsForTest(settings)

        println("LIVE_SETTINGS_OVERLAY $diagnostics")
        assertTrue(ScreenWatchdog.hasSettingsOverlayVisualForTest(settings), diagnostics)
        preSettingsFrames.forEach { (name, image) ->
            val negativeDiagnostics = ScreenWatchdog.settingsOverlayDiagnosticsForTest(image)
            println("PRE_SETTINGS_NEGATIVE name=$name $negativeDiagnostics")
            assertFalse(ScreenWatchdog.hasSettingsOverlayVisualForTest(image), "$name: $negativeDiagnostics")
            assertEquals(
                ScreenWatchdogKind.MULLIGAN,
                ScreenWatchdog.classifyForSurrenderForTest("unreadable mulligan labels", state, image),
                name,
            )
        }
        val settingsKind = ScreenWatchdog.classifyForSurrenderForTest(
            "保留或替换卡牌 确认",
            state,
            settings,
        )
        assertEquals(ScreenWatchdogKind.SETTINGS, settingsKind, diagnostics)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SURRENDER,
            MandatoryRankSurrenderRecoveryPolicy.decide(settingsKind).action,
        )
        val liveObservation = ScreenWatchdog.inspectForSurrender(
            state = state,
            attempts = 27,
            mandatoryRankSurrender = true,
            captureProvider = { settings },
            ocrProvider = { error("a visually confirmed Settings overlay must not wait on full-screen OCR") },
        )
        assertEquals(ScreenWatchdogKind.SETTINGS, liveObservation.kind, liveObservation.reason)
        assertEquals("fresh-settings-overlay-visual-priority", liveObservation.reason)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SURRENDER,
            MandatoryRankSurrenderRecoveryPolicy.decide(liveObservation.kind).action,
        )

        // A new probe that sees only the underlying mulligan must not skip
        // straight to the confirmation coordinate.
        val nextFreshProbe = ScreenWatchdog.classifyForSurrenderForTest(
            "unreadable mulligan labels",
            state,
            preSettingsFrames.last().second,
        )
        assertEquals(ScreenWatchdogKind.MULLIGAN, nextFreshProbe)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SETTINGS,
            MandatoryRankSurrenderRecoveryPolicy.decide(nextFreshProbe).action,
        )
        assertFalse(
            MandatoryRankSurrenderRecoveryPolicy.decide(nextFreshProbe).action ==
                MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_CONFIRMATION,
        )
        val confirmedDialog = ScreenWatchdog.classifyForTest("确定要投降吗 取消 确认")
        assertEquals(ScreenWatchdogKind.SURRENDER_CONFIRMATION, confirmedDialog)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_CONFIRMATION,
            MandatoryRankSurrenderRecoveryPolicy.decide(confirmedDialog).action,
        )
    }

    @Test
    fun `live rank three board colors cannot be mistaken for settings or authorize surrender`() {
        val board = readFixture("rank3-live-board-settings-false-positive.png")
        val state = "mode=GAMEPLAY|inWar=true|warPhase=GAME_TURN|myTurn=false|myMulliganInput=false"
        val diagnostics = ScreenWatchdog.settingsOverlayDiagnosticsForTest(board)

        println("LIVE_RANK3_BOARD_NOT_SETTINGS $diagnostics")
        assertFalse(ScreenWatchdog.hasSettingsOverlayVisualForTest(board), diagnostics)
        assertTrue(
            ScreenWatchdog.classifyForSurrenderForTest("unreadable board labels", state, board) !=
                ScreenWatchdogKind.SETTINGS,
            "the active-board capture must never classify as a Settings overlay",
        )

        val observation = ScreenWatchdog.inspectForSurrender(
            state = state,
            attempts = 42,
            mandatoryRankSurrender = true,
            captureProvider = { board },
            ocrProvider = { "unreadable board labels" },
        )
        assertTrue(observation.kind != ScreenWatchdogKind.SETTINGS, observation.reason)
        assertTrue(
            MandatoryRankSurrenderRecoveryPolicy.decide(observation.kind).action !=
                MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SURRENDER,
            "a live board is not evidence that the Settings surrender button is present",
        )
    }

    @Test
    fun `transient mulligan emote masking banner uses authoritative card signature before OCR`() {
        val state = "mode=GAMEPLAY|inWar=true|warPhase=REPLACE_CARD|myTurn=false|myMulliganInput=true"
        val animatedMulligan = maskMulliganBannerAndAddEmote(
            readFixture("rank6-live-mulligan-unknown-ocr-second-frame.png"),
        )
        val diagnostics = ScreenWatchdog.mulliganVisualDiagnosticsForTest(animatedMulligan)
        println("ANIMATED_MULLIGAN $diagnostics")
        assertTrue(diagnostics.contains("animationFallback=true"), diagnostics)
        assertTrue(ScreenWatchdog.hasMulliganVisualForTest(animatedMulligan), diagnostics)

        val observation = ScreenWatchdog.inspectForSurrender(
            state = state,
            attempts = 34,
            mandatoryRankSurrender = true,
            captureProvider = { animatedMulligan },
            ocrProvider = { error("authoritative Mulligan plus card signature must bypass slow OCR") },
        )
        assertEquals(ScreenWatchdogKind.MULLIGAN, observation.kind, observation.reason)
        assertEquals("authoritative-mulligan-input-and-fresh-mulligan-visual", observation.reason)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SETTINGS,
            MandatoryRankSurrenderRecoveryPolicy.decide(observation.kind).action,
        )

        // The same colors are insufficient without authoritative current-player
        // Mulligan input; this must remain UNKNOWN rather than authorize a click.
        val nonAuthoritative = ScreenWatchdog.inspectForSurrender(
            state = "mode=GAMEPLAY|inWar=true|warPhase=GAME_TURN|myTurn=false|myMulliganInput=false",
            attempts = 35,
            mandatoryRankSurrender = true,
            captureProvider = { animatedMulligan },
            ocrProvider = { "unreadable" },
        )
        assertEquals(ScreenWatchdogKind.UNKNOWN, nonAuthoritative.kind, nonAuthoritative.reason)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.OBSERVE_ONLY,
            MandatoryRankSurrenderRecoveryPolicy.decide(nonAuthoritative.kind).action,
        )
    }

    @Test
    fun `unknown rank surrender screens stay observe-only and diagnostic cadence is bounded`() {
        assertTrue(MandatoryRankSurrenderRecoveryPolicy.shouldWaitForMoreEvidence(true, false))
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.OBSERVE_ONLY,
            MandatoryRankSurrenderRecoveryPolicy.decide(ScreenWatchdogKind.UNKNOWN).action,
        )
        assertTrue(MandatoryRankSurrenderRecoveryPolicy.shouldEmitUnknownObservationDiagnostic(1))
        assertFalse(MandatoryRankSurrenderRecoveryPolicy.shouldEmitUnknownObservationDiagnostic(2))
        assertTrue(MandatoryRankSurrenderRecoveryPolicy.shouldEmitUnknownObservationDiagnostic(3))
        assertFalse(MandatoryRankSurrenderRecoveryPolicy.shouldEmitUnknownObservationDiagnostic(4))
    }

    @Test
    fun `authoritative mulligan beats settings OCR alone but fresh overlapping settings overlay allows surrender`() {
        val state = "mode=GAMEPLAY|inWar=true|warPhase=REPLACE_CARD|myTurn=false|myMulliganInput=true"
        val mulligan = readFixture("rank6-live-mulligan-unknown-ocr-second-frame.png")
        val settings = readFixture("rank6-live-mulligan-settings-overlay.png")

        // The raw Mulligan image can contain card colors that resemble parts
        // of the menu. It has no complete Settings signature, so even OCR
        // text claiming Settings must not route to the surrender coordinate.
        assertTrue(ScreenWatchdog.hasMulliganVisualForTest(mulligan))
        assertFalse(ScreenWatchdog.hasSettingsOverlayVisualForTest(mulligan))
        val mulliganObservation = ScreenWatchdog.inspectForSurrender(
            state = state,
            attempts = 31,
            mandatoryRankSurrender = true,
            captureProvider = { mulligan },
            ocrProvider = { "设置 投降" },
        )
        assertEquals(ScreenWatchdogKind.MULLIGAN, mulliganObservation.kind, mulliganObservation.reason)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SETTINGS,
            MandatoryRankSurrenderRecoveryPolicy.decide(mulliganObservation.kind).action,
        )

        // The Settings panel is a genuine fresh overlay over the same live
        // Mulligan phase, so both underlay and overlay signatures may match.
        // The topmost Settings evidence permits exactly the next surrender
        // step; the confirmation-dialog visual is still required afterward.
        assertTrue(ScreenWatchdog.hasMulliganVisualForTest(settings))
        assertTrue(ScreenWatchdog.hasSettingsOverlayVisualForTest(settings))
        val settingsObservation = ScreenWatchdog.inspectForSurrender(
            state = state,
            attempts = 32,
            mandatoryRankSurrender = true,
            captureProvider = { settings },
            ocrProvider = { "起始手牌" },
        )
        assertEquals(ScreenWatchdogKind.SETTINGS, settingsObservation.kind, settingsObservation.reason)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SURRENDER,
            MandatoryRankSurrenderRecoveryPolicy.decide(settingsObservation.kind).action,
        )

        // Settings OCR by itself is never enough when the current capture
        // contains neither a confirmed Mulligan nor a Settings overlay.
        val unknownObservation = ScreenWatchdog.inspectForSurrender(
            state = "mode=GAMEPLAY|inWar=true|warPhase=GAME_TURN|myTurn=false",
            attempts = 33,
            mandatoryRankSurrender = true,
            captureProvider = { BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB) },
            ocrProvider = { "设置 投降" },
        )
        assertEquals(ScreenWatchdogKind.UNKNOWN, unknownObservation.kind, unknownObservation.reason)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.OBSERVE_ONLY,
            MandatoryRankSurrenderRecoveryPolicy.decide(unknownObservation.kind).action,
        )
    }

    @Test
    fun `terminal OCR wins over settings overlay and settings-looking colors without panel remain unknown`() {
        val settings = readFixture("rank6-live-mulligan-settings-overlay.png")
        val state = "mode=GAMEPLAY|inWar=true|warPhase=REPLACE_CARD|myTurn=false|myMulliganInput=true"

        assertEquals(
            ScreenWatchdogKind.WIN,
            ScreenWatchdog.classifyForSurrenderForTest("胜利 点击继续", state, settings),
        )
        assertEquals(
            ScreenWatchdogKind.LOST,
            ScreenWatchdog.classifyForSurrenderForTest("失败 点击继续", state, settings),
        )
        assertEquals(
            ScreenWatchdogKind.RESULT,
            ScreenWatchdog.classifyForSurrenderForTest("本局结果 对战结束", state, settings),
        )
        // OCR text alone must not authorize the final surrender click without
        // a fresh visual confirmation dialog.
        assertEquals(
            ScreenWatchdogKind.UNKNOWN,
            ScreenWatchdog.classifyForSurrenderForTest("投降 确认 取消", state, settings),
        )

        val blank = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
        assertFalse(ScreenWatchdog.hasSettingsOverlayVisualForTest(blank))
        assertEquals(
            ScreenWatchdogKind.UNKNOWN,
            ScreenWatchdog.classifyForSurrenderForTest("unreadable", state, blank),
        )
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.OBSERVE_ONLY,
            MandatoryRankSurrenderRecoveryPolicy.decide(ScreenWatchdogKind.UNKNOWN).action,
        )

        // Even a strong nonterminal overlay signature cannot short-circuit
        // OCR when Power.log already reports a terminal game phase.
        val terminalState = "mode=GAMEPLAY|inWar=true|warPhase=GAME_OVER|won=true"
        assertEquals(null, ScreenWatchdog.fastVisualKindForSurrenderForTest(terminalState, settings))
        var terminalOcrCalled = false
        val terminalObservation = ScreenWatchdog.inspectForSurrender(
            state = terminalState,
            attempts = 36,
            mandatoryRankSurrender = true,
            captureProvider = { settings },
            ocrProvider = {
                terminalOcrCalled = true
                "胜利 点击继续"
            },
        )
        assertTrue(terminalOcrCalled)
        assertEquals(ScreenWatchdogKind.WIN, terminalObservation.kind)
    }

    @Test
    fun `fresh confirmation modal over mulligan or board authorizes only the final surrender click`() {
        val confirmationFrames = listOf(
            "rank7-live-surrender-confirmation-mulligan-overlay.png",
            "rank7-live-surrender-confirmation-board-overlay.png",
        ).map { it to readFixture(it) }
        val mulliganState = "mode=GAMEPLAY|inWar=true|warPhase=REPLACE_CARD|myTurn=false|myMulliganInput=true"
        val boardState = "mode=GAMEPLAY|inWar=true|warPhase=GAME_TURN|myTurn=false|myMulliganInput=false"
        val settingsOnly = readFixture("rank6-live-mulligan-settings-overlay.png")
        val mulliganOnly = readFixture("rank6-live-mulligan-pre-settings.png")
        assertFalse(ScreenWatchdog.hasSurrenderConfirmationPanelForTest(settingsOnly))
        assertFalse(ScreenWatchdog.hasSurrenderConfirmationPanelForTest(mulliganOnly))

        // The full sequence is re-observed after each single UI click; no
        // stale frame can authorize skipping directly to confirmation.
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SETTINGS,
            MandatoryRankSurrenderRecoveryPolicy.decide(
                ScreenWatchdog.classifyForSurrenderForTest("unreadable", mulliganState, mulliganOnly),
            ).action,
        )
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SURRENDER,
            MandatoryRankSurrenderRecoveryPolicy.decide(
                ScreenWatchdog.classifyForSurrenderForTest("unreadable", mulliganState, settingsOnly),
            ).action,
        )

        confirmationFrames.forEachIndexed { index, (name, image) ->
            val diagnostics = ScreenWatchdog.surrenderConfirmationDiagnosticsForTest(image)
            println("LIVE_SURRENDER_CONFIRMATION name=$name $diagnostics")
            assertTrue(ScreenWatchdog.hasSurrenderConfirmationPanelForTest(image), "$name: $diagnostics")
            assertTrue(ScreenWatchdog.hasSurrenderConfirmationVisualForTest(image), "$name: $diagnostics")
            val state = if (index == 0) mulliganState else boardState
            val kind = ScreenWatchdog.classifyForSurrenderForTest("unreadable background OCR", state, image)
            assertEquals(ScreenWatchdogKind.SURRENDER_CONFIRMATION, kind, "$name: $diagnostics")
            assertEquals(
                MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_CONFIRMATION,
                MandatoryRankSurrenderRecoveryPolicy.decide(kind).action,
                name,
            )
            assertEquals(
                MandatoryRankSurrenderRecoveryPolicy.ConfirmationTarget.ACCEPT_NOW,
                MandatoryRankSurrenderRecoveryPolicy.decide(kind).confirmationTarget,
                "the observed modal must target affirmative '现在认输', never '继续游戏'",
            )

            val observation = ScreenWatchdog.inspectForSurrender(
                state = state,
                attempts = 28 + index,
                mandatoryRankSurrender = true,
                captureProvider = { image },
                ocrProvider = { "unreadable background OCR" },
            )
            assertEquals(ScreenWatchdogKind.SURRENDER_CONFIRMATION, observation.kind, observation.reason)
            assertEquals("fresh-surrender-confirmation-visual", observation.reason)
            assertEquals(
                MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_CONFIRMATION,
                MandatoryRankSurrenderRecoveryPolicy.decide(observation.kind).action,
            )
            assertEquals(
                MandatoryRankSurrenderRecoveryPolicy.ConfirmationTarget.ACCEPT_NOW,
                MandatoryRankSurrenderRecoveryPolicy.decide(observation.kind).confirmationTarget,
            )
        }

        // A partially obscured choice row still looks like a modal, but is
        // deliberately UNKNOWN; the settings menu behind it cannot win.
        val ambiguous = maskConfirmationContinueButton(confirmationFrames.first().second)
        val ambiguousDiagnostics = ScreenWatchdog.surrenderConfirmationDiagnosticsForTest(ambiguous)
        assertTrue(ScreenWatchdog.hasSurrenderConfirmationPanelForTest(ambiguous), ambiguousDiagnostics)
        assertFalse(ScreenWatchdog.hasSurrenderConfirmationVisualForTest(ambiguous), ambiguousDiagnostics)
        assertEquals(
            ScreenWatchdogKind.UNKNOWN,
            ScreenWatchdog.classifyForSurrenderForTest("设置 选项 投降", mulliganState, ambiguous),
            ambiguousDiagnostics,
        )
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.OBSERVE_ONLY,
            MandatoryRankSurrenderRecoveryPolicy.decide(ScreenWatchdogKind.UNKNOWN).action,
        )
        assertEquals(null, MandatoryRankSurrenderRecoveryPolicy.decide(ScreenWatchdogKind.UNKNOWN).confirmationTarget)

        // Terminal evidence remains higher priority than any overlay.
        confirmationFrames.forEach { (_, image) ->
            assertEquals(ScreenWatchdogKind.WIN, ScreenWatchdog.classifyForSurrenderForTest("胜利 点击继续", boardState, image))
            assertEquals(ScreenWatchdogKind.LOST, ScreenWatchdog.classifyForSurrenderForTest("失败 点击继续", boardState, image))
            assertEquals(ScreenWatchdogKind.RESULT, ScreenWatchdog.classifyForSurrenderForTest("本局结果 对战结束", boardState, image))
        }

        val capability = MandatoryRankSurrenderGuard.begin()
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "surrender.retry.confirm.accept-now",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
                recoveryCapabilityValid = MandatoryRankSurrenderGuard.isRecoveryCapabilityValid(capability),
            ),
            "the production ACCEPT_NOW action must pass only with the live recovery capability",
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "strategy.turn-end",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
            ),
        )
        assertFalse(MatchmakingGuardPolicy.runtimeAllowsInput(true, false, MandatoryRankSurrenderGuard.isPending()))
        assertFalse(MandatoryRankSurrenderGuard.confirmCompleted("UNKNOWN"))
        assertTrue(MandatoryRankSurrenderGuard.isPending())
    }

    @Test
    fun `historical rank four mulligan confirmation screenshot selects only the affirmative control`() {
        val image = readFixture("rank4-current-game-confirmation.png")
        val state = "mode=GAMEPLAY|inWar=true|warPhase=REPLACE_CARD|myTurn=false|myMulliganInput=true"
        val diagnostics = ScreenWatchdog.surrenderConfirmationDiagnosticsForTest(image)
        assertTrue(ScreenWatchdog.hasSurrenderConfirmationPanelForTest(image), diagnostics)
        assertTrue(ScreenWatchdog.hasSurrenderConfirmationVisualForTest(image), diagnostics)
        val kind = ScreenWatchdog.classifyForSurrenderForTest("unreadable background OCR", state, image)
        assertEquals(ScreenWatchdogKind.SURRENDER_CONFIRMATION, kind, diagnostics)
        val decision = MandatoryRankSurrenderRecoveryPolicy.decide(kind)
        assertEquals(MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_CONFIRMATION, decision.action)
        assertEquals(MandatoryRankSurrenderRecoveryPolicy.ConfirmationTarget.ACCEPT_NOW, decision.confirmationTarget)
        assertFalse(ScreenWatchdog.hasSettingsOverlayVisualForTest(image), diagnostics)
    }

    @Test
    fun `exact transition screenshot stays on settings step until modal appears`() {
        val settingsTransition = readFixture("rank7-live-settings-surrender-transition.png")
        val confirmationOverlay = readFixture("rank7-live-surrender-confirmation-mulligan-overlay.png")
        val state = "mode=GAMEPLAY|inWar=true|warPhase=REPLACE_CARD|myTurn=false|myMulliganInput=true"
        val sourceSha256 = "0024C29B7BAA4005FF5373E41A1375EABA51DF03563328604244D68EDC1D0E17"

        assertEquals(1920, settingsTransition.width)
        assertEquals(1080, settingsTransition.height)
        assertTrue(ScreenWatchdog.hasSettingsOverlayVisualForTest(settingsTransition))
        assertFalse(ScreenWatchdog.hasSurrenderConfirmationPanelForTest(settingsTransition))
        val settingsObservation = ScreenWatchdog.inspectForSurrender(
            state = state,
            attempts = 12,
            trigger = "incident-settings-transition-fixture",
            mandatoryRankSurrender = true,
            captureProvider = { settingsTransition },
            ocrProvider = { error("freshly confirmed Settings must use its visual fast path") },
        )
        assertEquals(ScreenWatchdogKind.SETTINGS, settingsObservation.kind)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SURRENDER,
            MandatoryRankSurrenderRecoveryPolicy.decide(settingsObservation.kind).action,
        )

        // This second real frame shows the confirmation modal over the same
        // underlying Settings menu. The modal must win even when OCR says the
        // background labels, and may select only its affirmative choice.
        assertTrue(ScreenWatchdog.hasSurrenderConfirmationPanelForTest(confirmationOverlay))
        val confirmationObservation = ScreenWatchdog.inspectForSurrender(
            state = state,
            attempts = 13,
            trigger = "incident-confirmation-overlay-fixture",
            mandatoryRankSurrender = true,
            captureProvider = { confirmationOverlay },
            ocrProvider = { "设置 认输 选项 退出" },
        )
        assertEquals(ScreenWatchdogKind.SURRENDER_CONFIRMATION, confirmationObservation.kind)
        val decision = MandatoryRankSurrenderRecoveryPolicy.decide(confirmationObservation.kind)
        assertEquals(MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_CONFIRMATION, decision.action)
        assertEquals(MandatoryRankSurrenderRecoveryPolicy.ConfirmationTarget.ACCEPT_NOW, decision.confirmationTarget)
        println("SURRENDER_TRANSITION_FIXTURE sourceSha256=$sourceSha256 settings=${settingsObservation.kind} modal=${confirmationObservation.kind} target=${decision.confirmationTarget}")
    }

    @Test
    fun `surrender accept target remains inside affirmative button and outside continue button`() {
        val gameRect = ScriptStatus.GAME_RECT
        val original = intArrayOf(gameRect.left, gameRect.top, gameRect.right, gameRect.bottom)
        try {
            gameRect.left = 0
            gameRect.top = 0
            gameRect.right = 1920
            gameRect.bottom = 1080

            val target = GameUtil.surrenderConfirmationAcceptRectForTest()
            val targetRect = target.getRelativeRect()
            val center = target.getCenterClickPos()
            // Bounds measured from both checked-in rank-7 surrender modal
            // screenshots at 1920x1080. These are the visible gold-button
            // interiors, excluding the gap between the two choices.
            val acceptButton = Rectangle(730, 624, 215, 56)
            val continueButton = Rectangle(968, 624, 216, 56)
            val clickArea = Rectangle(
                targetRect.x.toInt(),
                targetRect.y.toInt(),
                kotlin.math.ceil(targetRect.width).toInt(),
                kotlin.math.ceil(targetRect.height).toInt(),
            )

            assertTrue(acceptButton.contains(center), "stable center $center must land on 现在认输")
            assertFalse(continueButton.contains(center), "stable center $center must not land on 继续游戏")
            assertTrue(acceptButton.contains(clickArea), "whole click area $clickArea must stay within affirmative target")
            assertFalse(clickArea.intersects(continueButton), "click area $clickArea must not overlap 继续游戏")
        } finally {
            gameRect.left = original[0]
            gameRect.top = original[1]
            gameRect.right = original[2]
            gameRect.bottom = original[3]
        }
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

    @Test
    fun `rank eight unknown OCR with authoritative live turn and real board image opens settings only`() {
        val image = ImageIO.read(
            requireNotNull(javaClass.getResourceAsStream("/club/xiaojiawei/hsscript/status/surrender/rank8-live-gameplay-unknown-ocr.png")),
        )
        val state = "mode=GAMEPLAY|inWar=true|warPhase=GAME_TURN|myTurn=true|warCount=82"

        assertTrue(ScreenWatchdog.hasActiveGameplayVisualForTest(image))
        val screen = ScreenWatchdog.classifyForSurrenderForTest("unreadable board labels", state, image)
        assertEquals(ScreenWatchdogKind.GAMEPLAY, screen)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SETTINGS,
            MandatoryRankSurrenderRecoveryPolicy.decide(screen).action,
        )
        val recoveryCapability = MandatoryRankSurrenderGuard.begin()
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "surrender.retry.open-settings",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
                recoveryCapabilityValid = MandatoryRankSurrenderGuard.isRecoveryCapabilityValid(recoveryCapability),
            ),
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "strategy.turn-end",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
                recoveryCapabilityValid = false,
            ),
        )
        // This visual fallback does not authorize guessing later coordinates:
        // every next step needs a fresh observation of the corresponding screen.
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.OBSERVE_ONLY,
            MandatoryRankSurrenderRecoveryPolicy.decide(ScreenWatchdogKind.UNKNOWN).action,
        )
    }

    @Test
    fun `gameplay visual alone and terminal OCR never authorize the fallback`() {
        val image = ImageIO.read(
            requireNotNull(javaClass.getResourceAsStream("/club/xiaojiawei/hsscript/status/surrender/rank8-live-gameplay-unknown-ocr.png")),
        )
        val active = "mode=GAMEPLAY|inWar=true|warPhase=GAME_TURN|myTurn=true"
        val outsideGame = "mode=HUB|inWar=false|warPhase=FILL_DECK|myTurn=false"

        assertEquals(
            ScreenWatchdogKind.UNKNOWN,
            ScreenWatchdog.classifyForSurrenderForTest("unreadable", outsideGame, image),
        )
        assertEquals(
            ScreenWatchdogKind.WIN,
            ScreenWatchdog.classifyForSurrenderForTest("victory continue", active, image),
        )
        assertEquals(
            ScreenWatchdogKind.UNKNOWN,
            ScreenWatchdog.classifyForSurrenderForTest(
                "unreadable",
                active,
                BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB),
            ),
        )
    }

    @Test
    fun `rank six confirmed mulligan image can only open settings and keeps normal actions blocked`() {
        val images = listOf(
            "rank6-live-mulligan-unknown-ocr.png",
            "rank6-live-mulligan-unknown-ocr-second-frame.png",
        ).map { name ->
            name to ImageIO.read(
                requireNotNull(
                    javaClass.getResourceAsStream("/club/xiaojiawei/hsscript/status/surrender/$name"),
                ),
            )
        }
        val state = "mode=GAMEPLAY|inWar=true|warPhase=REPLACE_CARD|myTurn=false|myMulliganInput=true"

        images.forEach { (name, image) ->
            val diagnostics = ScreenWatchdog.mulliganVisualDiagnosticsForTest(image)
            println("LIVE_MULLIGAN_VISUAL name=$name $diagnostics")
            assertTrue(ScreenWatchdog.hasMulliganVisualForTest(image), "$name: $diagnostics")
            val screen = ScreenWatchdog.classifyForSurrenderForTest("unreadable mulligan labels", state, image)
            assertEquals(ScreenWatchdogKind.MULLIGAN, screen, "$name: $diagnostics")
            assertEquals(
                MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SETTINGS,
                MandatoryRankSurrenderRecoveryPolicy.decide(screen).action,
            )
        }

        val capability = MandatoryRankSurrenderGuard.begin()
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "surrender.retry.open-settings",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
                recoveryCapabilityValid = MandatoryRankSurrenderGuard.isRecoveryCapabilityValid(capability),
            ),
        )
        assertFalse(
            ActionDispatchGate.allowForState(
                action = "strategy.mulligan.replace-card",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
                recoveryCapabilityValid = false,
            ),
        )
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.OBSERVE_ONLY,
            MandatoryRankSurrenderRecoveryPolicy.decide(ScreenWatchdogKind.UNKNOWN).action,
        )
    }

    @Test
    fun `mandatory rank recovery uses mulligan visual when general beta recovery toggle is off`() {
        ConfigUtil.putBoolean(ConfigEnum.BETA_RECOVERY_EXTENSIONS_ENABLED, false, store = false)
        val images = listOf(
            "rank6-live-mulligan-unknown-ocr.png",
            "rank6-live-mulligan-unknown-ocr-second-frame.png",
        ).map { name ->
            name to ImageIO.read(
                requireNotNull(
                    javaClass.getResourceAsStream("/club/xiaojiawei/hsscript/status/surrender/$name"),
                ),
            )
        }
        val state = "mode=GAMEPLAY|inWar=true|warPhase=REPLACE_CARD|myTurn=false|myMulliganInput=true"

        images.forEach { (name, image) ->
            val observation = ScreenWatchdog.inspectForSurrender(
                state = state,
                attempts = 3,
                mandatoryRankSurrender = true,
                captureProvider = { image },
                ocrProvider = { "" },
            )
            assertEquals(ScreenWatchdogKind.MULLIGAN, observation.kind, "$name: ${observation.reason}")
            assertEquals(
                "authoritative-mulligan-input-and-fresh-mulligan-visual",
                observation.reason,
                name,
            )
            assertEquals(
                MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SETTINGS,
                MandatoryRankSurrenderRecoveryPolicy.decide(observation.kind).action,
                name,
            )
        }
    }

    @Test
    fun `mulligan phase without confirmed local input or positive visual remains fail closed`() {
        val image = ImageIO.read(
            requireNotNull(javaClass.getResourceAsStream("/club/xiaojiawei/hsscript/status/surrender/rank6-live-mulligan-unknown-ocr.png")),
        )
        val phaseOnly = "mode=GAMEPLAY|inWar=true|warPhase=REPLACE_CARD|myTurn=false|myMulliganInput=false"
        val confirmedButNoImage = "mode=GAMEPLAY|inWar=true|warPhase=REPLACE_CARD|myTurn=false|myMulliganInput=true"

        assertEquals(
            ScreenWatchdogKind.UNKNOWN,
            ScreenWatchdog.classifyForSurrenderForTest("unreadable", phaseOnly, image),
        )
        assertEquals(
            ScreenWatchdogKind.UNKNOWN,
            ScreenWatchdog.classifyForSurrenderForTest(
                "unreadable",
                confirmedButNoImage,
                BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB),
            ),
        )
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.OBSERVE_ONLY,
            MandatoryRankSurrenderRecoveryPolicy.decide(ScreenWatchdogKind.UNKNOWN).action,
        )
    }

    @Test
    fun `authoritative Mulligan with incident-level hand evidence opens settings without requiring hero visibility`() {
        val image = incidentMulliganFrame()
        val state = "mode=GAMEPLAY|inWar=true|warPhase=REPLACE_CARD|myTurn=false|myMulliganInput=true"
        val diagnostics = ScreenWatchdog.mulliganVisualDiagnosticsForTest(image)

        assertTrue(diagnostics.contains("handVivid=0.2300"), diagnostics)
        assertTrue(diagnostics.contains("heroVivid=0.0000"), diagnostics)
        assertTrue(ScreenWatchdog.hasMulliganVisualForTest(image), diagnostics)
        val screen = ScreenWatchdog.classifyForSurrenderForTest("unreadable", state, image)
        assertEquals(ScreenWatchdogKind.MULLIGAN, screen, diagnostics)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SETTINGS,
            MandatoryRankSurrenderRecoveryPolicy.decide(screen).action,
        )

        val noAuthoritativeInput = state.replace("myMulliganInput=true", "myMulliganInput=false")
        assertEquals(
            ScreenWatchdogKind.UNKNOWN,
            ScreenWatchdog.classifyForSurrenderForTest("unreadable", noAuthoritativeInput, image),
            "a visual signature alone must never authorize a click",
        )
    }

    private fun incidentMulliganFrame(): BufferedImage {
        val image = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
        val gold = Color(200, 150, 50).rgb
        val card = Color(50, 120, 220).rgb
        // These pixel ratios reproduce the 00:17:10 log diagnostics within
        // sampling tolerance: a strong Mulligan banner, 0.23 hand vividness,
        // and no measurable local-hero region due to occlusion.
        fillSampledRatio(image, 0.35, 0.09, 0.66, 0.24, 0.48, gold)
        fillSampledRatio(image, 0.20, 0.30, 0.80, 0.68, 0.23, card)
        return image
    }

    private fun fillSampledRatio(
        image: BufferedImage,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        ratio: Double,
        color: Int,
    ) {
        val x0 = (image.width * left).toInt()
        val x1 = (image.width * right).toInt()
        val y0 = (image.height * top).toInt()
        val y1 = (image.height * bottom).toInt()
        val coordinates = buildList {
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    add(x to y)
                    x += 3
                }
                y += 3
            }
        }
        coordinates.take((coordinates.size * ratio).toInt()).forEach { (x, y) -> image.setRGB(x, y, color) }
    }

    private fun readFixture(name: String): BufferedImage = ImageIO.read(
        requireNotNull(
            javaClass.getResourceAsStream("/club/xiaojiawei/hsscript/status/surrender/$name"),
        ),
    )

    private fun maskMulliganBannerAndAddEmote(source: BufferedImage): BufferedImage {
        val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val graphics: Graphics2D = image.createGraphics()
        try {
            graphics.drawImage(source, 0, 0, null)
            graphics.color = Color(25, 23, 21)
            graphics.fillRect(
                (source.width * 0.35).toInt(),
                (source.height * 0.09).toInt(),
                (source.width * 0.31).toInt(),
                (source.height * 0.15).toInt(),
            )
            graphics.color = Color(118, 111, 103)
            graphics.fillRoundRect(
                (source.width * 0.31).toInt(),
                (source.height * 0.44).toInt(),
                (source.width * 0.38).toInt(),
                (source.height * 0.16).toInt(),
                (source.width * 0.035).toInt(),
                (source.height * 0.04).toInt(),
            )
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun maskConfirmationContinueButton(source: BufferedImage): BufferedImage {
        val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val graphics: Graphics2D = image.createGraphics()
        try {
            graphics.drawImage(source, 0, 0, null)
            graphics.color = Color.BLACK
            graphics.fillRect(
                (source.width * 0.505).toInt(),
                (source.height * 0.576).toInt(),
                (source.width * 0.112).toInt(),
                (source.height * 0.055).toInt(),
            )
        } finally {
            graphics.dispose()
        }
        return image
    }
}
