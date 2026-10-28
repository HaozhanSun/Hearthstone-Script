package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicInteger

class ScreenRecoveryCaptureAuthorityTest {
    private val game = CapturedWindowIdentity(rootWindow = 0x22408b4, processId = 93628)
    private val codex = CapturedWindowIdentity(rootWindow = 0x1b40906, processId = 11111)
    private val client = Rectangle(0, 0, 1920, 1080)

    @Test
    fun `pre-session capture rejection can happen before foreground recovery`() {
        assertEquals(
            "current-game-session-not-ready",
            ScreenRecoveryCapturePurposePolicy.preflightRejectionReason(
                currentSessionReady = false,
                preSessionQueueModalAuthorized = false,
                preSessionMatchmakingAuthorized = false,
            ),
            "an empty or unbound Power log with no bounded queue intent must skip before foregrounding",
        )
        assertNull(
            ScreenRecoveryCapturePurposePolicy.preflightRejectionReason(
                currentSessionReady = true,
                preSessionQueueModalAuthorized = false,
                preSessionMatchmakingAuthorized = false,
            ),
        )
        assertNull(
            ScreenRecoveryCapturePurposePolicy.preflightRejectionReason(
                currentSessionReady = false,
                preSessionQueueModalAuthorized = false,
                preSessionMatchmakingAuthorized = true,
            ),
            "existing narrowly authorized matchmaking capture remains available",
        )
        assertEquals(
            "current-game-session-not-ready",
            ScreenRecoveryCapturePurposePolicy.preflightRejectionReason(
                currentSessionReady = false,
                preSessionQueueModalAuthorized = false,
                preSessionMatchmakingAuthorized = false,
                preSessionStaleClientProbeAuthorized = false,
            ),
        )
        assertNull(
            ScreenRecoveryCapturePurposePolicy.preflightRejectionReason(
                currentSessionReady = false,
                preSessionQueueModalAuthorized = false,
                preSessionMatchmakingAuthorized = false,
                preSessionStaleClientProbeAuthorized = true,
            ),
            "the separately bounded stale-client probe may classify a visible client",
        )
        assertNull(
            ScreenRecoveryCapturePurposePolicy.preflightRejectionReason(
                currentSessionReady = false,
                preSessionQueueModalAuthorized = false,
                preSessionMatchmakingAuthorized = false,
                preSessionStartupClientProbeAuthorized = true,
            ),
            "an explicitly requested, exact-window startup probe may classify the pre-game client",
        )
        assertEquals(
            "current-game-session-not-ready",
            ScreenRecoveryCapturePurposePolicy.preflightRejectionReason(
                currentSessionReady = false,
                preSessionQueueModalAuthorized = false,
                preSessionMatchmakingAuthorized = false,
                preSessionStartupClientProbeAuthorized = false,
            ),
            "the startup exception is not implied by an empty or unbound log alone",
        )
    }

    @Test
    fun `only same-frame trusted HUB chooser visual and text grants the exact entry action`() {
        fun allows(
            purpose: ScreenRecoveryCapturePurpose = ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE,
            currentSessionReady: Boolean = false,
            screenKind: String? = "TOURNAMENT",
            confidence: Int = 96,
            evidence: String? = "hub-mode-chooser-visual-and-text",
            currentMode: String? = "HUB",
            modalVisible: Boolean = false,
            activeGame: Boolean = false,
            activeMatchmaking: Boolean = false,
            trustedCurrentWindow: Boolean = true,
        ) = StartupHubModeChooserPolicy.canAuthorizeTraditionalBattleSelection(
            purpose, currentSessionReady, screenKind, confidence, evidence, currentMode,
            modalVisible, activeGame, activeMatchmaking, trustedCurrentWindow,
        )

        assertTrue(allows())
        assertFalse(allows(evidence = "tournament-text"), "OCR label alone is not same-frame visual proof")
        assertFalse(allows(screenKind = "MATCHMAKING"))
        assertFalse(allows(screenKind = "UNKNOWN"))
        assertFalse(allows(purpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY))
        assertFalse(allows(currentSessionReady = true))
        assertFalse(allows(currentMode = "TOURNAMENT"))
        assertFalse(allows(modalVisible = true))
        assertFalse(allows(activeGame = true))
        assertFalse(allows(activeMatchmaking = true))
        assertFalse(allows(trustedCurrentWindow = false))
        assertFalse(allows(confidence = 89))
    }

