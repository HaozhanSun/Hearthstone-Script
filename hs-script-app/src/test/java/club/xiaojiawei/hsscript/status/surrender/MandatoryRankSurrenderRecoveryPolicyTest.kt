package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.status.ScreenWatchdog
import club.xiaojiawei.hsscript.status.ScreenWatchdogKind
import club.xiaojiawei.hsscript.status.ActionDispatchGate
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
    fun `settings overlay at surrender target permits only the settings surrender step`() {
        val screen = ScreenWatchdog.classifyForTest("设置 选项 投降 退出游戏")

        assertEquals(ScreenWatchdogKind.SETTINGS, screen)
        assertEquals(
            MandatoryRankSurrenderRecoveryPolicy.Action.CLICK_SURRENDER,
            MandatoryRankSurrenderRecoveryPolicy.decide(screen).action,
        )
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
            ocrProvider = { "起始手牌 保留或替换卡牌" },
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

        // Terminal evidence remains higher priority than any overlay.
        confirmationFrames.forEach { (_, image) ->
            assertEquals(ScreenWatchdogKind.WIN, ScreenWatchdog.classifyForSurrenderForTest("胜利 点击继续", boardState, image))
            assertEquals(ScreenWatchdogKind.LOST, ScreenWatchdog.classifyForSurrenderForTest("失败 点击继续", boardState, image))
            assertEquals(ScreenWatchdogKind.RESULT, ScreenWatchdog.classifyForSurrenderForTest("本局结果 对战结束", boardState, image))
        }

        val capability = MandatoryRankSurrenderGuard.begin()
        assertTrue(
            ActionDispatchGate.allowForState(
                action = "surrender.retry.confirm",
                paused = false,
                working = true,
                mandatoryRankSurrenderPending = true,
                recoveryCapabilityValid = MandatoryRankSurrenderGuard.isRecoveryCapabilityValid(capability),
            ),
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

    private fun readFixture(name: String): BufferedImage = ImageIO.read(
        requireNotNull(
            javaClass.getResourceAsStream("/club/xiaojiawei/hsscript/status/surrender/$name"),
        ),
    )

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
