package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.core.IdleWatchdogRestartSequence
import java.nio.file.Files
import java.nio.file.Path
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BetaCandidateScreenRecoveryIntegrationTest {

    @Test
    fun `empty-session Battle net handoff enters the bounded candidate chain without OCR`() {
        val fixture = requireNotNull(
            javaClass.getResourceAsStream("/offline-ocr/screen-recovery/battlenet-launcher-startup-live-20261031-redacted.png"),
        ).use(ImageIO::read)
        assertTrue(BattleNetLauncherVisualPolicy.isVisible(fixture))

        val classification = requireNotNull(
            ScreenStateRecovery.classifyBetaCandidateVisualForTest(fixture, currentSessionReady = false),
        )
        assertEquals("STARTUP_LAUNCHER", classification.screen)
        assertEquals(96, classification.confidence)
        assertEquals("battlenet-launcher-empty-session-visual", classification.evidence)
        assertNull(
            ScreenStateRecovery.classifyBetaCandidateVisualForTest(fixture, currentSessionReady = true),
            "the launcher visual is a pre-session handoff proof only",
        )

        val observation = ScreenStateRecovery.FreshScreenObservation(
            screen = requireNotNull(classification.screen),
            mode = classification.mode,
            confidence = classification.confidence,
            evidence = classification.evidence,
            pid = 33888L,
            hwnd = "native@0x13a07f4",
            screenshot = "battlenet-launcher-startup-live-20261031-redacted.png",
            perceptualFingerprint = 0x015028L,
            currentSessionReady = false,
            noActiveMatchmakingEvidence = true,
            hubModeChooserVisible = false,
            modalVisible = false,
        )
        val watchdog = BetaCandidateScreenWatchdog()
        val first = BetaScreenRecoveryService.candidateSnapshotForTest(0L, observation)
            .copy(working = true, paused = false)
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(first).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT,
            watchdog.observe(first.copy(nowMs = 119_999L)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(first.copy(nowMs = 120_000L)).action,
            "the exact launcher handoff can reach the existing serialized restart chain only after two minutes",
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_MATCHMAKING,
            BetaCandidateScreenWatchdog().observe(first.copy(noActiveMatchmakingEvidence = false)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_MANUAL_PAUSE,
            BetaCandidateScreenWatchdog().observe(first.copy(paused = true)).action,
        )

        val recoverySource = Files.readString(sourceRootFrom(Path.of("")).resolve(
            "src/main/java/club/xiaojiawei/hsscript/status/ScreenStateRecovery.kt",
        ))
        assertTrue(
            recoverySource.contains("battlenet-launcher-empty-session-visual") &&
                recoverySource.contains("visual.battleNetLauncherVisual"),
            "the production candidate callsite must classify the launcher structurally before OCR",
        )
        val serviceSource = Files.readString(sourceRootFrom(Path.of("")).resolve(
            "src/main/java/club/xiaojiawei/hsscript/status/BetaScreenRecoveryService.kt",
        ))
        assertTrue(
            serviceSource.contains("observation.currentSessionReady &&") &&
                serviceSource.contains("hasAuthorizedTerminalVisualProof(observation)"),
            "the final restart fence must ignore stale terminal latches for an empty-session launcher",
        )
    }

    @Test
    fun `current session or active game skips candidate OCR while queue visual remains protected`() {
        assertTrue(ScreenStateRecovery.betaCandidateOcrShouldSkipForTest(
            currentSessionReady = true,
            inWar = false,
        ))
        assertTrue(ScreenStateRecovery.betaCandidateOcrShouldSkipForTest(
            currentSessionReady = false,
            inWar = true,
        ))
        assertFalse(ScreenStateRecovery.betaCandidateOcrShouldSkipForTest(
            currentSessionReady = false,
            inWar = false,
        ))

        val source = Files.readString(sourceRootFrom(Path.of("")).resolve(
            "src/main/java/club/xiaojiawei/hsscript/status/ScreenStateRecovery.kt",
        ))
        val skip = source.indexOf("betaCandidateOcrShouldSkip(currentSessionReady, WarEx.inWar)")
        val boundedOcr = source.indexOf("runCandidateOcrBounded(capture)")
        assertTrue(skip >= 0 && boundedOcr > skip,
            "current-session/active-game short-circuit must precede candidate OCR")
        assertTrue(source.contains("screen = if (queueVisible) \"MATCHMAKING\" else \"GAMEPLAY\""),
            "the short-circuit must retain a visual queue classification when present")

        val activeGame = ScreenStateRecovery.FreshScreenObservation(
            screen = "GAMEPLAY",
            mode = "TOURNAMENT",
            confidence = 98,
            evidence = "current-session-or-active-game-no-ocr",
            pid = 20_632L,
            hwnd = "native@0x923029e",
            screenshot = null,
            perceptualFingerprint = 1L,
            currentSessionReady = true,
            noActiveMatchmakingEvidence = true,
            hubModeChooserVisible = false,
            modalVisible = false,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_GAME,
            BetaCandidateScreenWatchdog().observe(
                BetaScreenRecoveryService.candidateSnapshotForTest(0L, activeGame)
                    .copy(working = true, paused = false, inWar = true),
            ).action,
            "current active game must defer before any OCR-derived recovery action",
        )
        val queue = activeGame.copy(
            screen = "MATCHMAKING",
            evidence = "matchmaking-search-visual",
            noActiveMatchmakingEvidence = false,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_GAME,
            BetaCandidateScreenWatchdog().observe(
                BetaScreenRecoveryService.candidateSnapshotForTest(0L, queue)
                    .copy(working = true, paused = false, inWar = true),
            ).action,
            "a current game fence remains stronger than queue-screen recovery",
        )
    }

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
    fun `live Platinum 9 reward classification survives stale fill-deck session and reaches bounded restart`() {
        val fixture = requireNotNull(
            javaClass.getResourceAsStream("/offline-ocr/screen-recovery/beta-live-platinum9-rank-reward-20261121.png"),
        ).use(ImageIO::read)
        assertEquals("RANK_PROGRESS_CONTINUATION", UpstreamScreenStateRecovery.classifyImageForResultFixture(fixture))

        // This is the production handoff shape from the incident: stale
        // lifecycle phase FILL_DECK/current-session remains true while the
        // current Power.log has no new bytes. Drive the actual classifier
        // result through BetaScreenRecoveryService.candidateSnapshotForTest;
        // only authoritative terminal proof is supplied by the test seam.
        val observation = ScreenStateRecovery.FreshScreenObservation(
            screen = "RANK_PROGRESS_CONTINUATION",
            mode = "GAMEPLAY",
            confidence = 93,
            evidence = "post-result-rank-progress-visual",
            pid = 29_000L,
            hwnd = "0x27010",
            screenshot = "beta-live-platinum9-rank-reward-20261121.png",
            perceptualFingerprint = 0x39690b06L,
            currentSessionReady = true,
            noActiveMatchmakingEvidence = true,
            hubModeChooserVisible = false,
            modalVisible = true,
        )
        val reward = BetaScreenRecoveryService.candidateSnapshotForTest(
            now = 0L,
            observation = observation,
        ).copy(working = true, paused = false)
        assertEquals("RANK_PROGRESS_CONTINUATION", reward.screen)
        assertTrue(reward.currentSessionReady)
        assertTrue(reward.terminal)
        assertTrue(reward.noActiveMatchmakingEvidence)
        assertFalse(
            BetaScreenRecoveryService.candidateSnapshotForTest(
                now = 0L,
                observation = observation.copy(currentSessionReady = false),
            ).terminal,
            "rank visual alone cannot mint terminal proof without current-session evidence",
        )
        assertFalse(
            BetaScreenRecoveryService.candidateSnapshotForTest(
                now = 0L,
                observation = observation.copy(noActiveMatchmakingEvidence = false),
            ).terminal,
            "rank visual cannot mint terminal proof while queue evidence is active",
        )
        val watchdog = BetaCandidateScreenWatchdog()
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(reward).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.WAIT,
            watchdog.observe(reward.copy(nowMs = 119_999L)).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(reward.copy(nowMs = 120_000L)).action)

        assertEquals(BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_GAME,
            BetaCandidateScreenWatchdog().observe(reward.copy(inWar = true)).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_MATCHMAKING,
            BetaCandidateScreenWatchdog().observe(reward.copy(noActiveMatchmakingEvidence = false)).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.DEFER_MANUAL_PAUSE,
            BetaCandidateScreenWatchdog().observe(reward.copy(paused = true)).action)

        assertEquals(
            PostResultRankProgressPolicy.Action.CONTINUE,
            PostResultRankProgressPolicy.decide(
                rankProgressVisible = true,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
                rankProgressInputAttempts = 0,
            ),
            "ordinary rank-result recovery still owns the first Continue action before the two-minute fallback",
        )
        val recoverySource = Files.readString(sourceRootFrom(Path.of("")).resolve(
            "src/main/java/club/xiaojiawei/hsscript/status/ScreenStateRecovery.kt",
        ))
        val rankBranch = recoverySource.indexOf("ScreenKind.RANK_PROGRESS_CONTINUATION ->")
        assertTrue(rankBranch >= 0)
        assertTrue(
            recoverySource.indexOf("GameUtil.dismissStaleGameEndScreen(", rankBranch) > rankBranch,
            "the normal rank branch must dispatch bounded Continue cleanup before watchdog restart is considered",
        )
    }

    @Test
    fun `OCR exception is contained as unknown and cannot authorize a candidate restart`() {
        assertEquals(12_000L, ScreenStateRecovery.candidateOcrTimeoutMsForTest())
        val frame = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
        val classification = UpstreamScreenStateRecovery.classifyFreshScreenForWatchdogSafely(
            image = frame,
            ocrText = { error("simulated OCR provider failure") },
        )
        assertNull(classification.screen)
        assertEquals(0, classification.confidence)
        assertEquals("ocr-exception-contained", classification.evidence)

        val watchdog = BetaCandidateScreenWatchdog()
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN,
            watchdog.observe(
                BetaCandidateScreenWatchdog.Snapshot(
                    nowMs = 120_000L,
                    screen = classification.screen,
                    confidence = classification.confidence,
                    captureConfirmed = true,
                    foregroundConfirmed = true,
                    pid = 42L,
                    hwnd = "hwnd-42",
                    foregroundIdentity = "hwnd-42",
                    working = true,
                    paused = false,
                    inWar = false,
                    mulligan = false,
                    terminal = false,
                    currentSessionReady = false,
                    noActiveMatchmakingEvidence = true,
                ),
            ).action,
        )
    }

    @Test
    fun `bounded candidate OCR timeout keeps later monitor probes responsive`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val timedOut = ScreenStateRecovery.runCandidateOcrBoundedForTest(timeoutMs = 50L, probe = {
            started.countDown()
            // Native OCR may ignore interruption; keep the surrogate in-flight
            // until the explicit release so the single-flight guard is tested.
            var interrupted = false
            while (true) {
                try {
                    if (release.await(25, TimeUnit.MILLISECONDS)) break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        })
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertEquals("TIMEOUT", timedOut)

        val inFlight = ScreenStateRecovery.runCandidateOcrBoundedForTest(timeoutMs = 50L, probe = { })
        assertEquals("IN_FLIGHT", inFlight)

        release.countDown()
        // The daemon probe releases the single-flight gate once native OCR
        // returns; a completed probe is accepted on the next monitor tick.
        repeat(20) {
            if (ScreenStateRecovery.runCandidateOcrBoundedForTest(timeoutMs = 50L, probe = { }) == "COMPLETED") return
            Thread.sleep(10L)
        }
        error("timed-out candidate OCR worker did not release its single-flight gate")
    }

    @Test
    fun `candidate callsite marks a null fresh capture as missing evidence rather than active queue`() {
        val missing = BetaScreenRecoveryService.candidateSnapshotForTest(
            now = 60_000L,
            observation = null,
        )
        assertTrue(missing.observationMissing)
        assertFalse(missing.noActiveMatchmakingEvidence)
        val decision = BetaCandidateScreenWatchdog().observe(missing.copy(working = true, paused = false))
        assertEquals(BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN, decision.action)
        assertEquals("observation-missing", decision.reason)

        val currentQueueFrame = ScreenStateRecovery.FreshScreenObservation(
            screen = "DECK_SELECTION",
            mode = "TOURNAMENT",
            confidence = 94,
            evidence = "deck-selection-start-control-visual",
            pid = 31468L,
            hwnd = "0x27010",
            screenshot = null,
            perceptualFingerprint = java.lang.Long.parseUnsignedLong("86ca24794d2550c9", 16),
            currentSessionReady = false,
            noActiveMatchmakingEvidence = false,
            hubModeChooserVisible = false,
            modalVisible = false,
        )
        val queueDecision = BetaCandidateScreenWatchdog().observe(
            BetaScreenRecoveryService.candidateSnapshotForTest(60_000L, currentQueueFrame)
                .copy(working = true, paused = false),
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_MATCHMAKING, queueDecision.action)
        assertEquals("active-matchmaking-evidence", queueDecision.reason)

        val preSessionDeckFrame = currentQueueFrame.copy(noActiveMatchmakingEvidence = true)
        val episode = BetaCandidateScreenWatchdog()
        val initial = BetaScreenRecoveryService.candidateSnapshotForTest(0L, preSessionDeckFrame)
            .copy(working = true, paused = false)
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, episode.observe(initial).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN,
            episode.observe(BetaScreenRecoveryService.candidateSnapshotForTest(60_000L, null)
                .copy(working = true, paused = false)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT,
            episode.observe(BetaScreenRecoveryService.candidateSnapshotForTest(90_000L, preSessionDeckFrame)
                .copy(working = true, paused = false)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            episode.observe(BetaScreenRecoveryService.candidateSnapshotForTest(120_000L, preSessionDeckFrame)
                .copy(working = true, paused = false)).action,
            "the positive pre-session DECK_SELECTION episode survives one bounded missing sample",
        )
        val arbitraryUnknown = preSessionDeckFrame.copy(screen = "UNKNOWN", confidence = 0, evidence = null)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN,
            BetaCandidateScreenWatchdog().observe(
                BetaScreenRecoveryService.candidateSnapshotForTest(0L, arbitraryUnknown)
                    .copy(working = true, paused = false),
            ).action,
            "the production candidate snapshot keeps arbitrary UNKNOWN fail-closed",
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
    fun `empty-session stale input intent does not defer exact deck recovery while current queue evidence still fences`() {
        assertFalse(
            ScreenStateRecovery.hasCurrentCandidateMatchmakingEvidence(
                currentSessionReady = false,
                queueVisible = false,
                recentActiveScreen = false,
                shortEntryGuard = false,
                recentInputIntent = true,
            ),
            "a historical 15-minute input intent is not active queue proof when the current Power.log is empty",
        )
        assertTrue(
            ScreenStateRecovery.hasCurrentCandidateMatchmakingEvidence(
                currentSessionReady = false,
                queueVisible = true,
                recentActiveScreen = false,
                shortEntryGuard = false,
                recentInputIntent = true,
            ),
            "the current SEARCHING visual remains authoritative despite an empty Power.log",
        )
        assertTrue(
            ScreenStateRecovery.hasCurrentCandidateMatchmakingEvidence(
                currentSessionReady = false,
                queueVisible = false,
                recentActiveScreen = false,
                shortEntryGuard = true,
                recentInputIntent = true,
            ),
            "the short queue-entry race guard remains authoritative without a current session",
        )

        val watchdog = BetaCandidateScreenWatchdog()
        val candidate = BetaCandidateScreenWatchdog.Snapshot(
            nowMs = 0L,
            screen = "DECK_SELECTION",
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
            terminal = false,
            currentSessionReady = false,
            noActiveMatchmakingEvidence = true,
            allowHealthyScreenRecovery = true,
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, watchdog.observe(candidate).action)
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(candidate.copy(nowMs = BetaCandidateScreenWatchdog.DEFAULT_STABLE_THRESHOLD_MS)).action,
            "the observable no-session deck flow reaches the existing serialized restart chain after two minutes",
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
        assertTrue(
            service.contains("BuildChannel.isBetaDerived(BuildInfo.RELEASE_CHANNEL)"),
            "candidate restart monitoring must be Beta-channel scoped",
        )
        val recovery = Files.readString(
            sourceRootFrom(Path.of("")).resolve(
                "src/main/java/club/xiaojiawei/hsscript/status/ScreenStateRecovery.kt",
            ),
        )
        assertTrue(
            recovery.contains("stuckForMs = BetaCandidateScreenWatchdog.DEFAULT_STABLE_THRESHOLD_MS"),
            "the always-on candidate capture must use pre-session idle authority while Power.log is empty",
        )
        assertTrue(
            service.contains("observation.currentSessionReady"),
            "candidate restart must reject an active current session",
        )
        assertTrue(
            service.contains("pidMatches = observation != null && currentPid == observation.pid") &&
                service.contains("hwndMatches = observation != null && currentHwnd?.toString() == observation.hwnd") &&
                service.contains("currentWindowVerified = observation != null") &&
                service.contains("BetaCandidateRestartFencePolicy.rejectionReason"),
            "candidate restart must bind the exact current Beta Hearthstone PID/HWND before close",
        )
        val restartDecision = service.indexOf("BetaCandidateScreenWatchdog.Action.RESTART")
        val restart = service.indexOf("Core.restartFromIdleWatchdog()", restartDecision)
        assertTrue(
            service.contains("noActiveMatchmakingEvidence = observation?.noActiveMatchmakingEvidence == true") &&
                Files.readString(sourceRootFrom(Path.of("")).resolve("src/main/java/club/xiaojiawei/hsscript/status/BetaCandidateRestartFencePolicy.kt"))
                    .contains("active-matchmaking-evidence"),
            "Beta restart safety must reject active matchmaking evidence",
        )
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