    @Test
    fun `exact foreground HWND cannot authorize desktop pixels owned by Codex`() {
        val evidence = evidence(owners = List(63) { codex })
        assertEquals("client-center-not-owned-by-target-window", ScreenRecoveryCaptureAuthority.failureReason(evidence))
        assertFalse(ScreenRecoveryCaptureAuthority.isAuthorized(evidence))

        val riskyRobotActions = AtomicInteger()
        val dispatched = ScreenRecoveryAuthorityGate.dispatchIfAuthorized(
            recoveryEvidence(capturedPixelsVerified = ScreenRecoveryCaptureAuthority.isAuthorized(evidence)),
        ) {
            riskyRobotActions.incrementAndGet()
            true
        }
        assertFalse(dispatched)
        assertEquals(0, riskyRobotActions.get(), "unattributed capture must never reach recovery input")
    }

    @Test
    fun `foreign window covering more than twenty percent is rejected even when center is game-owned`() {
        val owners = MutableList<CapturedWindowIdentity?>(63) { game }
        owners[0] = codex
        owners[1] = codex
        owners[2] = codex
        owners[3] = codex
        owners[4] = codex
        owners[5] = codex
        owners[6] = codex
        owners[7] = codex
        owners[8] = codex
        owners[9] = codex
        owners[10] = codex
        owners[11] = codex
        owners[12] = codex

        val evidence = evidence(owners = owners)
        assertEquals("target-window-not-visible-before-capture", ScreenRecoveryCaptureAuthority.failureReason(evidence))
        assertFalse(ScreenRecoveryCaptureAuthority.isAuthorized(evidence))
    }

    @Test
    fun `visible game client with matching HWND dimensions and owner samples is authorized`() {
        val evidence = evidence(owners = List(63) { game })
        assertNull(ScreenRecoveryCaptureAuthority.failureReason(evidence))
        assertTrue(ScreenRecoveryCaptureAuthority.isAuthorized(evidence))

        val actions = AtomicInteger()
        assertTrue(
            ScreenRecoveryAuthorityGate.dispatchIfAuthorized(recoveryEvidence(capturedPixelsVerified = true)) {
                actions.incrementAndGet()
                true
            },
        )
        assertEquals(1, actions.get())
    }

    @Test
    fun `no game process or target window fails closed without authorizing input`() {
        val evidence = evidence(
            processAlive = false,
            target = null,
            foregroundBefore = null,
            foregroundAfter = null,
            owners = emptyList(),
        )
        assertEquals("target-window-missing", ScreenRecoveryCaptureAuthority.failureReason(evidence))
        assertFalse(ScreenRecoveryCaptureAuthority.isAuthorized(evidence))

        val actions = AtomicInteger()
        assertFalse(ScreenRecoveryAuthorityGate.dispatchIfAuthorized(recoveryEvidence(false)) {
            actions.incrementAndGet()
            true
        })
        assertEquals(0, actions.get())
        assertTrue(ScreenRecoveryCaptureAuthority.samplePoints(Rectangle(0, 0, 300, 200)).isEmpty())
    }

    @Test
    fun `full desktop capture bounds cannot substitute for the exact current client bounds`() {
        val evidence = evidence(
            owners = List(63) { game },
            captureBounds = Rectangle(0, 0, 3840, 2160),
        )
        assertEquals("capture-bounds-do-not-match-current-client", ScreenRecoveryCaptureAuthority.failureReason(evidence))
    }

    @Test
    fun `scaled or stale image dimensions are rejected even when foreground and hit tests match`() {
        val evidence = evidence(owners = List(63) { game }, imageWidth = 1280, imageHeight = 720)
        assertEquals("captured-image-dimensions-do-not-match-client", ScreenRecoveryCaptureAuthority.failureReason(evidence))
    }

