package club.xiaojiawei.hsscript.status

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Reads the launcher's atomic heartbeat and rejects foreign, stale, or non-running sessions. */
internal object GuardianRunStateReader {
    const val MAX_HEARTBEAT_AGE_MS = 30_000L

    data class Snapshot(
        val identityMatches: Boolean,
        val intent: String,
        val working: Boolean,
        val paused: Boolean,
        val heartbeatAgeMs: Long,
    )

    fun read(
        path: Path,
        expectedPid: Long,
        expectedStartTime: Instant,
        expectedDeploymentId: String,
        now: Instant = Instant.now(),
    ): Snapshot? = runCatching {
        val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
        val pid = root["pid"]?.jsonPrimitive?.longOrNull ?: return null
        val processStart = root["processStartTimeUtc"]?.jsonPrimitive?.contentOrNull ?: return null
        val deploymentId = root["deploymentId"]?.jsonPrimitive?.contentOrNull ?: return null
        val heartbeatText = root["heartbeatAtUtc"]?.jsonPrimitive?.contentOrNull ?: return null
        val heartbeat = Instant.parse(heartbeatText)
        val identityMatches = pid == expectedPid &&
            runCatching { Instant.parse(processStart) == expectedStartTime }.getOrDefault(false) &&
            deploymentId == expectedDeploymentId
        Snapshot(
            identityMatches = identityMatches,
            intent = root["intent"]?.jsonPrimitive?.contentOrNull ?: "UNKNOWN",
            working = root["working"]?.jsonPrimitive?.booleanOrNull ?: false,
            paused = root["paused"]?.jsonPrimitive?.booleanOrNull ?: true,
            heartbeatAgeMs = Duration.between(heartbeat, now).toMillis(),
        )
    }.getOrNull()
}

/** Pure policy for restoring Hearthstone through the existing in-process starter chain. */
internal object LiveMissingGameRecoveryPolicy {
    const val MISSING_GAME_GRACE_MS = 120_000L
    const val RETRY_COOLDOWN_MS = 60_000L
    const val MAX_STARTER_CHAIN_ATTEMPTS = 3

    enum class Decision {
        WAIT_UNVERIFIED,
        WAIT_NOT_RUNNING,
        WAIT_PAUSED,
        WAIT_ACTIVE_OR_TERMINAL,
        WAIT_STARTUP_CHAIN,
        WAIT_MISSING_GAME_GRACE,
        WAIT_RETRY_COOLDOWN,
        START_CONFIGURED_STARTER_CHAIN,
        EXHAUSTED,
    }

    /** A verified finished match with no client or active queue cannot retain the UI-cleanup dispatch fence. */
    fun shouldReleaseTerminalCleanupFence(
        terminalCleanupPending: Boolean,
        gameAlive: Boolean,
        activeGame: Boolean,
        activeMatchmaking: Boolean,
        authoritativeTerminalProof: Boolean,
    ): Boolean = terminalCleanupPending && !gameAlive && !activeGame && !activeMatchmaking &&
        authoritativeTerminalProof

    /**
     * A process-owned phase/screen latch cannot prove an active game once the
     * exact process is absent. An already accepted terminal-cleanup capability
     * resolves those stale latches; otherwise keep waiting for fresh proof.
     */
    fun shouldWaitForMissingGameEvidence(
        gameAlive: Boolean,
        activeGame: Boolean,
        activeMatchmaking: Boolean,
        terminalState: Boolean,
        recentMatchmakingIntent: Boolean,
        authoritativeTerminalProof: Boolean,
    ): Boolean = !gameAlive && !authoritativeTerminalProof &&
        (activeGame || activeMatchmaking || terminalState || recentMatchmakingIntent)

    /** REPLACE_CARD is an active-game fence except when complete proof makes it stale terminal state. */
    fun mayRecoverPastReplaceCard(phaseIsReplaceCard: Boolean, authoritativeTerminalProof: Boolean): Boolean =
        !phaseIsReplaceCard || authoritativeTerminalProof

