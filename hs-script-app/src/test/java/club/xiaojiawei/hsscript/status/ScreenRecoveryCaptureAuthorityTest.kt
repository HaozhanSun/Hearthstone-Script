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

    private fun evidence(
        processAlive: Boolean = true,
        target: CapturedWindowIdentity? = game,
        foregroundBefore: CapturedWindowIdentity? = game,
        foregroundAfter: CapturedWindowIdentity? = game,
        owners: List<CapturedWindowIdentity?>,
        captureBounds: Rectangle = client,
        imageWidth: Int = client.width,
        imageHeight: Int = client.height,
    ) = ScreenRecoveryCaptureEvidence(
        processAlive = processAlive,
        currentSessionReady = true,
        target = target,
        foregroundBefore = foregroundBefore,
        foregroundAfter = foregroundAfter,
        clientBounds = client,
        captureBounds = captureBounds,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        visibleOwnersBefore = owners,
        visibleOwnersAfter = owners,
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
