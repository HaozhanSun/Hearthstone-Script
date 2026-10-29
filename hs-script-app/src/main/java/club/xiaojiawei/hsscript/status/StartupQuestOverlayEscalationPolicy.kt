package club.xiaojiawei.hsscript.status

/** Restart only a persistently verified startup quest modal after normal dismissal retries are exhausted. */
internal class StartupQuestOverlayEscalationPolicy(
    private val stuckThresholdMs: Long = DEFAULT_STUCK_THRESHOLD_MS,
    private val restartCooldownMs: Long = DEFAULT_RESTART_COOLDOWN_MS,
    private val maxRestarts: Int = DEFAULT_MAX_RESTARTS,
) {
    enum class Action { WAIT, RESTART_CLIENT, ALERT_OPERATOR, ALREADY_ALERTED }

    data class Evidence(
        val nowMs: Long,
        val pid: Long?,
        val window: String?,
        val exactTrustedQuestOverlay: Boolean,
        val tapsAttempted: Int,
        val observations: Int,
        val noActiveQueue: Boolean,
        val noActiveGameOrTerminal: Boolean,
        val working: Boolean,
        val paused: Boolean,
    )

    data class Decision(val action: Action, val reason: String, val restartAttempt: Int)

    private var session: Pair<Long, String>? = null
    private var firstExactObservationAt = 0L
    private var lastRestartAt = 0L
    private var restartAttempts = 0
    private var alerted = false

    @Synchronized
    fun observe(evidence: Evidence): Decision {
        val identity = evidence.pid?.takeIf { it > 0L }?.let { pid ->
            evidence.window?.takeIf(String::isNotBlank)?.let { window -> pid to window }
        }
        if (!evidence.exactTrustedQuestOverlay || identity == null || evidence.nowMs <= 0L) {
            return Decision(Action.WAIT, "exact-trusted-current-session-required", restartAttempts)
        }
        if (session != identity) {
            session = identity
            firstExactObservationAt = evidence.nowMs
            lastRestartAt = 0L
            restartAttempts = 0
            alerted = false
        }
        val guard = when {
            evidence.tapsAttempted < StartupQuestOverlayDismissPolicy.MAX_TAPS -> "dismiss-tap-budget-not-exhausted"
            evidence.observations < StartupQuestOverlayRecoveryFence.MAX_REOBSERVATIONS -> "probe-budget-not-exhausted"
            !evidence.noActiveQueue -> "active-queue"
            !evidence.noActiveGameOrTerminal -> "active-game-or-terminal"
            evidence.paused -> "paused-by-user-or-operator"
            !evidence.working -> "script-not-working"
            evidence.nowMs - firstExactObservationAt < stuckThresholdMs -> "stuck-threshold-not-reached"
            else -> null
        }
        if (guard != null) return Decision(Action.WAIT, guard, restartAttempts)
        if (restartAttempts >= maxRestarts) {
            if (!alerted) {
                alerted = true
                return Decision(Action.ALERT_OPERATOR, "bounded-restart-budget-exhausted", restartAttempts)
            }
            return Decision(Action.ALREADY_ALERTED, "bounded-restart-budget-exhausted", restartAttempts)
        }
        if (lastRestartAt > 0L && evidence.nowMs - lastRestartAt < restartCooldownMs) {
            return Decision(Action.WAIT, "restart-cooldown", restartAttempts)
        }
        restartAttempts++
        lastRestartAt = evidence.nowMs
        return Decision(Action.RESTART_CLIENT, "verified-overlay-stuck-after-bounded-dismissal", restartAttempts)
    }

    @Synchronized
    fun reset() {
        session = null
        firstExactObservationAt = 0L
        lastRestartAt = 0L
        restartAttempts = 0
        alerted = false
    }

    companion object {
        const val DEFAULT_STUCK_THRESHOLD_MS = 60_000L
        const val DEFAULT_RESTART_COOLDOWN_MS = 120_000L
        const val DEFAULT_MAX_RESTARTS = 2
    }
}
