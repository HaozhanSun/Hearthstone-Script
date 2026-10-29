package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

class StuckScreenUnblockCallsiteIntegrationTest {
    private val image: BufferedImage by lazy {
        ImageIO.read(requireNotNull(javaClass.getResourceAsStream(
            "/offline-ocr/screen-recovery/hub-mode-chooser-live-20261029-035630.png",
        )))
    }

    private fun evidence(
        now: Long,
        stableForMs: Long = 60_000L,
        fingerprint: String = "9704|native@0x44c086e|live-hub-chooser",
        activeQueue: Boolean = false,
        paused: Boolean = false,
        terminal: Boolean = false,
        result: Boolean = false,
    ) = StuckScreenUnblockPolicy.Evidence(
        nowMs = now,
        stableForMs = stableForMs,
        fingerprint = fingerprint,
        captureFresh = true,
        exactWindowVerified = true,
        eligibleIdleProbe = true,
        recognizedSafeBlocker = true,
        currentSessionReady = false,
        activeGame = false,
        activeQueue = activeQueue,
        terminal = terminal,
        paused = paused,
        resultOrTerminalScreen = result,
    )

    private fun assertLiveChooserClassifiesAsStructurallyVerifiedHub() {
        // With no OCR text, TOURNAMENT can only come from the fixed HUB chooser visual.
        val classification = UpstreamScreenStateRecovery.classifyImageForTest("", image)
        assertEquals("TOURNAMENT", classification)
        assertTrue(SafeIdleUnblockScreenPolicy.matches(
            screenKind = classification,
            screenMode = ModeEnum.HUB.name,
            hubModeChooserVisual = true,
            eventQuestOverlayVisual = false,
        ))
    }

    @Test
    fun `live HUB chooser dispatches three exact client-origin clicks then requests a fresh recheck and bounded restart`() {
        assertLiveChooserClassifiesAsStructurallyVerifiedHub()
        val policy = StuckScreenUnblockPolicy()
        val decision = policy.observe(evidence(now = 61_000L))
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT, decision.action)
        val point = StartupQuestOverlayDismissPolicy.topLeftPixel(Rectangle(0, 0, image.width, image.height))
        assertEquals(Point(0, 0), point)

        val clicks = mutableListOf<Pair<Int, Point>>()
        var rechecks = 0
        val batchInFlight = AtomicBoolean(false)
        assertTrue(StuckScreenUnblockDispatch.dispatchTopLeftBatch(
            batchInFlight = batchInFlight,
            action = decision.action,
            firstClick = decision.clickNumber,
            freshCaptureAuthorized = true,
            point = point,
            expectedFingerprint = "9704|native@0x44c086e|live-hub-chooser",
            completionTimeMs = { 61_000L },
            dispatchClick = { dispatchedPoint, number ->
                clicks += number to dispatchedPoint
                true
            },
            completeBatch = policy::completeTopLeftClickBatch,
            requestFreshRecheck = { rechecks++ },
        ))
        assertEquals(listOf(1, 2, 3), clicks.map { it.first })
        assertEquals(listOf(Point(0, 0), Point(0, 0), Point(0, 0)), clicks.map { it.second })
        assertEquals(1, rechecks)
        assertFalse(batchInFlight.get(), "the batch lock must release after the clicks and recheck request")