    data class State(
        val missingSinceMs: Long = 0L,
        val attempts: Int = 0,
        val lastAttemptAtMs: Long = 0L,
    )

    data class Evidence(
        val identityMatches: Boolean,
        val heartbeatAgeMs: Long,
        val runningIntent: Boolean,
        val working: Boolean,
        val paused: Boolean,
        val gameAlive: Boolean,
        val activeGame: Boolean,
        val activeMatchmaking: Boolean,
        val terminalState: Boolean,
        val startupChainActive: Boolean,
        val nowMs: Long,
        val authoritativeTerminalProof: Boolean = false,
        val recentMatchmakingIntent: Boolean = false,
    )

    data class Evaluation(val state: State, val decision: Decision)

    fun evaluate(state: State, evidence: Evidence): Evaluation {
        if (!evidence.identityMatches || evidence.heartbeatAgeMs !in 0..GuardianRunStateReader.MAX_HEARTBEAT_AGE_MS) {
            return Evaluation(State(), Decision.WAIT_UNVERIFIED)
        }
        if (!evidence.runningIntent || !evidence.working) return Evaluation(State(), Decision.WAIT_NOT_RUNNING)
        if (evidence.paused) return Evaluation(State(), Decision.WAIT_PAUSED)
        // Queue/game latches describe an existing client session. When the
        // exact client process is absent they can be stale; after the bounded
        // missing-process grace the configured starter chain is safe because
        // it launches a client and never terminates or cancels a live queue.
        if (evidence.gameAlive) {
            return Evaluation(State(), Decision.WAIT_ACTIVE_OR_TERMINAL)
        }

        val missingSince = state.missingSinceMs.takeIf { it > 0L } ?: evidence.nowMs
        val activeState = state.copy(missingSinceMs = missingSince)
        if (evidence.startupChainActive) return Evaluation(activeState, Decision.WAIT_STARTUP_CHAIN)
        if (evidence.nowMs - missingSince < MISSING_GAME_GRACE_MS) {
            return Evaluation(activeState, Decision.WAIT_MISSING_GAME_GRACE)
        }
        if (activeState.attempts >= MAX_STARTER_CHAIN_ATTEMPTS) {
            return Evaluation(activeState, Decision.EXHAUSTED)
        }
        if (activeState.lastAttemptAtMs > 0L &&
            evidence.nowMs - activeState.lastAttemptAtMs < RETRY_COOLDOWN_MS
        ) {
            return Evaluation(activeState, Decision.WAIT_RETRY_COOLDOWN)
        }
        return Evaluation(activeState, Decision.START_CONFIGURED_STARTER_CHAIN)
    }

    fun recordDispatch(state: State, nowMs: Long): State = state.copy(
        missingSinceMs = state.missingSinceMs.takeIf { it > 0L } ?: nowMs,
        attempts = state.attempts + 1,
        lastAttemptAtMs = nowMs,
    )
}

/** Side-effect boundary so tests can distinguish a policy decision from starter-chain dispatch. */
internal object LiveMissingGameRecoveryDispatch {
    enum class Result { NO_ACTION, BLOCKED, STARTER_CHAIN_DISPATCHED }

    fun dispatch(
        decision: LiveMissingGameRecoveryPolicy.Decision,
        stillAuthorized: () -> Boolean,
        recoverModeToStartup: () -> Unit,
        startConfiguredStarterChain: () -> Unit,
    ): Result {
        if (decision != LiveMissingGameRecoveryPolicy.Decision.START_CONFIGURED_STARTER_CHAIN) {
            return Result.NO_ACTION
        }
        if (!stillAuthorized()) return Result.BLOCKED
        recoverModeToStartup()
        if (!stillAuthorized()) return Result.BLOCKED
        startConfiguredStarterChain()
        return Result.STARTER_CHAIN_DISPATCHED
    }
}
