package club.xiaojiawei.hsscript.status

/** Limits recovery to one accepted input per exact reward stage and client window. */
internal class SeasonRankAwardRecoveryAttemptGate {
    data class BeginResult(val allowed: Boolean, val reason: String)

    private data class ClientStage(val pid: Long, val hwnd: String, val stage: String)
    private val inFlight = mutableSetOf<ClientStage>()
    private val completed = mutableSetOf<ClientStage>()

    @Synchronized
    fun tryBegin(pid: Long, hwnd: String, stage: String): Boolean {
        return tryBeginDetailed(pid, hwnd, stage).allowed
    }

    @Synchronized
    fun tryBeginDetailed(pid: Long?, hwnd: String?, stage: String): BeginResult {
        if (pid == null || pid <= 0L) return BeginResult(false, "process-unavailable")
        if (hwnd.isNullOrBlank()) return BeginResult(false, "window-unavailable")
        if (stage !in STAGES) return BeginResult(false, "unsupported-stage")
        val key = ClientStage(pid, hwnd, stage)
        if (key in inFlight) return BeginResult(false, "stage-in-flight")
        if (key in completed) return BeginResult(false, "stage-already-accepted")
        inFlight += key
        return BeginResult(true, "eligible")
    }

    @Synchronized
    fun resolve(pid: Long, hwnd: String, stage: String, accepted: Boolean) {
        val key = ClientStage(pid, hwnd, stage)
        inFlight -= key
        if (accepted) completed += key
    }

    @Synchronized
    fun clearForFreshHubChooser(pid: Long, hwnd: String) {
        completed.removeIf { it.pid == pid && it.hwnd == hwnd }
        inFlight.removeIf { it.pid == pid && it.hwnd == hwnd }
    }

    private companion object {
        val STAGES = setOf(
            SeasonRankAwardRecoveryPolicy.DISPATCH_ACTION,
            SeasonRankAwardRecoveryPolicy.OPEN_CHEST_ACTION,
            SeasonRankAwardRecoveryPolicy.REWARDS_CONTINUE_ACTION,
        )
    }
}
