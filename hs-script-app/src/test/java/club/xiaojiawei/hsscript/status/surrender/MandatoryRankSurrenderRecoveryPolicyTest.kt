package club.xiaojiawei.hsscript.status.surrender

import club.xiaojiawei.hsscript.status.ScreenWatchdog
import club.xiaojiawei.hsscript.status.ScreenWatchdogKind
import club.xiaojiawei.hsscript.status.ActionDispatchGate
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
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
        // Settings and the confirmation dialog still need their own OCR state.
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
}