    @Test
    fun `startup probe waits for verified current window and nonempty bound current-session Power log`() {
        assertFalse(
            CurrentGameScreenReadinessPolicy.isReady(
                gameWindowVerified = true,
                attachedPowerLogPath = "D:/Logs/session/Power.log",
                currentSessionPowerLogPath = "D:/Logs/session/Power.log",
                powerLogLength = 0,
            ),
        )
        assertFalse(
            CurrentGameScreenReadinessPolicy.isReady(
                gameWindowVerified = true,
                attachedPowerLogPath = "D:/Logs/old/Power.log",
                currentSessionPowerLogPath = "D:/Logs/session/Power.log",
                powerLogLength = 100,
            ),
        )
        assertFalse(
            CurrentGameScreenReadinessPolicy.isReady(
                gameWindowVerified = false,
                attachedPowerLogPath = "D:/Logs/session/Power.log",
                currentSessionPowerLogPath = "D:/Logs/session/Power.log",
                powerLogLength = 100,
            ),
        )
        assertTrue(
            CurrentGameScreenReadinessPolicy.isReady(
                gameWindowVerified = true,
                attachedPowerLogPath = "D:/Logs/session/Power.log",
                currentSessionPowerLogPath = "d:\\logs\\session\\Power.log",
                powerLogLength = 100,
            ),
        )
    }

