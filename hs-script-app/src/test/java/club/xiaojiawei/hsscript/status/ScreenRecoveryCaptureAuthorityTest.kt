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

        val readOnlyClassification = evidence(
            currentSessionReady = false,
            preSessionUiClassificationAuthorized = true,
        )
        assertTrue(ScreenRecoveryCaptureAuthority.isAuthorized(readOnlyClassification))
        assertFalse(ScreenRecoverySessionPolicy.mayApplyRecoveryInput(currentSessionReady = false))
        assertTrue(ScreenRecoverySessionPolicy.mayApplyRecoveryInput(currentSessionReady = true))
        var recoveryInputs = 0
        if (ScreenRecoverySessionPolicy.mayApplyRecoveryInput(readOnlyClassification.currentSessionReady)) {
            recoveryInputs++
        }
        assertEquals(0, recoveryInputs, "empty pre-match Power.log permits observation only, never recovery input")

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
            "queue-modal authority cannot be reused as screen-classification authority",
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
    fun `startup observation is read-only and remains subject to exact PID foreground and pixel ownership`() {
        fun startupAuthorized(
            purpose: ScreenRecoveryCapturePurpose = ScreenRecoveryCapturePurpose.STARTUP_MENU_OBSERVATION,
            windowVerified: Boolean = true,
            currentPid: Long? = game.processId.toLong(),
            windowPid: Long? = game.processId.toLong(),
            mode: String? = "NONE",
            working: Boolean = true,
            paused: Boolean = false,
            activeMatch: Boolean = false,
            terminal: Boolean = false,
        ) = ScreenRecoveryCapturePurposePolicy.allowsStartupMenuObservation(
            purpose, windowVerified, currentPid, windowPid, mode, working, paused, activeMatch, terminal,
        )

        assertTrue(startupAuthorized())
        assertFalse(startupAuthorized(purpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY))
        assertFalse(startupAuthorized(windowVerified = false))
        assertFalse(startupAuthorized(currentPid = 88848L))
        assertFalse(startupAuthorized(windowPid = null))
        assertFalse(startupAuthorized(mode = "GAMEPLAY"))
        assertFalse(startupAuthorized(paused = true))
        assertFalse(startupAuthorized(activeMatch = true))
        assertFalse(startupAuthorized(terminal = true))

        val observedFrame = evidence(
            currentSessionReady = false,
            purpose = ScreenRecoveryCapturePurpose.STARTUP_MENU_OBSERVATION,
            preSessionStartupObservationAuthorized = true,
        )
        assertTrue(ScreenRecoveryCaptureAuthority.isAuthorized(observedFrame))
        assertTrue(ScreenRecoveryCaptureAuthority.isReadOnlyStartupObservationAuthorized(observedFrame))
        assertFalse(
            ScreenRecoveryCaptureAuthority.isAuthorized(
                evidence(
                    currentSessionReady = false,
                    purpose = ScreenRecoveryCapturePurpose.STARTUP_MENU_OBSERVATION,
                ),
            ),
            "the purpose label itself cannot authorize a pre-session capture",
        )
        assertFalse(
            ScreenRecoveryCaptureAuthority.isReadOnlyStartupObservationAuthorized(
                observedFrame.copy(currentSessionReady = true),
            ),
        )
        assertFalse(
            ScreenRecoveryCaptureAuthority.isAuthorized(
                observedFrame.copy(visibleOwnersBefore = List(63) { codex }, visibleOwnersAfter = List(63) { codex }),
            ),
            "startup observation keeps the pixel ownership gate; no overlay whitelist is used",
        )

        val dispatched = AtomicInteger()
        assertFalse(
            ScreenRecoveryAuthorityGate.dispatchIfAuthorized(
                recoveryEvidence(capturedPixelsVerified = true, currentSessionReady = false),
            ) { dispatched.incrementAndGet(); true },
            "startup/menu observation must never authorize gameplay or matchmaking input",
        )
        assertEquals(0, dispatched.get())
    }

    @Test
    fun `empty session log rejects generic capture while watchdog preserves live startup without recovery`() {
        val generic = evidence(currentSessionReady = false)
        assertEquals("current-game-session-not-ready", ScreenRecoveryCaptureAuthority.failureReason(generic))
        val startupObservation = generic.copy(
            purpose = ScreenRecoveryCapturePurpose.STARTUP_MENU_OBSERVATION,
            preSessionStartupObservationAuthorized = true,
        )
        assertTrue(ScreenRecoveryCaptureAuthority.isReadOnlyStartupObservationAuthorized(startupObservation))

        val snapshot = NoProgressWatchdog.Snapshot(
            nowMs = 1_000L,
            mode = "NONE",
            expectedMode = "NONE",
            screen = NoProgressWatchdog.ScreenExpectation.MENU_OR_MATCHING,
            processAlive = true,
            currentPid = game.processId.toLong(),
            boundPid = game.processId.toLong(),
            windowPresent = true,
            powerLogPath = null,
            boundPowerLogPath = null,
            powerLogPosition = Long.MIN_VALUE,
            powerLogLength = 0L,
            powerLogAgeMs = Long.MAX_VALUE,
            powerLogUsable = false,
            screenConfirmed = true,
        )
        val decision = NoProgressWatchdog().observe(snapshot)
        assertEquals(NoProgressWatchdog.RecoveryAction.WAIT_EXPECTED, decision.action)
        assertEquals("power-log-unbound-or-unusable", decision.reason)

        val actionCalls = AtomicInteger()
        assertFalse(
            ScreenRecoveryAuthorityGate.dispatchIfAuthorized(
                recoveryEvidence(capturedPixelsVerified = true, currentSessionReady = false),
            ) { actionCalls.incrementAndGet(); true },
        )
        assertEquals(0, actionCalls.get())
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

        fun allowsClassification(
            purpose: ScreenRecoveryCapturePurpose = ScreenRecoveryCapturePurpose.SCREEN_STATE_RECOVERY,
            tournamentMode: Boolean = true,
            activeGame: Boolean = false,
            mulligan: Boolean = false,
            terminal: Boolean = false,
        ) = ScreenRecoveryCapturePurposePolicy.allowsPreSessionScreenClassification(
            purpose, tournamentMode, activeGame, mulligan, terminal,
        )

        assertTrue(allowsClassification())
        assertFalse(allowsClassification(purpose = ScreenRecoveryCapturePurpose.MATCHMAKING_ERROR_DIALOG))
        assertFalse(allowsClassification(tournamentMode = false))
        assertFalse(allowsClassification(activeGame = true))
        assertFalse(allowsClassification(mulligan = true))
        assertFalse(allowsClassification(terminal = true))
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
        preSessionUiClassificationAuthorized: Boolean = false,
        preSessionStartupObservationAuthorized: Boolean = false,
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
        preSessionUiClassificationAuthorized = preSessionUiClassificationAuthorized,
        preSessionStartupObservationAuthorized = preSessionStartupObservationAuthorized,
        currentGameProcessId = currentGameProcessId,
        targetWindowVisibleBefore = targetWindowVisibleBefore,
        targetWindowVisibleAfter = targetWindowVisibleAfter,
    )

    private fun recoveryEvidence(
        capturedPixelsVerified: Boolean,
        currentSessionReady: Boolean = true,
    ) = ScreenRecoveryAuthorityEvidence(
        processAlive = true,
        windowPresent = true,
        windowVerified = true,
        foregroundConfirmed = true,
        sameWindow = true,
        capturedPixelsVerified = capturedPixelsVerified,
        currentSessionReady = currentSessionReady,
    )
}
