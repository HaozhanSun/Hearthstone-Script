package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.core.IdleWatchdogRestartSequence
import java.nio.file.Files
import java.nio.file.Path
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BetaCandidateScreenRecoveryIntegrationTest {

    @Test
    fun `centered modal fingerprint ignores animated underlay but changes with modal content`() {
        val first = modalFixture(background = Color(18, 42, 78), modalVariant = 0)
        val animatedUnderlay = modalFixture(background = Color(118, 28, 36), modalVariant = 0)
        val changedModal = modalFixture(background = Color(18, 42, 78), modalVariant = 1)

        assertEquals(
            ScreenStateRecovery.centeredModalPanelFingerprintForTest(first),
            ScreenStateRecovery.centeredModalPanelFingerprintForTest(animatedUnderlay),
            "candidate identity must ignore animation outside the centered modal ROI",
        )
        assertNotEquals(
            ScreenStateRecovery.centeredModalPanelFingerprintForTest(first),
            ScreenStateRecovery.centeredModalPanelFingerprintForTest(changedModal),
            "a changed dialog must start a new candidate episode",
        )
    }

    @Test
    fun `known start game error fixture classifies and reaches restart at exactly two minutes`() {
        val fixture = requireNotNull(
            javaClass.getResourceAsStream("/offline-ocr/screen-recovery/start-game-error-dialog-over-deck-selection.png"),
        ).use(ImageIO::read)
        val classification = UpstreamScreenStateRecovery.classifyFreshScreenForWatchdog(
            "发生错误 由于你的对手无法连接，游戏无法继续。请再试一次。确定",
            fixture,
        )
        assertEquals("START_GAME_ERROR_DIALOG", classification.screen)
        assertTrue(classification.confidence >= BetaCandidateScreenWatchdog.MIN_POSITIVE_CONFIDENCE)

        val watchdog = BetaCandidateScreenWatchdog()
        val first = candidate(
            nowMs = 0L,
            screen = requireNotNull(classification.screen),
            dialogScreen = classification.screen,
            confidence = classification.confidence,
            currentSessionReady = false,
            noActiveMatchmakingEvidence = true,
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(first).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(first.copy(nowMs = BetaCandidateScreenWatchdog.DEFAULT_STABLE_THRESHOLD_MS)).action,
        )
    }

    @Test
    fun `start game error modal restarts only without queue game or pause evidence`() {
        val fixture = requireNotNull(
            javaClass.getResourceAsStream("/offline-ocr/screen-recovery/start-game-error-dialog-over-deck-selection.png"),
        ).use(ImageIO::read)
        val classification = UpstreamScreenStateRecovery.classifyFreshScreenForWatchdog(
            "发生错误 由于你的对手无法连接，游戏无法继续。请再试一次。确定",
            fixture,
        )
        val base = candidate(
            nowMs = 0L,
            screen = requireNotNull(classification.screen),
            dialogScreen = classification.screen,
            confidence = classification.confidence,
            currentSessionReady = false,
            noActiveMatchmakingEvidence = true,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            BetaCandidateScreenWatchdog().run {
                observe(base)
                observe(base.copy(nowMs = 120_000L)).action
            },
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_MATCHMAKING,
            BetaCandidateScreenWatchdog().observe(base.copy(noActiveMatchmakingEvidence = false)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_GAME,
            BetaCandidateScreenWatchdog().observe(base.copy(inWar = true)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_MANUAL_PAUSE,
            BetaCandidateScreenWatchdog().observe(base.copy(paused = true)).action,
        )
    }

    @Test
    fun `candidate restart is wired to the serialized close and canonical starter chain`() {
        val events = mutableListOf<String>()
        IdleWatchdogRestartSequence.dispatch(
            forceCloseClient = { events += "old-game-closed" },
            startStarterChain = { events += "canonical-starter-chain" },
        )
        assertEquals(listOf("old-game-closed", "canonical-starter-chain"), events)

        val moduleRoot = sourceRootFrom(Path.of(""))
        val service = Files.readString(
            moduleRoot.resolve("src/main/java/club/xiaojiawei/hsscript/status/BetaScreenRecoveryService.kt"),
        )
        val restartDecision = service.indexOf("BetaCandidateScreenWatchdog.Action.RESTART")
        val safetyFence = service.indexOf("candidateRestartStillSafe", restartDecision)
        val serializedRestart = service.indexOf("Core.restartFromIdleWatchdog()", safetyFence)
        assertTrue(
            restartDecision >= 0 && safetyFence > restartDecision && serializedRestart > safetyFence,
            "candidate recovery must pass the runtime fence before the serialized full restart",
        )

        val reactorRoot = requireNotNull(moduleRoot.parent)
        assertEquals(moduleRoot, sourceRootFrom(moduleRoot), "module-root cwd must resolve to the app module")
        assertEquals(moduleRoot, sourceRootFrom(reactorRoot), "reactor-root cwd must resolve to the app module")
    }

    @Test
    fun `two minute terminal fallback keeps queue safety and uses the full Beta restart chain`() {
        val watchdog = BetaCandidateScreenWatchdog()
        val result = BetaCandidateScreenWatchdog.Snapshot(
            nowMs = 0L,
            screen = "RANK_PROGRESS_CONTINUATION",
            confidence = 100,
            captureConfirmed = true,
            foregroundConfirmed = true,
            pid = 42L,
            hwnd = "hwnd-42",
            foregroundIdentity = "hwnd-42",
            working = true,
            paused = false,
            inWar = false,
            mulligan = false,
            terminal = true,
            currentSessionReady = true,
            noActiveMatchmakingEvidence = true,
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(result).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(result.copy(nowMs = 120_000L)).action,
        )

        val queued = result.copy(
            screen = "MATCHMAKING",
            terminal = false,
            noActiveMatchmakingEvidence = false,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_MATCHMAKING,
            BetaCandidateScreenWatchdog().observe(queued).action,
        )

        val events = mutableListOf<String>()
        IdleWatchdogRestartSequence.dispatch(
            forceCloseClient = { events += "hearthstone-process-shutdown" },
            startStarterChain = { events += "beta-canonical-startup-and-matchmaking" },
        )
        assertEquals(
            listOf("hearthstone-process-shutdown", "beta-canonical-startup-and-matchmaking"),
            events,
            "the threshold decision must enter the existing serialized full restart path",
        )

        val service = Files.readString(
            sourceRootFrom(Path.of("")).resolve(
                "src/main/java/club/xiaojiawei/hsscript/status/BetaScreenRecoveryService.kt",
            ),
        )
        val safety = service.indexOf("!observation.noActiveMatchmakingEvidence")
        val restartDecision = service.indexOf("BetaCandidateScreenWatchdog.Action.RESTART")
        val restart = service.indexOf("Core.restartFromIdleWatchdog()", restartDecision)
        assertTrue(safety >= 0, "Beta restart safety must reject active matchmaking evidence")
        assertTrue(
            restartDecision >= 0 && restart > restartDecision,
            "Beta restart must dispatch only after the watchdog threshold decision",
        )
    }

    private fun sourceRootFrom(start: Path): Path {
        val marker = Path.of("src/main/java/club/xiaojiawei/hsscript/status/BetaScreenRecoveryService.kt")
        return generateSequence(start.toAbsolutePath().normalize()) { it.parent }
            .flatMap { candidate ->
                sequenceOf(candidate, candidate.resolve("hs-script-app"))
            }
            .distinct()
            .firstOrNull { Files.isRegularFile(it.resolve(marker)) }
            ?: error("Unable to locate BetaScreenRecoveryService.kt from ${start.toAbsolutePath()}")
    }

    @Test
    fun `same modal fingerprint survives a new client identity but remains bounded until healthy progress`() {
        val watchdog = BetaCandidateScreenWatchdog()
        val firstClient = candidate(
            nowMs = 0L,
            screen = "UNKNOWN",
            dialogScreen = null,
            confidence = 0,
            visualModalConfirmed = true,
            visualModalFingerprint = 0xCAFE,
            pid = 100L,
            hwnd = "hwnd-100",
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(firstClient).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(firstClient.copy(nowMs = 120_000L)).action,
        )

        val replacementClient = firstClient.copy(
            nowMs = 120_001L,
            pid = 101L,
            hwnd = "hwnd-101",
            foregroundIdentity = "hwnd-101",
        )
        val replacementBaseline = watchdog.observe(replacementClient)
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, replacementBaseline.action)
        assertEquals(1, replacementBaseline.restartCount, "PID replacement must preserve the semantic restart budget")

        assertEquals(
            BetaCandidateScreenWatchdog.Action.REARM,
            watchdog.observe(replacementClient.copy(nowMs = 240_002L)).action,
            "the two-minute timer must restart from the new client's fresh baseline",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(replacementClient.copy(nowMs = 360_002L)).action,
        )

        val thirdClient = replacementClient.copy(
            nowMs = 360_003L,
            pid = 102L,
            hwnd = "hwnd-102",
            foregroundIdentity = "hwnd-102",
        )
        assertEquals(2, watchdog.observe(thirdClient).restartCount)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.REARM,
            watchdog.observe(thirdClient.copy(nowMs = 480_004L)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.EXHAUSTED,
            watchdog.observe(thirdClient.copy(nowMs = 600_004L)).action,
            "same semantic modal must stop at the restart budget even after PID changes",
        )

        val healthy = thirdClient.copy(
            nowMs = 600_005L,
            screen = "MATCHMAKING",
            visualModalConfirmed = false,
            visualModalFingerprint = null,
            confidence = 100,
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.DEFER_HEALTHY_SCREEN, watchdog.observe(healthy).action)
        val rearmedAfterHealthy = healthy.copy(
            nowMs = 720_005L,
            screen = "UNKNOWN",
            confidence = 0,
            visualModalConfirmed = true,
            visualModalFingerprint = 0xCAFE,
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(rearmedAfterHealthy).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(rearmedAfterHealthy.copy(nowMs = 840_005L)).action,
            "healthy progress must clear the previous exhaustion budget",
        )
    }

    @Test
    fun `alternating blocked modal fingerprints share one restart budget until healthy progress`() {
        val watchdog = BetaCandidateScreenWatchdog()
        val modalA = candidate(
            nowMs = 0L,
            screen = "UNKNOWN",
            dialogScreen = null,
            confidence = 0,
            visualModalConfirmed = true,
            visualModalFingerprint = 0xA0A0,
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(modalA).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(modalA.copy(nowMs = 120_000L)).action,
        )

        val modalB = modalA.copy(nowMs = 120_001L, visualModalFingerprint = 0xB0B0)
        assertEquals(1, watchdog.observe(modalB).restartCount)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.REARM,
            watchdog.observe(modalB.copy(nowMs = 240_002L)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(modalB.copy(nowMs = 360_002L)).action,
        )

        val modalAAgain = modalA.copy(nowMs = 360_003L)
        assertEquals(2, watchdog.observe(modalAAgain).restartCount)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.REARM,
            watchdog.observe(modalAAgain.copy(nowMs = 480_004L)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.EXHAUSTED,
            watchdog.observe(modalAAgain.copy(nowMs = 600_004L)).action,
            "blocked-candidate alternation must not evade the total restart cap",
        )
    }

    private fun candidate(
        nowMs: Long,
        screen: String,
        dialogScreen: String?,
        confidence: Int,
        visualModalConfirmed: Boolean = false,
        visualModalFingerprint: Long? = null,
        pid: Long = 42L,
        hwnd: String = "hwnd-1",
        currentSessionReady: Boolean = true,
        noActiveMatchmakingEvidence: Boolean = true,
    ) = BetaCandidateScreenWatchdog.Snapshot(
        nowMs = nowMs,
        screen = screen,
        dialogScreen = dialogScreen,
        visualModalConfirmed = visualModalConfirmed,
        visualModalFingerprint = visualModalFingerprint,
        confidence = confidence,
        captureConfirmed = true,
        foregroundConfirmed = true,
        pid = pid,
        hwnd = hwnd,
        foregroundIdentity = hwnd,
        working = true,
        paused = false,
        inWar = false,
        mulligan = false,
        terminal = false,
        currentSessionReady = currentSessionReady,
        noActiveMatchmakingEvidence = noActiveMatchmakingEvidence,
    )

    private fun modalFixture(background: Color, modalVariant: Int): BufferedImage =
        BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB).also { image ->
            image.createGraphics().also { graphics ->
                graphics.color = background
                graphics.fillRect(0, 0, image.width, image.height)
                val left = (image.width * 0.32).toInt()
                val top = (image.height * 0.39).toInt()
                val width = (image.width * 0.36).toInt()
                val height = (image.height * 0.23).toInt()
                graphics.color = Color(42, 42, 42)
                graphics.fillRect(left, top, width, height)
                for (column in 0 until 9) {
                    val stripeColor = if ((column + modalVariant) % 2 == 0) Color(224, 224, 224) else Color(62, 62, 62)
                    graphics.color = stripeColor
                    graphics.fillRect(left + 45 + column * 70, top + 55, 35, height - 110)
                }
                graphics.dispose()
            }
        }
}