    @Test
    fun `only the exact matchmaking modal purpose may capture before Power log is bound`() {
        assertFalse(
            CurrentGameScreenReadinessPolicy.isReady(
                gameWindowVerified = true,
                attachedPowerLogPath = null,
                currentSessionPowerLogPath = "D:/Logs/session/Power.log",
                powerLogLength = 0L,
            ),
            "a missing/late listener binding does not create current-session readiness",
        )
        assertFalse(
            CurrentGameScreenReadinessPolicy.isReady(
                gameWindowVerified = true,
                attachedPowerLogPath = "D:/Logs/session/Power.log",
                currentSessionPowerLogPath = "D:/Logs/session/Power.log",
                powerLogLength = 0L,
            ),
            "a file that exists but is still zero bytes remains unbound for ordinary recovery",
        )
        val missingPowerLog = evidence(currentSessionReady = false)
        assertEquals(
            "current-game-session-not-ready",
            ScreenRecoveryCaptureAuthority.failureReason(missingPowerLog),
        )

        val exactModal = evidence(
            currentSessionReady = false,
            purpose = ScreenRecoveryCapturePurpose.MATCHMAKING_ERROR_DIALOG,
            preSessionQueueModalAuthorized = true,
        )
        assertTrue(ScreenRecoveryCaptureAuthority.isAuthorized(exactModal))

        assertFalse(
            ScreenRecoveryCaptureAuthority.isAuthorized(
                evidence(
                    currentSessionReady = false,
                    purpose = ScreenRecoveryCapturePurpose.MATCHMAKING_ERROR_DIALOG,
                ),
            ),
            "the purpose label alone cannot grant the pre-session exception",
        )
        assertEquals(
            "current-game-session-not-ready",
            ScreenRecoveryCaptureAuthority.failureReason(
                evidence(
                    currentSessionReady = false,
                    purpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY,
                    preSessionQueueModalAuthorized = true,
                ),
            ),
            "even a positive queue context cannot remove the log gate from ordinary/game-state capture",
        )

        assertTrue(
            ScreenRecoveryCapturePurposePolicy.allowsPreSessionMatchmaking(
                purpose = ScreenRecoveryCapturePurpose.MATCHMAKING_SEARCH,
                tournamentMode = true,
                activeGame = false,
                mulligan = false,
                terminal = false,
                recentInputIntent = true,
            ),
        )
        assertFalse(
            ScreenRecoveryCapturePurposePolicy.allowsPreSessionMatchmaking(
                purpose = ScreenRecoveryCapturePurpose.MATCHMAKING_ERROR_DIALOG,
                tournamentMode = true,
                activeGame = false,
                mulligan = false,
                terminal = false,
                recentInputIntent = true,
            ),
            "the matchmaking intent is only for normal screen observation, not dialog input",
        )
        assertFalse(
            ScreenRecoveryCapturePurposePolicy.allowsPreSessionMatchmaking(
                purpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY,
                tournamentMode = true,
                activeGame = false,
                mulligan = false,
                terminal = false,
                recentInputIntent = true,
            ),
            "generic recovery must not inherit queue-start authority",
        )
        assertFalse(
            ScreenRecoveryCapturePurposePolicy.allowsPreSessionMatchmaking(
                purpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY,
                tournamentMode = true,
                activeGame = false,
                mulligan = false,
                terminal = false,
                recentInputIntent = false,
            ),
        )
        assertTrue(
            ScreenRecoveryCaptureAuthority.isAuthorized(
                evidence(
                    currentSessionReady = false,
                    preSessionMatchmakingAuthorized = true,
                ),
            ),
            "a bounded queue-start intent authorizes capture while the new Power.log is empty",
        )

        val occludedModal = evidence(
            currentSessionReady = false,
            purpose = ScreenRecoveryCapturePurpose.MATCHMAKING_ERROR_DIALOG,
            preSessionQueueModalAuthorized = true,
            owners = List(63) { codex },
        )
        assertFalse(
            ScreenRecoveryCaptureAuthority.isAuthorized(occludedModal),
            "the purpose exception must not whitelist a foreign/full-screen overlay",
        )

        val wrongWindow = evidence(
            currentSessionReady = false,
            purpose = ScreenRecoveryCapturePurpose.MATCHMAKING_ERROR_DIALOG,
            preSessionQueueModalAuthorized = true,
            foregroundAfter = codex,
        )
        assertEquals(
            "foreground-window-not-exact-target-before-and-after",
            ScreenRecoveryCaptureAuthority.failureReason(wrongWindow),
        )

        assertEquals(
            "target-pid-not-current-game",
            ScreenRecoveryCaptureAuthority.failureReason(
                evidence(
                    currentSessionReady = false,
                    purpose = ScreenRecoveryCapturePurpose.MATCHMAKING_ERROR_DIALOG,
                    preSessionQueueModalAuthorized = true,
                    currentGameProcessId = 777L,
                ),
            ),
        )
        assertEquals(
            "target-window-not-visible-before-and-after",
            ScreenRecoveryCaptureAuthority.failureReason(
                evidence(
                    currentSessionReady = false,
                    purpose = ScreenRecoveryCapturePurpose.MATCHMAKING_ERROR_DIALOG,
                    preSessionQueueModalAuthorized = true,
                    targetWindowVisibleAfter = false,
                ),
            ),
        )
        assertEquals(
            "game-process-not-alive",
            ScreenRecoveryCaptureAuthority.failureReason(
                evidence(
                    processAlive = false,
                    currentSessionReady = false,
                    purpose = ScreenRecoveryCapturePurpose.MATCHMAKING_ERROR_DIALOG,
                    preSessionQueueModalAuthorized = true,
                ),
            ),
        )
    }

    @Test
    fun `pre-session exception is limited to non-game tournament queue context`() {
        fun allows(
            purpose: ScreenRecoveryCapturePurpose = ScreenRecoveryCapturePurpose.MATCHMAKING_ERROR_DIALOG,
            tournamentMode: Boolean = true,
            activeGame: Boolean = false,
            mulligan: Boolean = false,
            terminal: Boolean = false,
        ) = ScreenRecoveryCapturePurposePolicy.allowsPreSessionQueueModal(
            purpose,
            tournamentMode,
            activeGame,
            mulligan,
            terminal,
        )

        assertTrue(allows())
        assertFalse(allows(purpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY))
        assertFalse(allows(tournamentMode = false))
        assertFalse(allows(activeGame = true))
        assertFalse(allows(mulligan = true))
        assertFalse(allows(terminal = true))
    }

