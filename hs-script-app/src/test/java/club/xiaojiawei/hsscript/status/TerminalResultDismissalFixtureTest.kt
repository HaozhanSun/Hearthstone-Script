package club.xiaojiawei.hsscript.status

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.TerminalPageCleanupCoordinator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Replays the deployed v4.16.564 terminal-menu incident without launching Hearthstone or dispatching input. */
class TerminalResultDismissalFixtureTest {
    @Test
    fun `incident screenshot and PowerLog fixture drive bounded inputs then require observed transition`() {
        val screenshot = requireNotNull(javaClass.getResourceAsStream(FIXTURE_SCREENSHOT)).use { it.readBytes() }
        assertEquals(SCREENSHOT_SHA256, sha256(screenshot), "fixture must remain the exact live incident screenshot")
        val image: BufferedImage = requireNotNull(ImageIO.read(ByteArrayInputStream(screenshot)))
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)

        val incident = requireNotNull(javaClass.getResourceAsStream(FIXTURE_LOG)).bufferedReader().use { it.readText() }
        assertTrue(incident.contains("RANK_SURRENDER_TERMINAL_PROOF result=ACCEPTED"))
        assertTrue(incident.contains("sameGame=true ownPlayState=LOST opponentPlayState=WON"))
        assertTrue(incident.contains("tag=PLAYSTATE value=CONCEDED"))
        assertTrue(incident.contains("tag=STEP value=FINAL_GAMEOVER"))
        assertTrue(incident.contains("tag=STATE value=COMPLETE"))
        assertTrue(incident.contains("SCREEN_RECOVERY_BLOCKED reason=authority-lost-before-capture"))
        assertTrue(incident.contains("clickAttempts=0 postcheck=UNKNOWN"))

