package club.xiaojiawei.hsscript.status

/**
 * Tracks a positively observed, PID-bound matchmaking queue across the gap
 * before Hearthstone creates the next Power.log game segment.
 *
 * Initiating a queue click is deliberately not enough to activate this state;
 * callers must provide a fresh, authorized MATCHMAKING screen observation.
 */
class MatchmakingQueueLifecycle {
    enum class Phase { IDLE, QUEUE_PENDING, GAME_CREATED, QUEUE_TERMINAL, QUEUE_EXPIRED }
    enum class ScreenEvidence { MATCHMAKING, QUEUE_TERMINAL, UNKNOWN }
    enum class TimeoutDisposition { RETRY, DEFER_WITHOUT_INPUT, STOP }

    data class Snapshot(
        val phase: Phase,
        val processId: Long?,
        val startedAtNanos: Long?,
        val lastEvidenceAtNanos: Long?,
    ) {
        val isPending: Boolean get() = phase == Phase.QUEUE_PENDING
    }

    @Volatile
    private var current = Snapshot(Phase.IDLE, null, null, null)

    @Synchronized
    fun observeScreen(
        observedPid: Long?,
        currentPid: Long?,
        evidence: ScreenEvidence,
        confidence: Int,
        captureAuthorized: Boolean,
        processAlive: Boolean,
        nowNanos: Long,
    ): Snapshot {
        expireIfNeeded(nowNanos)
        if (!processAlive || !captureAuthorized || observedPid == null || observedPid <= 0L || observedPid != currentPid) {
            return snapshotForLocked(currentPid, processAlive, nowNanos)
        }
        return when (evidence) {
            ScreenEvidence.MATCHMAKING -> {
                if (confidence < MIN_MATCHMAKING_CONFIDENCE) current
                else if (current.phase == Phase.QUEUE_EXPIRED && current.processId == observedPid) current
                else if (current.phase == Phase.QUEUE_PENDING && current.processId == observedPid) {
                    // Refresh evidence freshness, never the original hard deadline.
                    current.copy(lastEvidenceAtNanos = nowNanos).also { current = it }
                } else Snapshot(Phase.QUEUE_PENDING, observedPid, nowNanos, nowNanos).also { current = it }
            }
            ScreenEvidence.QUEUE_TERMINAL -> {
                if (confidence >= MIN_MATCHMAKING_CONFIDENCE &&
                    current.phase in setOf(Phase.QUEUE_PENDING, Phase.QUEUE_EXPIRED) &&
                    current.processId == observedPid
                ) {
                    Snapshot(Phase.QUEUE_TERMINAL, observedPid, current.startedAtNanos, nowNanos).also { current = it }
                } else current
            }
            ScreenEvidence.UNKNOWN -> current
        }
    }

    @Synchronized
    fun observeGameCreated(processId: Long?): Snapshot {
        if (processId != null && processId > 0L && current.phase == Phase.QUEUE_PENDING &&
            current.processId == processId
        ) {
            current = current.copy(phase = Phase.GAME_CREATED)
        }
        return current
    }

    @Synchronized
    fun snapshotFor(processId: Long?, processAlive: Boolean, nowNanos: Long): Snapshot {
        expireIfNeeded(nowNanos)
        if (!processAlive && processId != null && current.processId == processId &&
            current.phase == Phase.QUEUE_PENDING
        ) {
            current = Snapshot(Phase.IDLE, null, null, null)
        }
        return snapshotForLocked(processId, processAlive, nowNanos)
    }

    @Synchronized
    fun timeoutDisposition(processId: Long?, processAlive: Boolean, nowNanos: Long): TimeoutDisposition =
        when (snapshotFor(processId, processAlive, nowNanos).phase) {
            Phase.QUEUE_PENDING -> TimeoutDisposition.DEFER_WITHOUT_INPUT
            Phase.GAME_CREATED, Phase.QUEUE_TERMINAL, Phase.QUEUE_EXPIRED -> TimeoutDisposition.STOP
            Phase.IDLE -> TimeoutDisposition.RETRY
        }

    @Synchronized
    fun resetForTest() {
        current = Snapshot(Phase.IDLE, null, null, null)
    }

    private fun snapshotForLocked(processId: Long?, processAlive: Boolean, nowNanos: Long): Snapshot =
        if (processAlive && processId != null && current.processId == processId) current
        else Snapshot(Phase.IDLE, null, null, null)

    private fun expireIfNeeded(nowNanos: Long) {
        if (current.phase != Phase.QUEUE_PENDING) return
        val startedAt = current.startedAtNanos ?: return
        val lastEvidenceAt = current.lastEvidenceAtNanos ?: return
        val queueAge = (nowNanos - startedAt).coerceAtLeast(0L)
        val evidenceAge = (nowNanos - lastEvidenceAt).coerceAtLeast(0L)
        if (queueAge >= MAX_QUEUE_PENDING_NANOS || evidenceAge >= MAX_EVIDENCE_AGE_NANOS) {
            current = current.copy(phase = Phase.QUEUE_EXPIRED)
        }
    }

    companion object {
        const val MIN_MATCHMAKING_CONFIDENCE = 85
        const val MAX_QUEUE_PENDING_NANOS = 180_000_000_000L
        const val MAX_EVIDENCE_AGE_NANOS = 30_000_000_000L
    }
}