    @Test
    fun `stale client probe is time bounded capture only and fails closed for active game contexts`() {
        fun allows(
            stuckForMs: Long = ScreenRecoveryCapturePurposePolicy.STALE_CLIENT_PROBE_AFTER_MS,
            nonGameplayScreen: Boolean = true,
            activeGame: Boolean = false,
            mulligan: Boolean = false,
            terminal: Boolean = false,
            warModelAuthoritative: Boolean = true,
        ) = ScreenRecoveryCapturePurposePolicy.allowsPreSessionStaleClientProbe(
            ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
            nonGameplayScreen,
            activeGame,
            mulligan,
            terminal,
            stuckForMs,
            warModelAuthoritative,
        )

        assertTrue(allows())
        assertFalse(allows(stuckForMs = ScreenRecoveryCapturePurposePolicy.STALE_CLIENT_PROBE_AFTER_MS - 1))
        assertFalse(allows(nonGameplayScreen = false))
        assertTrue(
            allows(nonGameplayScreen = false, warModelAuthoritative = false),
            "a stale GAMEPLAY mode is not authoritative when the current Power.log session is missing",
        )
        assertFalse(allows(activeGame = true))
        assertFalse(allows(mulligan = true))
        assertFalse(allows(terminal = true))
        assertTrue(allows(terminal = true, mulligan = true, warModelAuthoritative = false))
        assertFalse(
            ScreenRecoveryCapturePurposePolicy.allowsPreSessionStaleClientProbe(
                ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY,
                true, false, false, false,
                ScreenRecoveryCapturePurposePolicy.STALE_CLIENT_PROBE_AFTER_MS * 10,
            ),
            "a long stall does not grant ordinary recovery the pre-session exception",
        )
        assertEquals(
            ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
            ScreenRecoveryCapturePurposePolicy.purposeForStateRecovery(
                tournamentMode = true,
                nonGameplayScreen = true,
                activeGame = false,
                mulligan = false,
                terminal = false,
                recentInputIntent = false,
                stuckForMs = ScreenRecoveryCapturePurposePolicy.STALE_CLIENT_PROBE_AFTER_MS,
                warModelAuthoritative = false,
            ),
        )
        assertEquals(
            ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
            ScreenRecoveryCapturePurposePolicy.purposeForStateRecovery(
                tournamentMode = false,
                nonGameplayScreen = true,
                activeGame = false,
                mulligan = false,
                terminal = false,
                recentInputIntent = false,
                stuckForMs = ScreenRecoveryCapturePurposePolicy.STALE_CLIENT_PROBE_AFTER_MS,
            ),
        )
        assertEquals(
            ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY,
            ScreenRecoveryCapturePurposePolicy.purposeForStateRecovery(
                tournamentMode = true,
                activeGame = true,
                mulligan = false,
                terminal = false,
                recentInputIntent = false,
                stuckForMs = ScreenRecoveryCapturePurposePolicy.STALE_CLIENT_PROBE_AFTER_MS * 2,
            ),
        )
    }