        // A process-owned but OCR-UNKNOWN frame after same-game terminal proof
        // permits at most the upstream center click and one Enter fallback.
        var dispatchedInputs = 0
        var observedResultVisible: Boolean? = null
        repeat(2) { index ->
            val decision = ResultPageDismissalPolicy.decide(
                inWar = true,
                resultPageVisible = observedResultVisible,
                attempt = index + 1,
                maxAttempts = 2,
                clickAttempts = dispatchedInputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            )
            assertEquals(ResultPageDismissalPolicy.Decision.DISPATCH_CLICK, decision)
            val input = GameUtil.staleResultInputForAttempt(dispatchedInputs + 1, maxAttempts = 2)
            assertEquals(
                if (index == 0) ResultPageDismissalPolicy.Input.CENTER_CLICK else ResultPageDismissalPolicy.Input.KEYBOARD_ENTER,
                input,
            )
            dispatchedInputs++
            // Input dispatch is not target acceptance; do not mutate observedResultVisible here.
        }
        assertEquals(2, dispatchedInputs)
        assertEquals(null, observedResultVisible)
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = observedResultVisible,
                attempt = 3,
                maxAttempts = 2,
                clickAttempts = dispatchedInputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
        )
        assertFalse(dispatchedInputs > 2)

        // Only an independently observed Home/deck-selection state confirms
        // the postgame transition and can complete the cleanup capability.
        observedResultVisible = false
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = observedResultVisible,
                attempt = 3,
                maxAttempts = 2,
                clickAttempts = dispatchedInputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
        )
    }

    @Test
    fun `terminal proof cannot authorize fallback when current client capture is unavailable`() {
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 1,
                maxAttempts = 2,
                terminalCleanupAuthorized = true,
                captureAuthorized = false,
            ),
        )
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = null,
                attempt = 3,
                maxAttempts = 2,
                clickAttempts = 0,
                terminalCleanupAuthorized = true,
                captureAuthorized = false,
            ),
        )
    }

    @Test
    fun `v565 repeated recovery callbacks cannot renew the shared terminal input budget`() {
        val screenshot = requireNotNull(javaClass.getResourceAsStream(V565_LOOP_SCREENSHOT)).use { it.readBytes() }
        assertEquals(V565_LOOP_SCREENSHOT_SHA256, sha256(screenshot))
        val image: BufferedImage = requireNotNull(ImageIO.read(ByteArrayInputStream(screenshot)))
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)

        val incident = requireNotNull(javaClass.getResourceAsStream(V565_LOOP_LOG))
            .bufferedReader().use { it.readText() }
        assertTrue(incident.contains("RANK_SURRENDER_TERMINAL_PROOF result=ACCEPTED"))
        assertTrue(incident.contains("ownPlayState=LOST"))
        assertTrue(incident.contains("tag=PLAYSTATE value=CONCEDED"))
        assertTrue(incident.contains("tag=STEP value=FINAL_GAMEOVER"))
        assertTrue(incident.contains("tag=STATE value=COMPLETE"))
        assertTrue(incident.contains("SCREEN_RECOVERY_APPLIED screen=RESULT next=DISMISS_STALE_RESULT"))
        assertTrue(incident.contains("dispatchAccepted=true acceptance=awaiting-current-client-postcheck"))
        assertFalse(incident.contains("RESULT_PAGE_DISMISSAL_CONFIRMED"))
        assertTrue(Regex("RESULT_PAGE_DISMISSAL_INPUT").findAll(incident).count() >= 5)

        val coordinator = club.xiaojiawei.hsscript.utils.TerminalPageCleanupCoordinator()
        val ticket = requireNotNull(coordinator.begin().ticket)
        var observedResultVisible: Boolean? = true // The pinned live screenshot still shows the LOST panel.
        repeat(2) { index ->
            val probe = requireNotNull(coordinator.nextProbe(ticket))
            val snapshot = coordinator.snapshot()
            assertEquals(
                ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
                ResultPageDismissalPolicy.decide(
                    inWar = false,
                    resultPageVisible = observedResultVisible,
                    attempt = probe,
                    maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                    clickAttempts = snapshot.inputs,
                    terminalCleanupAuthorized = true,
                    captureAuthorized = true,
                ),
            )
            assertEquals(index + 1, coordinator.reserveInput(ticket))
            // Robot/SendInput's accepted return is only dispatch evidence.
            assertEquals(true, observedResultVisible)
        }

        coordinator.hold(ticket)
        val laterRecovery = coordinator.begin()
        assertEquals(TerminalPageCleanupCoordinator.BeginState.HELD, laterRecovery.state)
        assertEquals(2, coordinator.snapshot().inputs)
        assertEquals(TerminalPageCleanupCoordinator.State.HELD, coordinator.snapshot().state)

        // A fresh, authorized Home/deck-selection observation is the only release.
        observedResultVisible = false
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = observedResultVisible,
                attempt = TerminalPageCleanupCoordinator.DEFAULT_MAX_PROBES + 1,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = coordinator.snapshot().inputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
        )
        assertTrue(coordinator.confirmDestination(requireNotNull(laterRecovery.ticket)))
        assertEquals(TerminalPageCleanupCoordinator.State.COMPLETED, coordinator.snapshot().state)
        assertEquals(2, coordinator.snapshot().inputs, "confirmation must not be inferred from accepted input logs")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02X".format(it) }

    private companion object {
        const val FIXTURE_SCREENSHOT = "/club/xiaojiawei/hsscript/status/surrender/v564-terminal-menu-unknown-20261004-212018-636.png"
        const val FIXTURE_LOG = "/club/xiaojiawei/hsscript/status/surrender/v564-terminal-menu-unknown-20261004-212018.log"
        const val SCREENSHOT_SHA256 = "E11A3E982AA63114DC4B493CB3A7DA9EBB9B1628A51701E11B1215E0CA14200D"
        const val V565_LOOP_SCREENSHOT = "/club/xiaojiawei/hsscript/status/surrender/v565-result-loop-20261004-223540-274.png"
        const val V565_LOOP_LOG = "/club/xiaojiawei/hsscript/status/surrender/v565-result-loop-20261004-223540-274.log"
        const val V565_LOOP_SCREENSHOT_SHA256 = "1E19CB7625B6C33E3323789AA5F90B368A63B2C50F20C81C43B806FB0F455A25"
    }
}
