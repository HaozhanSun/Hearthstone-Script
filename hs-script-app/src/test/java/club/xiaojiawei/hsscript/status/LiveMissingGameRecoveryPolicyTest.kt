package club.xiaojiawei.hsscript.status

import java.nio.file.Files
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveMissingGameRecoveryPolicyTest {
    private val now = 1_000_000L

    private fun evidence(
        identityMatches: Boolean = true,
        heartbeatAgeMs: Long = 1_000L,
        runningIntent: Boolean = true,
        working: Boolean = true,
        paused: Boolean = false,
        gameAlive: Boolean = false,
        activeGame: Boolean = false,
        activeMatchmaking: Boolean = false,
        terminalState: Boolean = false,
        startupChainActive: Boolean = false,
        nowMs: Long = now,
    ) = LiveMissingGameRecoveryPolicy.Evidence(
        identityMatches, heartbeatAgeMs, runningIntent, working, paused, gameAlive,
        activeGame, activeMatchmaking, terminalState, startupChainActive, nowMs,
    )

    @Test
    fun `terminal cleanup fence is released only for a proven missing client with no active game or matchmaking`() {
        fun shouldRelease(
            gameAlive: Boolean = false,
            activeGame: Boolean = false,
            activeMatchmaking: Boolean = false,
            terminalProof: Boolean = true,
        ) = LiveMissingGameRecoveryPolicy.shouldReleaseTerminalCleanupFence(
            terminalCleanupPending = true,
            gameAlive = gameAlive,
            activeGame = activeGame,
            activeMatchmaking = activeMatchmaking,
            authoritativeTerminalProof = terminalProof,
        )

        assertTrue(shouldRelease(), "complete FINAL_GAMEOVER proof and no client/queue must release stale result cleanup")
        assertFalse(shouldRelease(gameAlive = true))
        assertFalse(shouldRelease(activeGame = true))
        assertFalse(shouldRelease(activeMatchmaking = true), "a live queue remains protected")
        assertFalse(shouldRelease(terminalProof = false))
        assertFalse(
            LiveMissingGameRecoveryPolicy.shouldReleaseTerminalCleanupFence(
                terminalCleanupPending = false,
                gameAlive = false,
                activeGame = false,
                activeMatchmaking = false,
                authoritativeTerminalProof = true,
            ),
        )
        assertFalse(LiveMissingGameRecoveryPolicy.mayRecoverPastReplaceCard(phaseIsReplaceCard = true, authoritativeTerminalProof = false))
        assertTrue(LiveMissingGameRecoveryPolicy.mayRecoverPastReplaceCard(phaseIsReplaceCard = true, authoritativeTerminalProof = true))
        assertTrue(LiveMissingGameRecoveryPolicy.mayRecoverPastReplaceCard(phaseIsReplaceCard = false, authoritativeTerminalProof = false))
    }

    @Test
    fun `accepted terminal capability ends stale missing-client phase and screen waits`() {
        val fixture = requireNotNull(
            javaClass.getResourceAsStream("/offline-ocr/result-flow/rc-v705-terminal-recovery/incident-evidence.txt"),
        ).bufferedReader().use { it.readText() }
        assertTrue(fixture.contains("incidentA.powerLogCurrentSession=false"))
        assertTrue(fixture.contains("incidentA.terminalCleanupPending=true"))
        assertTrue(LiveMissingGameRecoveryPolicy.shouldWaitForMissingGameEvidence(
            gameAlive = false,
            activeGame = true,
            activeMatchmaking = true,
            terminalState = true,
            recentMatchmakingIntent = true,
            authoritativeTerminalProof = true,
        ).not(), "already accepted same-game terminal cleanup is durable after its process/log binding disappears")
        assertTrue(LiveMissingGameRecoveryPolicy.shouldWaitForMissingGameEvidence(
            gameAlive = false,
            activeGame = true,
            activeMatchmaking = true,
            terminalState = true,
            recentMatchmakingIntent = true,
            authoritativeTerminalProof = false,
        ), "unproven missing-process and queue evidence remains fail-closed")
        val recovered = LiveMissingGameRecoveryPolicy.evaluate(
            LiveMissingGameRecoveryPolicy.State(
                missingSinceMs = now - LiveMissingGameRecoveryPolicy.MISSING_GAME_GRACE_MS,
            ),
            evidence(
                activeGame = true,
                activeMatchmaking = true,
                terminalState = true,
                nowMs = now,
            ).copy(authoritativeTerminalProof = true, recentMatchmakingIntent = true),
        )
        assertEquals(
            LiveMissingGameRecoveryPolicy.Decision.START_CONFIGURED_STARTER_CHAIN,
            recovered.decision,
            "after grace, durable same-game terminal proof lets the configured starter chain restore the client",
        )
        assertFalse(LiveMissingGameRecoveryPolicy.shouldWaitForMissingGameEvidence(
            gameAlive = true,
            activeGame = true,
            activeMatchmaking = true,
            terminalState = false,
            recentMatchmakingIntent = false,
            authoritativeTerminalProof = true,
        ), "this defer policy applies only after the exact process has exited")
    }

    @Test
    fun `matching fresh running session starts configured chain only after missing grace`() {
        val first = LiveMissingGameRecoveryPolicy.evaluate(LiveMissingGameRecoveryPolicy.State(), evidence())
        assertEquals(LiveMissingGameRecoveryPolicy.Decision.WAIT_MISSING_GAME_GRACE, first.decision)

        val afterGrace = LiveMissingGameRecoveryPolicy.evaluate(
            first.state,
            evidence(nowMs = now + LiveMissingGameRecoveryPolicy.MISSING_GAME_GRACE_MS),
        )
        assertEquals(LiveMissingGameRecoveryPolicy.Decision.START_CONFIGURED_STARTER_CHAIN, afterGrace.decision)
    }

    @Test
    fun `stale matchmaking latch cannot hold recovery after absent client grace`() {
        val first = LiveMissingGameRecoveryPolicy.evaluate(
            LiveMissingGameRecoveryPolicy.State(),
            evidence(activeMatchmaking = true).copy(recentMatchmakingIntent = true),
        )
        assertEquals(LiveMissingGameRecoveryPolicy.Decision.WAIT_MISSING_GAME_GRACE, first.decision)
        val afterGrace = LiveMissingGameRecoveryPolicy.evaluate(
            first.state,
            evidence(
                activeMatchmaking = true,
                activeGame = true,
                terminalState = true,
                nowMs = now + LiveMissingGameRecoveryPolicy.MISSING_GAME_GRACE_MS,
            ).copy(recentMatchmakingIntent = true),
        )
        assertEquals(LiveMissingGameRecoveryPolicy.Decision.START_CONFIGURED_STARTER_CHAIN, afterGrace.decision)
        assertEquals(
            LiveMissingGameRecoveryPolicy.Decision.WAIT_ACTIVE_OR_TERMINAL,
            LiveMissingGameRecoveryPolicy.evaluate(
                first.state,
                evidence(gameAlive = true, activeMatchmaking = true,
                    nowMs = now + LiveMissingGameRecoveryPolicy.MISSING_GAME_GRACE_MS),
            ).decision,
            "a live queued Hearthstone process remains protected",
        )
    }

    @Test
    fun `active game queue terminal F2 and untrusted heartbeat suppress recovery`() {
        val suppressions = listOf(
            evidence(gameAlive = true, activeGame = true),
            evidence(gameAlive = true, activeMatchmaking = true),
            evidence(gameAlive = true, terminalState = true),
            evidence(paused = true),
            evidence(working = false),
            evidence(identityMatches = false),
            evidence(heartbeatAgeMs = GuardianRunStateReader.MAX_HEARTBEAT_AGE_MS + 1),
        )
        suppressions.forEach { input ->
            val result = LiveMissingGameRecoveryPolicy.evaluate(LiveMissingGameRecoveryPolicy.State(), input)
            assertFalse(result.decision == LiveMissingGameRecoveryPolicy.Decision.START_CONFIGURED_STARTER_CHAIN)
            assertEquals(LiveMissingGameRecoveryPolicy.State(), result.state)
        }
        assertEquals(
            LiveMissingGameRecoveryPolicy.Decision.WAIT_ACTIVE_OR_TERMINAL,
            LiveMissingGameRecoveryPolicy.evaluate(LiveMissingGameRecoveryPolicy.State(), evidence(gameAlive = true)).decision,
        )
    }

    @Test
    fun `complete current game terminal proof releases stale queue and terminal latches for restart`() {
        val state = LiveMissingGameRecoveryPolicy.State(
            missingSinceMs = now - LiveMissingGameRecoveryPolicy.MISSING_GAME_GRACE_MS,
        )
        val result = LiveMissingGameRecoveryPolicy.evaluate(
            state,
            evidence(terminalState = true).copy(
                authoritativeTerminalProof = true,
                recentMatchmakingIntent = true,
            ),
        )
        assertEquals(LiveMissingGameRecoveryPolicy.Decision.START_CONFIGURED_STARTER_CHAIN, result.decision)
        assertEquals(state.missingSinceMs, result.state.missingSinceMs)

        var modeRecoveryCalls = 0
        var starterCalls = 0
        var mode = "GAMEPLAY"
        val workingIntent = true
        val dispatched = LiveMissingGameRecoveryDispatch.dispatch(
            decision = result.decision,
            stillAuthorized = { true },
            recoverModeToStartup = {
                modeRecoveryCalls++
                mode = "STARTUP"
            },
            startConfiguredStarterChain = {
                starterCalls++
                assertTrue(workingIntent, "recovery preserves the running schedule intent")
            },
        )
        assertEquals(LiveMissingGameRecoveryDispatch.Result.STARTER_CHAIN_DISPATCHED, dispatched)
        assertEquals(1, modeRecoveryCalls)
        assertEquals(1, starterCalls)
        assertEquals("STARTUP", mode, "the stale GAMEPLAY mode is reconciled before relaunch")

        val liveClientStillBlocks = LiveMissingGameRecoveryPolicy.evaluate(
            state,
            evidence(gameAlive = true, activeGame = true, terminalState = true)
                .copy(authoritativeTerminalProof = true),
        )
        assertEquals(LiveMissingGameRecoveryPolicy.Decision.WAIT_ACTIVE_OR_TERMINAL, liveClientStillBlocks.decision)

        val liveQueueStillBlocks = LiveMissingGameRecoveryPolicy.evaluate(
            state,
            evidence(gameAlive = true, activeMatchmaking = true, terminalState = true).copy(
                authoritativeTerminalProof = true,
                recentMatchmakingIntent = true,
            ),
        )
        assertEquals(LiveMissingGameRecoveryPolicy.Decision.WAIT_ACTIVE_OR_TERMINAL, liveQueueStillBlocks.decision)

        val unknownWithoutTerminalProof = LiveMissingGameRecoveryPolicy.evaluate(
            state,
            evidence(terminalState = true).copy(recentMatchmakingIntent = true),
        )
        assertEquals(LiveMissingGameRecoveryPolicy.Decision.START_CONFIGURED_STARTER_CHAIN, unknownWithoutTerminalProof.decision)
    }

    @Test
    fun `startup chain activity postpones duplicate start`() {
        val result = LiveMissingGameRecoveryPolicy.evaluate(
            LiveMissingGameRecoveryPolicy.State(missingSinceMs = now - 300_000L),
            evidence(startupChainActive = true),
        )
        assertEquals(LiveMissingGameRecoveryPolicy.Decision.WAIT_STARTUP_CHAIN, result.decision)
    }

    @Test
    fun `retry cooldown and attempt budget are bounded`() {
        var state = LiveMissingGameRecoveryPolicy.State(missingSinceMs = now - 300_000L)
        repeat(LiveMissingGameRecoveryPolicy.MAX_STARTER_CHAIN_ATTEMPTS) { index ->
            val dispatchAt = now + index * LiveMissingGameRecoveryPolicy.RETRY_COOLDOWN_MS
            val decision = LiveMissingGameRecoveryPolicy.evaluate(state, evidence(nowMs = dispatchAt))
            assertEquals(LiveMissingGameRecoveryPolicy.Decision.START_CONFIGURED_STARTER_CHAIN, decision.decision)
            state = LiveMissingGameRecoveryPolicy.recordDispatch(decision.state, dispatchAt)
            if (index == 0) {
                assertEquals(
                    LiveMissingGameRecoveryPolicy.Decision.WAIT_RETRY_COOLDOWN,
                    LiveMissingGameRecoveryPolicy.evaluate(
                        state,
                        evidence(nowMs = dispatchAt + LiveMissingGameRecoveryPolicy.RETRY_COOLDOWN_MS - 1),
                    ).decision,
                )
            }
        }
        assertEquals(
            LiveMissingGameRecoveryPolicy.Decision.EXHAUSTED,
            LiveMissingGameRecoveryPolicy.evaluate(
                state,
                evidence(nowMs = state.lastAttemptAtMs + LiveMissingGameRecoveryPolicy.RETRY_COOLDOWN_MS),
            ).decision,
        )
    }

    @Test
    fun `starter chain dispatch requires a still-authorized decision and invokes configured path`() {
        var modeRecoveryCalls = 0
        var starterChainCalls = 0
        val started = LiveMissingGameRecoveryDispatch.dispatch(
            decision = LiveMissingGameRecoveryPolicy.Decision.START_CONFIGURED_STARTER_CHAIN,
            stillAuthorized = { true },
            recoverModeToStartup = { modeRecoveryCalls++ },
            startConfiguredStarterChain = { starterChainCalls++ },
        )
        assertEquals(LiveMissingGameRecoveryDispatch.Result.STARTER_CHAIN_DISPATCHED, started)
        assertEquals(1, modeRecoveryCalls)
        assertEquals(1, starterChainCalls)

        var authorizationChecks = 0
        val pausedBeforeActualDispatch = LiveMissingGameRecoveryDispatch.dispatch(
            decision = LiveMissingGameRecoveryPolicy.Decision.START_CONFIGURED_STARTER_CHAIN,
            stillAuthorized = { ++authorizationChecks == 1 },
            recoverModeToStartup = { modeRecoveryCalls++ },
            startConfiguredStarterChain = { starterChainCalls++ },
        )
        assertEquals(LiveMissingGameRecoveryDispatch.Result.BLOCKED, pausedBeforeActualDispatch)
        assertEquals(1, starterChainCalls)
        assertEquals(2, authorizationChecks)

        val denied = LiveMissingGameRecoveryDispatch.dispatch(
            decision = LiveMissingGameRecoveryPolicy.Decision.START_CONFIGURED_STARTER_CHAIN,
            stillAuthorized = { false },
            recoverModeToStartup = { modeRecoveryCalls++ },
            startConfiguredStarterChain = { starterChainCalls++ },
        )
        assertEquals(LiveMissingGameRecoveryDispatch.Result.BLOCKED, denied)
        assertEquals(1, starterChainCalls)
    }

    @Test
    fun `guardian state reader requires matching identity and fresh heartbeat`() {
        val path = Files.createTempFile("guardian-state-test", ".json")
        try {
            val start = Instant.parse("2026-10-26T10:00:00Z")
            val heartbeat = Instant.parse("2026-10-26T10:00:20Z")
            val now = Instant.parse("2026-10-26T10:00:25Z")
            Files.writeString(
                path,
                """{"pid":42,"processStartTimeUtc":"$start","deploymentId":"beta-1","intent":"RUNNING","working":true,"paused":false,"heartbeatAtUtc":"$heartbeat"}""",
            )
            val snapshot = GuardianRunStateReader.read(path, 42, start, "beta-1", now)
            assertNotNull(snapshot)
            assertTrue(snapshot!!.identityMatches)
            assertEquals(5_000L, snapshot.heartbeatAgeMs)
            assertEquals("RUNNING", snapshot.intent)

            val foreign = GuardianRunStateReader.read(path, 43, start, "beta-1", now)
            assertFalse(foreign!!.identityMatches)
            val stale = GuardianRunStateReader.read(
                path, 42, start, "beta-1", now.plusMillis(GuardianRunStateReader.MAX_HEARTBEAT_AGE_MS + 1),
            )
            assertTrue(stale!!.heartbeatAgeMs > GuardianRunStateReader.MAX_HEARTBEAT_AGE_MS)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