    @Test
    fun `startup and stale gameplay probes can classify missing-log session under exact window authority`() {
        val stalePurpose = ScreenRecoveryCapturePurposePolicy.purposeForStateRecovery(
            tournamentMode = false,
            nonGameplayScreen = false,
            activeGame = false,
            mulligan = false,
            terminal = false,
            recentInputIntent = false,
            stuckForMs = ScreenRecoveryCapturePurposePolicy.STALE_CLIENT_PROBE_AFTER_MS,
            warModelAuthoritative = false,
        )
        assertEquals(ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE, stalePurpose)
        assertTrue(ScreenRecoveryCapturePurposePolicy.allowsPreSessionStaleClientProbe(
            stalePurpose,
            nonGameplayScreen = false,
            activeGame = false,
            mulligan = false,
            terminal = false,
            stuckForMs = ScreenRecoveryCapturePurposePolicy.STALE_CLIENT_PROBE_AFTER_MS,
            warModelAuthoritative = false,
        ))
        assertTrue(ScreenRecoveryCapturePurposePolicy.allowsStaleProbeRecovery("RECONNECT"))
        assertFalse(ScreenRecoveryCapturePurposePolicy.allowsStaleProbeRecovery("UNKNOWN"))
        assertTrue(ScreenRecoveryCaptureAuthority.isAuthorized(
            evidence(
                currentSessionReady = false,
                purpose = stalePurpose,
                preSessionStaleClientProbeAuthorized = true,
            ),
        ), "capture stays bound to exact current PID/HWND/foreground/pixel ownership")

        val startupPurpose = ScreenRecoveryCapturePurposePolicy.purposeForStateRecovery(
            tournamentMode = false,
            nonGameplayScreen = false,
            activeGame = false,
            mulligan = false,
            terminal = false,
            recentInputIntent = false,
            startupProbe = true,
            warModelAuthoritative = false,
        )
        assertEquals(ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE, startupPurpose)
        assertTrue(ScreenRecoveryCaptureAuthority.isAuthorized(
            evidence(
                currentSessionReady = false,
                purpose = startupPurpose,
                preSessionStartupClientProbeAuthorized = true,
            ),
        ))
    }

    @Test
    fun `Yogg startup capture requires no session game or active queue evidence`() {
        assertTrue(
            ScreenRecoveryCapturePurposePolicy.allowsPreSessionYoggProbe(
                ScreenRecoveryCapturePurpose.STARTUP_YOGG_PROBE,
                currentSessionReady = false,
                activeGame = false,
                noActiveQueueEvidence = true,
            ),
        )
        assertFalse(ScreenRecoveryCapturePurposePolicy.allowsPreSessionYoggProbe(
            ScreenRecoveryCapturePurpose.STARTUP_YOGG_PROBE, true, false, true,
        ))
        assertFalse(ScreenRecoveryCapturePurposePolicy.allowsPreSessionYoggProbe(
            ScreenRecoveryCapturePurpose.STARTUP_YOGG_PROBE, false, true, true,
        ))
        assertFalse(ScreenRecoveryCapturePurposePolicy.allowsPreSessionYoggProbe(
            ScreenRecoveryCapturePurpose.STARTUP_YOGG_PROBE, false, false, false,
        ))
        assertFalse(ScreenRecoveryCapturePurposePolicy.allowsPreSessionYoggProbe(
            ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY, false, false, true,
        ))
    }

    @Test
    fun `stale probe can recover recognized reconnect or exact configured deck selection screens`() {
        assertTrue(ScreenRecoveryCapturePurposePolicy.allowsStaleProbeRecovery("RECONNECT"))
        assertTrue(ScreenRecoveryCapturePurposePolicy.allowsStaleProbeRecovery("RECONNECT_FAILURE"))
        assertTrue(ScreenRecoveryCapturePurposePolicy.allowsStaleProbeRecovery("RECONNECT_SPINNER"))
        assertTrue(ScreenRecoveryCapturePurposePolicy.allowsStaleProbeRecovery("MATCHMAKING"))
        assertTrue(ScreenRecoveryCapturePurposePolicy.allowsStaleProbeRecovery("DECK_SELECTION"))
        assertFalse(ScreenRecoveryCapturePurposePolicy.allowsStaleProbeRecovery("UNKNOWN"))
        assertFalse(ScreenRecoveryCapturePurposePolicy.allowsStaleProbeRecovery("MATCHMAKING_ERROR_DIALOG"))

        assertTrue(
            ScreenRecoveryCaptureAuthority.isAuthorized(
                evidence(
                    currentSessionReady = false,
                    purpose = ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
                    preSessionStaleClientProbeAuthorized = true,
                ),
            ),
        )
        assertFalse(
            ScreenRecoveryCaptureAuthority.isAuthorized(
                evidence(
                    currentSessionReady = false,
                    purpose = ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
                ),
            ),
            "the purpose label alone cannot grant stale pre-session capture",
        )
    }

