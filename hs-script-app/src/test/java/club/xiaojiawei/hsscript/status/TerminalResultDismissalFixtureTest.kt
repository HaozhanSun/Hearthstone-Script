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
        assertFalse(
            ScreenStateRecovery.classifyImageForResultFixture(image) == "RESULT",
            "the v564 settings overlay is not a result screen and must not authorize its lower-right gear or any fallback input",
        )

        val incident = requireNotNull(javaClass.getResourceAsStream(FIXTURE_LOG)).bufferedReader().use { it.readText() }
        assertTrue(incident.contains("RANK_SURRENDER_TERMINAL_PROOF result=ACCEPTED"))
        assertTrue(incident.contains("sameGame=true ownPlayState=LOST opponentPlayState=WON"))
        assertTrue(incident.contains("tag=PLAYSTATE value=CONCEDED"))
        assertTrue(incident.contains("tag=STEP value=FINAL_GAMEOVER"))
        assertTrue(incident.contains("tag=STATE value=COMPLETE"))
        assertTrue(incident.contains("SCREEN_RECOVERY_BLOCKED reason=authority-lost-before-capture"))
        assertTrue(incident.contains("clickAttempts=0 postcheck=UNKNOWN"))

        // A process-owned but OCR-UNKNOWN frame after same-game terminal proof
        // is not allow-listed: the capture is not enough to authorize input.
        var dispatchedInputs = 0
        var observedResultVisible: Boolean? = null
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = true,
                resultPageVisible = observedResultVisible,
                attempt = 1,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = dispatchedInputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
        )
        assertEquals(0, dispatchedInputs, "unknown pixels must never dispatch toward the settings overlay")
        assertEquals(null, observedResultVisible)
        assertEquals(
            ResultPageDismissalPolicy.Decision.WAIT_FOR_SCREEN_TRANSITION,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = observedResultVisible,
                attempt = 3,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = dispatchedInputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
        )
        assertEquals(0, dispatchedInputs)

        // Only an independently observed Home/deck-selection state confirms
        // the postgame transition and can complete the cleanup capability.
        observedResultVisible = false
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = observedResultVisible,
                attempt = 3,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
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
    fun `v565 successful live sequence keeps four click Enter pairs in one episode until observed destination`() {
        val incident = requireNotNull(javaClass.getResourceAsStream(V565_CONFIRMED_LOG))
            .bufferedReader().use { it.readText() }
        val inputs = Regex("RESULT_PAGE_DISMISSAL_INPUT input=(CENTER_CLICK|KEYBOARD_ENTER)")
            .findAll(incident).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("CENTER_CLICK", "KEYBOARD_ENTER", "CENTER_CLICK", "KEYBOARD_ENTER",
                "CENTER_CLICK", "KEYBOARD_ENTER", "CENTER_CLICK", "KEYBOARD_ENTER"),
            inputs,
        )
        assertTrue(incident.contains("RESULT_PAGE_DISMISSAL_CONFIRMED source=visible-screen-postcheck"))

        val coordinator = TerminalPageCleanupCoordinator()
        val ticket = requireNotNull(coordinator.begin().ticket)
        repeat(inputs.size) { index ->
            assertEquals(TerminalPageCleanupCoordinator.BeginState.ALREADY_RUNNING, coordinator.begin().state)
            val probe = requireNotNull(coordinator.nextProbe(ticket))
            assertEquals(
                ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
                ResultPageDismissalPolicy.decide(
                    inWar = false,
                    resultPageVisible = true,
                    attempt = probe,
                    maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                    clickAttempts = coordinator.snapshot().inputs,
                    terminalCleanupAuthorized = true,
                    captureAuthorized = true,
                ),
            )
            assertEquals(index + 1, coordinator.reserveInput(ticket))
            assertEquals(inputs[index], requireNotNull(GameUtil.terminalResultInputForAttempt(index + 1)).name)
        }

        // The successful incident's fresh postcheck, not SendInput's accepted
        // return, supplies the destination transition.
        assertEquals(
            ResultPageDismissalPolicy.Decision.CONFIRMED_CLEARED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = false,
                attempt = inputs.size + 1,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = coordinator.snapshot().inputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
        )
        assertTrue(coordinator.confirmDestination(ticket))
        assertEquals(TerminalPageCleanupCoordinator.State.COMPLETED, coordinator.snapshot().state)
    }

    @Test
    fun `v565 repeated recovery callbacks cannot renew the shared terminal input budget`() {
        val screenshot = requireNotNull(javaClass.getResourceAsStream(V565_LOOP_SCREENSHOT)).use { it.readBytes() }
        assertEquals(V565_LOOP_SCREENSHOT_SHA256, sha256(screenshot))
        val image: BufferedImage = requireNotNull(ImageIO.read(ByteArrayInputStream(screenshot)))
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)
        assertEquals(
            "RESULT",
            ScreenStateRecovery.classifyImageForResultFixture(image),
            "the v565 result-only image must pass the production allowlist before cleanup input is permitted",
        )
        val target = GameUtil.terminalContinueTargetForTest()
        assertEquals(-0.09, target.left, 0.0001)
        assertEquals(0.09, target.right, 0.0001)
        assertEquals(0.41, target.top, 0.0001)
        assertEquals(0.48, target.bottom, 0.0001)
        assertTrue(0.5 + target.right < 0.94, "the allow-listed result target stays far left of the bottom-right settings gear")
        assertTrue(0.5 + target.top > 0.90, "the target remains in the result screen's lower-center continue label band")

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
        repeat(8) { index ->
            assertEquals(TerminalPageCleanupCoordinator.BeginState.ALREADY_RUNNING, coordinator.begin().state)
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
            assertEquals(
                if (index % 2 == 0) ResultPageDismissalPolicy.Input.CENTER_CLICK else ResultPageDismissalPolicy.Input.KEYBOARD_ENTER,
                GameUtil.terminalResultInputForAttempt(index + 1),
            )
            // Robot/SendInput's accepted return is only dispatch evidence.
            assertEquals(true, observedResultVisible)
        }

        assertEquals(8, coordinator.snapshot().inputs)
        assertEquals(TerminalPageCleanupCoordinator.State.RUNNING, coordinator.snapshot().state)
        assertEquals(8, coordinator.snapshot().probes)
        // The second live game remained on this screen after at least eight
        // inputs. This replay proves the next callback continues the same
        // shared pair sequence instead of silently starting a fresh 2-input cap.
        val nextProbe = requireNotNull(coordinator.nextProbe(ticket))
        assertEquals(9, nextProbe)
        assertEquals(
            ResultPageDismissalPolicy.Decision.DISPATCH_CLICK,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = observedResultVisible,
                attempt = nextProbe,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = coordinator.snapshot().inputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
        )
        assertEquals(9, coordinator.reserveInput(ticket))
        assertEquals(ResultPageDismissalPolicy.Input.CENTER_CLICK, GameUtil.terminalResultInputForAttempt(9))

        repeat(7) { index ->
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
            assertEquals(10 + index, coordinator.reserveInput(ticket))
        }

        val exhaustedProbe = requireNotNull(coordinator.nextProbe(ticket))
        assertEquals(
            ResultPageDismissalPolicy.Decision.EXHAUSTED,
            ResultPageDismissalPolicy.decide(
                inWar = false,
                resultPageVisible = observedResultVisible,
                attempt = exhaustedProbe,
                maxAttempts = TerminalPageCleanupCoordinator.DEFAULT_MAX_INPUTS,
                clickAttempts = coordinator.snapshot().inputs,
                terminalCleanupAuthorized = true,
                captureAuthorized = true,
            ),
        )
        assertTrue(coordinator.fail(ticket, "result-visible-input-budget-exhausted"))
        assertEquals(TerminalPageCleanupCoordinator.State.FAILED, coordinator.snapshot().state)
        assertEquals(16, coordinator.snapshot().inputs)
        assertEquals(TerminalPageCleanupCoordinator.BeginState.FAILED, coordinator.begin().state)

        assertEquals(
            true,
            observedResultVisible,
            "the recorded second-game image persisted after eight dispatches; offline replay cannot invent an accepted transition",
        )
        assertEquals(16, coordinator.snapshot().inputs, "the episode budget is shared across all recovery callbacks")
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
        const val V565_CONFIRMED_LOG = "/club/xiaojiawei/hsscript/status/surrender/v565-result-cleanup-confirmed-20261004-222834.log"
    }
}
