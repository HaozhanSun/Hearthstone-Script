package club.xiaojiawei.hsscript.status

/** Limits recovery to one accepted input per exact reward stage and client window. */
internal class SeasonRankAwardRecoveryAttemptGate {
    private data class ClientStage(val pid: Long, val hwnd: String, val stage: String)
    private val inFlight = mutableSetOf<ClientStage>()
    private val completed = mutableSetOf<ClientStage>()

    @Synchronized
    fun tryBegin(pid: Long, hwnd: String, stage: String): Boolean {
        if (pid <= 0L || hwnd.isBlank() || stage !in STAGES) return false
        val key = ClientStage(pid, hwnd, stage)
        if (key in completed || key in inFlight) return false
        inFlight += key
        return true
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
        )
    }
}
