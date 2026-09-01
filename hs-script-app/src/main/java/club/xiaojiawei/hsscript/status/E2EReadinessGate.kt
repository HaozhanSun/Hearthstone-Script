package club.xiaojiawei.hsscript.status

/**
 * Readiness contract for a supervised E2E run.
 *
 * A Power.log file existing is not evidence that this JVM owns a fresh game
 * session.  The gate starts at the current file offset and only becomes ready
 * after a CREATE_GAME line is observed after that offset.  This keeps a
 * watchdog restart from treating an old gameplay tail as a new run.
 */
internal class E2EReadinessGate(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    enum class State {
        UNINITIALIZED,
        WAITING_FOR_CREATE_GAME,
        READY,
        BLOCKED,
    }

    data class Session(
        val runId: String,
        val processId: Long,
        val logPath: String,
        val baselineOffset: Long,
        val startedAtMs: Long,
    )

    data class Decision(
        val state: State,
        val reason: String,
        val waitedMs: Long = 0L,
    )

    @Volatile
    private var session: Session? = null

    @Volatile
    private var state: State = State.UNINITIALIZED

    @Synchronized
    fun begin(
        runId: String,
        processId: Long,
        logPath: String,
        baselineOffset: Long,
        nowMs: Long,
    ) {
        session = Session(
            runId = runId,
            processId = processId,
            logPath = logPath,
            baselineOffset = baselineOffset.coerceAtLeast(0L),
            startedAtMs = nowMs,
        )
        state = State.WAITING_FOR_CREATE_GAME
    }

    /**
     * Return true only for a line consumed after this session's baseline.
     * The run and process identity are checked as an extra fence for callers
     * that multiplex several supervised attempts in one JVM/test fixture.
     */
    @Synchronized
    fun observeLine(
        runId: String,
        processId: Long,
        absolutePosition: Long,
        line: String,
    ): Boolean {
        val current = session ?: return false
        if (state != State.WAITING_FOR_CREATE_GAME ||
            current.runId != runId ||
            current.processId != processId ||
            absolutePosition <= current.baselineOffset ||
            !line.contains("CREATE_GAME")
        ) {
            return false
        }
        state = State.READY
        return true
    }

    @Synchronized
    fun evaluate(runId: String, processId: Long, nowMs: Long): Decision {
        val current = session ?: return Decision(
            State.UNINITIALIZED,
            "power-log-not-attached",
        )
        if (current.runId != runId || current.processId != processId) {
            return Decision(
                State.BLOCKED,
                "session-identity-mismatch",
            )
        }
        if (state == State.READY) {
            return Decision(State.READY, "fresh-create-game-observed")
        }
        if (state == State.BLOCKED) {
            return Decision(State.BLOCKED, "fresh-create-game-not-observed")
        }

        val waitedMs = (nowMs - current.startedAtMs).coerceAtLeast(0L)
        if (waitedMs >= timeoutMs) {
            state = State.BLOCKED
            return Decision(
                State.BLOCKED,
                if (current.baselineOffset > 0L) {
                    "stale-existing-log-rejected-and-no-fresh-create-game"
                } else {
                    "fresh-power-log-empty-and-no-create-game"
                },
                waitedMs,
            )
        }
        return Decision(
            State.WAITING_FOR_CREATE_GAME,
            if (current.baselineOffset > 0L) {
                "existing-log-tail-rejected-awaiting-fresh-create-game"
            } else {
                "fresh-power-log-awaiting-create-game"
            },
            waitedMs,
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 45_000L
    }
}
