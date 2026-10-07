package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscript.utils.GameUtil

/** Process-local bridge between verified screen observations and startup/no-progress recovery. */
internal object StartupMatchmakingQueueState {
    private val lifecycle = MatchmakingQueueLifecycle()

    fun observeVerifiedQueueModal(
        pid: Long?,
        searchPanelRedRatio: Double,
        cancelButtonWarmRatio: Double,
        nowNanos: Long = System.nanoTime(),
    ): MatchmakingQueueLifecycle.Snapshot {
        val snapshot = observeScreen(
            screen = "MATCHMAKING",
            confidence = 95,
            observedPid = pid,
            currentPid = pid,
            captureAuthorized = true,
            nowNanos = nowNanos,
        )
        if (snapshot.isPending) {
            log.info {
                "MATCHMAKING_QUEUE_VISUAL_CONFIRMED pid=$pid " +
                    "searchPanelRedRatio=${"%.3f".format(java.util.Locale.ROOT, searchPanelRedRatio)} " +
                    "cancelButtonWarmRatio=${"%.3f".format(java.util.Locale.ROOT, cancelButtonWarmRatio)} " +
                    "phase=${snapshot.phase} actionInput=none"
            }
        }
        return snapshot
    }

    fun observeScreen(
        screen: String?,
        confidence: Int,
        observedPid: Long?,
        currentPid: Long?,
        captureAuthorized: Boolean,
        nowNanos: Long = System.nanoTime(),
    ): MatchmakingQueueLifecycle.Snapshot {
        val evidence = when (screen) {
            "MATCHMAKING" -> MatchmakingQueueLifecycle.ScreenEvidence.MATCHMAKING
            // These are positive, fresh screen classifications proving the
            // client left queue search (including manual cancellation).
            "HOME", "HOME_TASK_OVERLAY", "TOURNAMENT", "DECK_SELECTION", "GAME_MODE", "LOGIN" ->
                MatchmakingQueueLifecycle.ScreenEvidence.QUEUE_TERMINAL
            else -> MatchmakingQueueLifecycle.ScreenEvidence.UNKNOWN
        }
        val processAlive = currentPid != null &&
            GameUtil.findGameProcessIdForDiagnostics() == currentPid && GameUtil.isAliveOfGame()
        val before = lifecycle.snapshotFor(currentPid, processAlive, nowNanos)
        val after = lifecycle.observeScreen(
            observedPid = observedPid,
            currentPid = currentPid,
            evidence = evidence,
            confidence = confidence,
            captureAuthorized = captureAuthorized,
            processAlive = processAlive,
            nowNanos = nowNanos,
        )
        if (before.phase != after.phase || before.processId != after.processId) {
            log.info {
                "MATCHMAKING_QUEUE_LIFECYCLE phase=${after.phase} screen=${screen ?: "UNKNOWN"} " +
                    "confidence=$confidence pid=${after.processId ?: currentPid ?: "none"} " +
                    "reason=${if (after.phase == MatchmakingQueueLifecycle.Phase.QUEUE_PENDING) "verified-matchmaking-screen" else "verified-queue-exit-screen"}"
            }
        }
        return after
    }

    fun observeCurrentPowerLog(pid: Long?, evidence: PowerLogActiveMatchProbe.Evidence) {
        if (evidence.gameCreated && pid != null) {
            val before = lifecycle.snapshotFor(pid, processAlive = true, nowNanos = System.nanoTime())
            val after = lifecycle.observeGameCreated(pid)
            if (before.phase == MatchmakingQueueLifecycle.Phase.QUEUE_PENDING &&
                after.phase == MatchmakingQueueLifecycle.Phase.GAME_CREATED
            ) {
                log.info {
                    "MATCHMAKING_QUEUE_LIFECYCLE phase=GAME_CREATED pid=$pid " +
                        "powerLogReason=${evidence.reason} powerLogLength=${evidence.fileLength}"
                }
            }
        }
    }

    fun observeGameCreated(pid: Long?) {
        if (pid == null) return
        val before = lifecycle.snapshotFor(pid, processAlive = true, nowNanos = System.nanoTime())
        val after = lifecycle.observeGameCreated(pid)
        if (before.phase == MatchmakingQueueLifecycle.Phase.QUEUE_PENDING &&
            after.phase == MatchmakingQueueLifecycle.Phase.GAME_CREATED
        ) {
            log.info { "MATCHMAKING_QUEUE_LIFECYCLE phase=GAME_CREATED pid=$pid evidence=authoritative-active-game" }
        }
    }

    fun observeQueueTerminal(pid: Long?, reason: String) {
        if (pid == null) return
        val before = lifecycle.snapshotFor(pid, processAlive = true, nowNanos = System.nanoTime())
        val after = lifecycle.observeScreen(
            observedPid = pid,
            currentPid = pid,
            evidence = MatchmakingQueueLifecycle.ScreenEvidence.QUEUE_TERMINAL,
            confidence = MatchmakingQueueLifecycle.MIN_MATCHMAKING_CONFIDENCE,
            captureAuthorized = true,
            processAlive = pid == GameUtil.findGameProcessIdForDiagnostics() && GameUtil.isAliveOfGame(),
            nowNanos = System.nanoTime(),
        )
        if (before.phase == MatchmakingQueueLifecycle.Phase.QUEUE_PENDING &&
            after.phase == MatchmakingQueueLifecycle.Phase.QUEUE_TERMINAL
        ) {
            log.info { "MATCHMAKING_QUEUE_LIFECYCLE phase=QUEUE_TERMINAL pid=$pid reason=$reason" }
        }
    }

    fun snapshotFor(pid: Long?, processAlive: Boolean): MatchmakingQueueLifecycle.Snapshot {
        val sameLiveProcess = processAlive && pid != null &&
            GameUtil.findGameProcessIdForDiagnostics() == pid && GameUtil.isAliveOfGame()
        return lifecycle.snapshotFor(pid, sameLiveProcess, System.nanoTime())
    }

    fun isPendingFor(pid: Long?, processAlive: Boolean): Boolean = snapshotFor(pid, processAlive).isPending

    fun isExpiredFor(pid: Long?, processAlive: Boolean): Boolean =
        snapshotFor(pid, processAlive).phase == MatchmakingQueueLifecycle.Phase.QUEUE_EXPIRED

    fun timeoutDisposition(pid: Long?, processAlive: Boolean): MatchmakingQueueLifecycle.TimeoutDisposition =
        lifecycle.timeoutDisposition(pid, processAlive, System.nanoTime())
}
