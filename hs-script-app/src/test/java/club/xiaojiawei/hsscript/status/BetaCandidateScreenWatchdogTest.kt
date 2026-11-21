package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BetaCandidateScreenWatchdogTest {

    @Test
    fun `repeated positive candidate reaches restart at exactly two minutes`() {
        val watchdog = BetaCandidateScreenWatchdog()

        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESET_IDENTITY,
            watchdog.observe(candidate(nowMs = 0L)).action,
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT,
            watchdog.observe(candidate(nowMs = 119_999L)).action,
        )
        val threshold = watchdog.observe(candidate(nowMs = 120_000L))
        assertEquals(BetaCandidateScreenWatchdog.Action.RESTART, threshold.action)
        assertEquals(120_000L, threshold.stableForMs)
        assertEquals("candidate-stable-threshold-reached", threshold.reason)
    }

    @Test
    fun `screen foreground pid and window transitions reset the candidate episode`() {
        val watchdog = BetaCandidateScreenWatchdog()
        watchdog.observe(candidate(nowMs = 0L, screen = "RECONNECT"))

        val screenChanged = watchdog.observe(candidate(nowMs = 120_000L, screen = "SERVER_UNAVAILABLE"))
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, screenChanged.action)

        val foregroundChanged = watchdog.observe(
            candidate(nowMs = 240_000L, screen = "SERVER_UNAVAILABLE", hwnd = "hwnd-2"),
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, foregroundChanged.action)

        val pidChanged = watchdog.observe(
            candidate(nowMs = 360_000L, screen = "SERVER_UNAVAILABLE", pid = 43L),
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, pidChanged.action)
    }

    @Test
    fun `capture or OCR uncertainty clears evidence and never restarts`() {
        val watchdog = BetaCandidateScreenWatchdog()
        watchdog.observe(candidate(nowMs = 0L))

        val uncertain = watchdog.observe(
            candidate(nowMs = 120_000L, confidence = 0, captureConfirmed = false, screen = "UNKNOWN"),
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN, uncertain.action)

        val fresh = watchdog.observe(candidate(nowMs = 240_000L))
        assertEquals(BetaCandidateScreenWatchdog.Action.RESET_IDENTITY, fresh.action)
    }

    @Test
    fun `unknown OCR only becomes a candidate when a centered modal visual is repeated`() {
        val watchdog = BetaCandidateScreenWatchdog()
        assertEquals(
            BetaCandidateScreenWatchdog.Action.WAIT_UNCERTAIN,
            watchdog.observe(candidate(nowMs = 0L, screen = "UNKNOWN", confidence = 0)).action,
        )

        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESET_IDENTITY,
            watchdog.observe(
                candidate(
                    nowMs = 1L,
                    screen = "UNKNOWN",
                    confidence = 0,
                    visualModalConfirmed = true,
                    visualModalFingerprint = 0x1234L,
                ),
            ).action,
        )
        val threshold = watchdog.observe(
            candidate(
                nowMs = 120_001L,
                screen = "UNKNOWN",
                confidence = 0,
                visualModalConfirmed = true,
                visualModalFingerprint = 0x1234L,
            ),
        )
        assertEquals(BetaCandidateScreenWatchdog.Action.RESTART, threshold.action)

        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESET_IDENTITY,
            watchdog.observe(
                candidate(
                    nowMs = 240_002L,
                    screen = "UNKNOWN",
                    confidence = 0,
                    visualModalConfirmed = true,
                    visualModalFingerprint = 0x5678L,
                ),
            ).action,
            "a changed visual fingerprint must start a new episode",
        )
    }

    @Test
    fun `recognized dialog outranks actionable underlay while healthy screens never restart`() {
        val watchdog = BetaCandidateScreenWatchdog()
        watchdog.observe(
            candidate(
                nowMs = 0L,
                screen = "DECK_SELECTION",
                dialogScreen = "START_GAME_ERROR_DIALOG",
            ),
        )
        assertEquals(
            BetaCandidateScreenWatchdog.Action.RESTART,
            watchdog.observe(
                candidate(
                    nowMs = 120_000L,
                    screen = "DECK_SELECTION",
                    dialogScreen = "START_GAME_ERROR_DIALOG",
                ),
            ).action,
            "the recognized modal must be the stable candidate, not the actionable underlay",
        )

        val healthy = BetaCandidateScreenWatchdog()
        healthy.observe(candidate(nowMs = 0L, screen = "MATCHMAKING"))
        assertEquals(
            BetaCandidateScreenWatchdog.Action.DEFER_HEALTHY_SCREEN,
            healthy.observe(candidate(nowMs = 120_000L, screen = "MATCHMAKING")).action,
        )
    }

    @Test
    fun `manual pause live game mulligan and terminal guards reset without restart`() {
        val guarded = listOf(
            BetaCandidateScreenWatchdog.Action.DEFER_MANUAL_PAUSE to candidate(nowMs = 0L, paused = true),
            BetaCandidateScreenWatchdog.Action.DEFER_ACTIVE_GAME to candidate(nowMs = 0L, inWar = true),
            BetaCandidateScreenWatchdog.Action.DEFER_MULLIGAN to candidate(nowMs = 0L, mulligan = true),
            BetaCandidateScreenWatchdog.Action.DEFER_TERMINAL to candidate(nowMs = 0L, terminal = true),
        )

        guarded.forEach { (expected, snapshot) ->
            val watchdog = BetaCandidateScreenWatchdog()
            watchdog.observe(candidate(nowMs = 0L))
            val decision = watchdog.observe(snapshot.copy(nowMs = 120_000L))
            assertEquals(expected, decision.action)
            assertEquals(0, decision.restartCount)
        }
    }

    @Test
    fun `restart enters bounded backoff then rearms and exhausts repeated same-identity restarts`() {
        val watchdog = BetaCandidateScreenWatchdog()
        watchdog.observe(candidate(nowMs = 0L))
        assertEquals(BetaCandidateScreenWatchdog.Action.RESTART, watchdog.observe(candidate(nowMs = 120_000L)).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.BACKOFF, watchdog.observe(candidate(nowMs = 120_001L)).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.REARM, watchdog.observe(candidate(nowMs = 240_000L)).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.RESTART, watchdog.observe(candidate(nowMs = 360_000L)).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.BACKOFF, watchdog.observe(candidate(nowMs = 360_001L)).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.REARM, watchdog.observe(candidate(nowMs = 480_000L)).action)
        assertEquals(BetaCandidateScreenWatchdog.Action.EXHAUSTED, watchdog.observe(candidate(nowMs = 600_000L)).action)
        assertTrue(watchdog.observe(candidate(nowMs = 720_000L)).action == BetaCandidateScreenWatchdog.Action.EXHAUSTED)
    }

    private fun candidate(
        nowMs: Long,
        screen: String = "RECONNECT",
        dialogScreen: String? = null,
        visualModalConfirmed: Boolean = false,
        visualModalFingerprint: Long? = null,
        confidence: Int = 100,
        captureConfirmed: Boolean = true,
        foregroundConfirmed: Boolean = true,
        pid: Long = 42L,
        hwnd: String = "hwnd-1",
        working: Boolean = true,
        paused: Boolean = false,
        inWar: Boolean = false,
        mulligan: Boolean = false,
        terminal: Boolean = false,
    ) = BetaCandidateScreenWatchdog.Snapshot(
        nowMs = nowMs,
        screen = screen,
        dialogScreen = dialogScreen,
        visualModalConfirmed = visualModalConfirmed,
        visualModalFingerprint = visualModalFingerprint,
        confidence = confidence,
        captureConfirmed = captureConfirmed,
        foregroundConfirmed = foregroundConfirmed,
        pid = pid,
        hwnd = hwnd,
        foregroundIdentity = hwnd,
        working = working,
        paused = paused,
        inWar = inWar,
        mulligan = mulligan,
        terminal = terminal,
    )
}
