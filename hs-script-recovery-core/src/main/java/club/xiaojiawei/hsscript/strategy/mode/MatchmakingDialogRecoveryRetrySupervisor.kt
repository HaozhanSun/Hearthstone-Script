package club.xiaojiawei.hsscript.strategy.mode

/** Bounded, non-pausing cooldown after an exhausted exact-dialog probe budget. */
class MatchmakingDialogRecoveryRetrySupervisor(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    enum class Action { ATTEMPT_ALLOWED, WAIT_COOLDOWN, REARMED }

    data class Decision(
        val action: Action,
        val reason: String,
        val retryAfterMs: Long,
        val clickAllowed: Boolean = false,
        val pauseRequested: Boolean = false,
    )

    private var retryAtMs: Long? = null

    fun beginCooldown(nowMs: Long): Decision {
        retryAtMs = nowMs + cooldownMs.coerceAtLeast(1L)
        return Decision(
            action = Action.WAIT_COOLDOWN,
            reason = "attempt-budget-exhausted",
            retryAfterMs = retryAtMs!!,
        )
    }

    fun observe(nowMs: Long, probe: MatchmakingDialogRecoveryPolicy.Probe): Decision {
        val retryAt = retryAtMs ?: return Decision(
            action = Action.ATTEMPT_ALLOWED,
            reason = "no-cooldown",
            retryAfterMs = 0L,
            clickAllowed = true,
        )
        if (nowMs < retryAt) {
            return Decision(Action.WAIT_COOLDOWN, "cooldown-active", retryAt)
        }
        if (probe != MatchmakingDialogRecoveryPolicy.Probe.ERROR_DIALOG_VISIBLE) {
            retryAtMs = nowMs + cooldownMs.coerceAtLeast(1L)
            return Decision(
                Action.WAIT_COOLDOWN,
                "fresh-exact-dialog-evidence-required",
                retryAtMs!!,
            )
        }
        retryAtMs = null
        return Decision(
            action = Action.REARMED,
            reason = "fresh-exact-dialog-evidence",
            retryAfterMs = 0L,
        )
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 30_000L
    }
}
