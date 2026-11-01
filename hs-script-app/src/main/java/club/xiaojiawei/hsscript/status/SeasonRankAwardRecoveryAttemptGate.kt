package club.xiaojiawei.hsscript.status

/** Bounds accepted inputs per exact reward stage and client window; only observed screens prove transition. */
internal class SeasonRankAwardRecoveryAttemptGate {
    data class BeginResult(
        val allowed: Boolean,
        val reason: String,
        val priorAcceptedAttempts: Int = 0,
        val retryAfterMs: Long? = null,
    )

    private data class ClientStage(val pid: Long, val hwnd: String, val stage: String)
    private val inFlight = mutableSetOf<ClientStage>()
    private val acceptedAttempts = mutableMapOf<ClientStage, Int>()
    private val lastAcceptedAtMs = mutableMapOf<ClientStage, Long>()

    companion object {
        private const val REWARDS_CONTINUE_RETRY_DELAY_MS = 30_000L
        private const val REWARDS_CONTINUE_MAX_ACCEPTED_ATTEMPTS = 3
        private val STAGES = setOf(
            SeasonRankAwardRecoveryPolicy.DISPATCH_ACTION,
            SeasonRankAwardRecoveryPolicy.OPEN_CHEST_ACTION,
            SeasonRankAwardRecoveryPolicy.REWARDS_CONTINUE_ACTION,
        )

        fun hasFreshAuthorizedRewardsScreen(
            screenKind: String,
            evidence: String,
            freshCaptureAuthorized: Boolean,
            exactClientWindowVerified: Boolean,
            foregroundVerified: Boolean,
        ): Boolean = screenKind == "SEASON_RANK_AWARD_REWARDS" &&
            evidence == "season-rank-award-rewards-five-chests-and-labeled-continue-structural-visual" &&
            freshCaptureAuthorized && exactClientWindowVerified && foregroundVerified
    }

    @Synchronized
    fun tryBegin(pid: Long, hwnd: String, stage: String): Boolean {
        return tryBeginDetailed(pid, hwnd, stage).allowed
    }

    @Synchronized
    fun tryBeginDetailed(
        pid: Long?,
        hwnd: String?,
        stage: String,
        nowMs: Long = System.currentTimeMillis(),
        freshExactScreenObservation: Boolean = false,
    ): BeginResult {
        if (pid == null || pid <= 0L) return BeginResult(false, "process-unavailable")
        if (hwnd.isNullOrBlank()) return BeginResult(false, "window-unavailable")
        if (stage !in STAGES) return BeginResult(false, "unsupported-stage")
        val key = ClientStage(pid, hwnd, stage)
        if (key in inFlight) return BeginResult(false, "stage-in-flight")
        val priorAccepted = acceptedAttempts[key] ?: 0
        if (priorAccepted > 0) {
            if (stage != SeasonRankAwardRecoveryPolicy.REWARDS_CONTINUE_ACTION) {
                return BeginResult(false, "stage-already-accepted", priorAccepted)
            }
            if (priorAccepted >= REWARDS_CONTINUE_MAX_ACCEPTED_ATTEMPTS) {
                return BeginResult(false, "stage-attempt-limit", priorAccepted)
            }
            if (!freshExactScreenObservation) {
                return BeginResult(false, "fresh-exact-screen-required", priorAccepted)
            }
            val lastAcceptedAt = lastAcceptedAtMs[key] ?: nowMs
            val elapsed = (nowMs - lastAcceptedAt).coerceAtLeast(0L)
            val retryAfter = (REWARDS_CONTINUE_RETRY_DELAY_MS - elapsed).coerceAtLeast(0L)
            if (retryAfter > 0L) {
                return BeginResult(false, "accepted-reprobe-delay", priorAccepted, retryAfter)
            }
        }
        inFlight += key
        return BeginResult(
            allowed = true,
            reason = if (priorAccepted == 0) "eligible" else "fresh-screen-retry-eligible",
            priorAcceptedAttempts = priorAccepted,
        )
    }

    @Synchronized
    fun resolve(
        pid: Long,
        hwnd: String,
        stage: String,
        accepted: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val key = ClientStage(pid, hwnd, stage)
        inFlight -= key
        if (accepted) {
            acceptedAttempts[key] = (acceptedAttempts[key] ?: 0) + 1
            lastAcceptedAtMs[key] = nowMs
        }
    }

    @Synchronized
    fun clearForFreshHubChooser(pid: Long, hwnd: String) {
        acceptedAttempts.keys.removeIf { it.pid == pid && it.hwnd == hwnd }
        lastAcceptedAtMs.keys.removeIf { it.pid == pid && it.hwnd == hwnd }
        inFlight.removeIf { it.pid == pid && it.hwnd == hwnd }
    }

}