        assertEquals(StuckScreenUnblockPolicy.Action.REPROBE, policy.observe(
            evidence(now = 120_999L, stableForMs = 119_999L),
        ).action)
        val restart = policy.observe(evidence(now = 121_000L, stableForMs = 120_000L))
        assertEquals(StuckScreenUnblockPolicy.Action.RESTART_CLIENT, restart.action)
        var restartDispatches = 0
        assertTrue(StuckScreenUnblockDispatch.dispatchRestartIfAuthorized(restart.action, true) {
            restartDispatches++
        })
        assertEquals(1, restartDispatches)
        assertFalse(StuckScreenUnblockDispatch.dispatchRestartIfAuthorized(restart.action, false) {
            restartDispatches++
        })
        assertEquals(1, restartDispatches)
    }

    @Test
    fun `changed frame and active queue pause or terminal evidence prevent click and restart dispatch`() {
        assertLiveChooserClassifiesAsStructurallyVerifiedHub()
        val changedPolicy = StuckScreenUnblockPolicy()
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT,
            changedPolicy.observe(evidence(now = 61_000L)).action)
        assertTrue(changedPolicy.completeTopLeftClickBatch(
            "9704|native@0x44c086e|live-hub-chooser", 61_000L,
        ))
        val changed = changedPolicy.observe(evidence(
            now = 61_500L,
            stableForMs = 60_000L,
            fingerprint = "9704|native@0x44c086e|different-screen",
        ))
        assertEquals(StuckScreenUnblockPolicy.Action.UNBLOCKED, changed.action)
        var changedScreenClicks = 0
        var changedScreenRestarts = 0
        assertFalse(StuckScreenUnblockDispatch.dispatchTopLeftBatch(
            batchInFlight = AtomicBoolean(false),
            action = changed.action,
            firstClick = changed.clickNumber,
            freshCaptureAuthorized = true,
            point = Point(0, 0),
            expectedFingerprint = "9704|native@0x44c086e|different-screen",
            completionTimeMs = { 61_500L },
            dispatchClick = { _, _ -> changedScreenClicks++; true },
            completeBatch = changedPolicy::completeTopLeftClickBatch,
            requestFreshRecheck = {},
        ))
        assertFalse(StuckScreenUnblockDispatch.dispatchRestartIfAuthorized(changed.action, false) {
            changedScreenRestarts++
        })
        assertEquals(0, changedScreenClicks)
        assertEquals(0, changedScreenRestarts)

        val clickCounts = mutableListOf<Int>()
        listOf(
            evidence(now = 61_000L, activeQueue = true),
            evidence(now = 61_000L, paused = true),
            evidence(now = 61_000L, result = true),
        ).forEach { unsafeEvidence ->
            val policy = StuckScreenUnblockPolicy()
            val blocked = policy.observe(unsafeEvidence)
            assertEquals(StuckScreenUnblockPolicy.Action.STOP, blocked.action)
            assertFalse(StuckScreenUnblockDispatch.dispatchTopLeftBatch(
                batchInFlight = AtomicBoolean(false),
                action = blocked.action,
                firstClick = blocked.clickNumber,
                freshCaptureAuthorized = true,
                point = Point(0, 0),
                expectedFingerprint = unsafeEvidence.fingerprint,
                completionTimeMs = { unsafeEvidence.nowMs },
                dispatchClick = { _, _ -> clickCounts += 1; true },
                completeBatch = policy::completeTopLeftClickBatch,
                requestFreshRecheck = {},
            ))
            assertFalse(StuckScreenUnblockDispatch.dispatchRestartIfAuthorized(
                action = blocked.action,
                finalSafetyPassed = false,
                restart = { clickCounts += 99 },
            ))
        }
        assertTrue(clickCounts.isEmpty())
    }

    @Test
    fun `batch already in flight rejects a second click sequence`() {
        val clicks = mutableListOf<Int>()
        val rechecks = mutableListOf<Int>()
        val busyBatch = AtomicBoolean(true)
        assertFalse(StuckScreenUnblockDispatch.dispatchTopLeftBatch(
            batchInFlight = busyBatch,
            action = StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT,
            firstClick = 1,
            freshCaptureAuthorized = true,
            point = Point(0, 0),
            expectedFingerprint = "pid|hwnd|same-frame",
            completionTimeMs = { 61_000L },
            dispatchClick = { _, number -> clicks += number; true },
            completeBatch = { _, _ -> true },
            requestFreshRecheck = { rechecks += 1 },
        ))
        assertTrue(busyBatch.get(), "the active owner retains the lock")
        assertTrue(clicks.isEmpty(), "overlapping click sequences must not dispatch")
        assertTrue(rechecks.isEmpty(), "the rejected sequence must not schedule a second probe")
    }

    @Test
    fun `same exact frame reclassified UNKNOWN to RECONNECT preserves one staged ladder`() {
        assertLiveChooserClassifiesAsStructurallyVerifiedHub()
        val policy = StuckScreenUnblockPolicy()
        val fingerprint = "9704|native@0x44c086e|same-visible-frame"
        val initialUnknownFrame = evidence(now = 61_000L, fingerprint = fingerprint)
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT, policy.observe(initialUnknownFrame).action)
        assertTrue(policy.completeTopLeftClickBatch(fingerprint, 61_000L))

        // Runtime reclassification may change from UNKNOWN to RECONNECT on a
        // later OCR pass. It is not progress unless the perceptual frame changes.
        assertTrue(policy.hasProgressFor(fingerprint))
        assertFalse(policy.hasProgressFor("9704|native@0x44c086e|changed-frame"))
        val reconnectReclassification = policy.observe(
            evidence(now = 120_999L, stableForMs = 119_999L, fingerprint = fingerprint),
        )
        assertEquals(StuckScreenUnblockPolicy.Action.REPROBE, reconnectReclassification.action)
        val oneMinuteLaterSameFrame = policy.observe(
            evidence(now = 121_000L, stableForMs = 120_000L, fingerprint = fingerprint),
        )
        assertEquals(StuckScreenUnblockPolicy.Action.RESTART_CLIENT, oneMinuteLaterSameFrame.action)
    }
}
