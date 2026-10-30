package club.xiaojiawei.hsscript.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StuckScreenUnblockPolicyTest {
    private fun evidence(
        now: Long,
        stableForMs: Long = 0L,
        fingerprint: String = "same-frame",
        dispatchAccepted: Boolean = true,
        paused: Boolean = false,
        activeGame: Boolean = false,
        activeQueue: Boolean = false,
        terminal: Boolean = false,
        modeMismatchDeck: Boolean = false,
        reconnect: Boolean = false,
        result: Boolean = false,
        gameplay: Boolean = false,
        queueScreen: Boolean = false,
        fresh: Boolean = true,
        exactWindow: Boolean = true,
        eligible: Boolean = true,
        knownBlocker: Boolean = true,
        currentSessionReady: Boolean = false,
    ) = StuckScreenUnblockPolicy.Evidence(
        nowMs = now,
        stableForMs = stableForMs,
        fingerprint = fingerprint,
        captureFresh = fresh,
        exactWindowVerified = exactWindow,
        eligibleIdleProbe = eligible,
        recognizedSafeBlocker = knownBlocker,
        currentSessionReady = currentSessionReady,
        activeGame = activeGame,
        activeQueue = activeQueue,
        terminal = terminal,
        paused = paused,
        modeMismatchDeck = modeMismatchDeck,
        reconnect = reconnect,
        resultOrTerminalScreen = result,
        activeGameplayScreen = gameplay,
        matchmakingScreen = queueScreen,
        dispatchAccepted = dispatchAccepted,
    )

    @Test
    fun `three clicks require sixty second same screen then another sixty seconds to restart`() {
        val policy = StuckScreenUnblockPolicy()
        assertEquals(StuckScreenUnblockPolicy.Action.WAIT, policy.observe(evidence(1_000)).action)
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT, policy.observe(evidence(61_000)).action)
        assertEquals(true, policy.completeTopLeftClickBatch("same-frame", 61_100))
        assertEquals(StuckScreenUnblockPolicy.Action.REPROBE, policy.observe(evidence(121_099)).action)
        assertEquals(StuckScreenUnblockPolicy.Action.RESTART_CLIENT, policy.observe(evidence(121_100)).action)
    }

    @Test
    fun `fingerprint change resets ladder and dispatch failure stops`() {
        val policy = StuckScreenUnblockPolicy()
        policy.observe(evidence(1_000))
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT, policy.observe(evidence(61_000)).action)
        assertEquals(StuckScreenUnblockPolicy.Action.UNBLOCKED, policy.observe(evidence(62_000, fingerprint = "unblocked" )).action)
        assertEquals(StuckScreenUnblockPolicy.Action.WAIT,
            policy.observe(evidence(62_001, stableForMs = 1_000, fingerprint = "unblocked")).action,
            "a different stable identity begins its own fresh 60 second first stage")
        assertEquals("click-dispatch-failed", policy.observe(
            evidence(123_000, fingerprint = "unblocked", dispatchAccepted = false),
        ).reason)
    }

    @Test
    fun `unsafe or dedicated recovery contexts never enter generic ladder`() {
        val cases = listOf(
            evidence(61_000, paused = true), evidence(61_000, activeGame = true),
            evidence(61_000, activeQueue = true), evidence(61_000, terminal = true),
            evidence(61_000, currentSessionReady = true),
            evidence(61_000, result = true), evidence(61_000, gameplay = true),
            evidence(61_000, queueScreen = true), evidence(61_000, fresh = false),
            evidence(61_000, exactWindow = false), evidence(61_000, eligible = false),
            evidence(61_000, knownBlocker = false),
        )
        cases.forEach { assertEquals(StuckScreenUnblockPolicy.Action.STOP, StuckScreenUnblockPolicy().observe(it).action) }
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT,
            StuckScreenUnblockPolicy().observe(evidence(61_000, stableForMs = 60_000, reconnect = true)).action,
            "fresh exact reconnect may use the shared top-left ladder")
    }

    @Test
    fun `exact confirmed wrong mode deck can use ladder but never becomes start proof`() {
        val policy = StuckScreenUnblockPolicy()
        val first = evidence(61_000, stableForMs = 61_000, modeMismatchDeck = true)
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT, policy.observe(first).action)
        assertTrue(policy.completeTopLeftClickBatch(first.fingerprint, 61_100))
        assertEquals(StuckScreenUnblockPolicy.Action.REPROBE,
            policy.observe(evidence(121_099, modeMismatchDeck = true)).action)
        assertEquals(StuckScreenUnblockPolicy.Action.RESTART_CLIENT,
            policy.observe(evidence(121_100, modeMismatchDeck = true)).action)
        assertEquals(StuckScreenUnblockPolicy.Action.STOP,
            policy.observe(evidence(121_200, modeMismatchDeck = true, activeGame = true)).action)
    }

    @Test
    fun `unknown but stable exact client frame reaches bounded ladder`() {
        val policy = StuckScreenUnblockPolicy()
        val decision = policy.observe(evidence(
            now = 60_001L,
            stableForMs = 60_001L,
            fingerprint = "pid-55|hwnd-77|unknown-stable-frame-a91f",
            knownBlocker = true,
        ))
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT, decision.action)
    }

    @Test
    fun `exact reconnect gets three top-left attempts before its restart grace`() {
        val policy = StuckScreenUnblockPolicy()
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT,
            policy.observe(evidence(61_000, stableForMs = 60_000, reconnect = true)).action)
        assertEquals(true, policy.completeTopLeftClickBatch("same-frame", 62_600))
        assertEquals(StuckScreenUnblockPolicy.Action.REPROBE,
            policy.observe(evidence(122_599, reconnect = true)).action)
        assertEquals(StuckScreenUnblockPolicy.Action.RESTART_CLIENT,
            policy.observe(evidence(122_600, reconnect = true)).action)
    }

    @Test
    fun `animated exact mode selector frames preserve one bounded ladder through restart grace`() {
        fun proof(perceptual: String) = StuckScreenUnblockFrameIdentity.create(
            processId = 34_996,
            windowId = "native@selector",
            perceptualFingerprint = perceptual,
            exactReconnectDialog = false,
            semanticScreenIdentity = StuckScreenUnblockFrameIdentity.semanticScreenIdentity(
                screenKind = "TOURNAMENT_MODE_SELECTOR",
                screenMode = "TOURNAMENT",
            ),
        )

        val firstFrame = proof("animation-a")
        val secondFrame = proof("animation-b")
        val thirdFrame = proof("animation-c")
        assertEquals(firstFrame.fingerprint, secondFrame.fingerprint)
        assertEquals(firstFrame.fingerprint, thirdFrame.fingerprint)
        assertTrue(StuckScreenUnblockFrameIdentity.isSameClientScreen(firstFrame, thirdFrame))

        val policy = StuckScreenUnblockPolicy()
        val first = policy.observe(evidence(
            now = 61_000L,
            stableForMs = 60_000L,
            fingerprint = firstFrame.fingerprint,
        ))
        assertEquals(StuckScreenUnblockPolicy.Action.CLICK_TOP_LEFT, first.action)
        assertTrue(policy.completeTopLeftClickBatch(firstFrame.fingerprint, 61_100L))
        assertEquals(StuckScreenUnblockPolicy.Action.REPROBE, policy.observe(evidence(
            now = 121_099L,
            stableForMs = 120_099L,
            fingerprint = secondFrame.fingerprint,
        )).action)
        assertEquals(StuckScreenUnblockPolicy.Action.RESTART_CLIENT, policy.observe(evidence(
            now = 121_100L,
            stableForMs = 120_100L,
            fingerprint = thirdFrame.fingerprint,
        )).action)
    }
}