    @Test
    fun `stale fresh configured deck selection reaches safe matching transition only with authoritative evidence`() {
        fun decision(
            purpose: ScreenRecoveryCapturePurpose = ScreenRecoveryCapturePurpose.STALE_CLIENT_PROBE,
            screen: String = "DECK_SELECTION",
            confidence: Int = 100,
            evidence: String = "deck-selection-title",
            fresh: Boolean = true,
            configuredDeck: Boolean = true,
            configuredRunMode: Boolean = true,
            activeGame: Boolean = false,
            activeMatchmaking: Boolean = false,
        ) = ScreenRecoveryCapturePurposePolicy.staleProbeRecoveryDecision(
            purpose, screen, confidence, evidence, fresh, configuredDeck, configuredRunMode,
            activeGame, activeMatchmaking,
        )

        assertEquals(
            ScreenRecoveryCapturePurposePolicy.StaleProbeRecoveryDecision.APPLY_CONFIGURED_DECK_SELECTION,
            decision(),
        )
        val safeTransition = ScreenStateRecovery.recoveryTransitionForTest("选择套牌 狂野对战")
        assertEquals("DECK_SELECTION", safeTransition?.screen)
        assertEquals("START_MATCHING", safeTransition?.action)

        listOf(
            decision(fresh = false),
            decision(confidence = 84),
            decision(evidence = ""),
            decision(screen = "UNKNOWN"),
            decision(configuredDeck = false),
            decision(configuredRunMode = false),
            decision(activeGame = true),
            decision(activeMatchmaking = true),
            decision(purpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY),
        ).forEach {
            assertEquals(ScreenRecoveryCapturePurposePolicy.StaleProbeRecoveryDecision.CLASSIFICATION_ONLY, it)
        }
    }

    private fun evidence(
        processAlive: Boolean = true,
        currentSessionReady: Boolean = true,
        target: CapturedWindowIdentity? = game,
        foregroundBefore: CapturedWindowIdentity? = game,
        foregroundAfter: CapturedWindowIdentity? = game,
        owners: List<CapturedWindowIdentity?> = List(63) { game },
        captureBounds: Rectangle = client,
        imageWidth: Int = client.width,
        imageHeight: Int = client.height,
        purpose: ScreenRecoveryCapturePurpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY,
        preSessionQueueModalAuthorized: Boolean = false,
        preSessionMatchmakingAuthorized: Boolean = false,
        preSessionStaleClientProbeAuthorized: Boolean = false,
        preSessionStartupClientProbeAuthorized: Boolean = false,
        preSessionYoggProbeAuthorized: Boolean = false,
        currentGameProcessId: Long? = game.processId.toLong(),
        targetWindowVisibleBefore: Boolean = true,
        targetWindowVisibleAfter: Boolean = true,
    ) = ScreenRecoveryCaptureEvidence(
        processAlive = processAlive,
        currentSessionReady = currentSessionReady,
        target = target,
        foregroundBefore = foregroundBefore,
        foregroundAfter = foregroundAfter,
        clientBounds = client,
        captureBounds = captureBounds,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        visibleOwnersBefore = owners,
        visibleOwnersAfter = owners,
        purpose = purpose,
        preSessionQueueModalAuthorized = preSessionQueueModalAuthorized,
        preSessionMatchmakingAuthorized = preSessionMatchmakingAuthorized,
        preSessionStaleClientProbeAuthorized = preSessionStaleClientProbeAuthorized,
        preSessionStartupClientProbeAuthorized = preSessionStartupClientProbeAuthorized,
        preSessionYoggProbeAuthorized = preSessionYoggProbeAuthorized,
        currentGameProcessId = currentGameProcessId,
        targetWindowVisibleBefore = targetWindowVisibleBefore,
        targetWindowVisibleAfter = targetWindowVisibleAfter,
    )

    private fun recoveryEvidence(capturedPixelsVerified: Boolean) = ScreenRecoveryAuthorityEvidence(
        processAlive = true,
        windowPresent = true,
        windowVerified = true,
        foregroundConfirmed = true,
        sameWindow = true,
        capturedPixelsVerified = capturedPixelsVerified,
    )
}
