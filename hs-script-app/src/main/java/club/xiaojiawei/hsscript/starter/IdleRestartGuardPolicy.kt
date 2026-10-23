package club.xiaojiawei.hsscript.starter

/**
 * Decides whether the legacy idle restart may restart the client.
 *
 * Power.log is allowed to be empty while Hearthstone is visibly searching for
 * an opponent. Keep that visual evidence ahead of the log's activity clock.
 * Unknown or untrusted capture results are fail-safe: they never authorize a
 * destructive recovery after a retry budget is exhausted.
 */
internal object IdleRestartGuardPolicy {

    const val MAX_UNCERTAIN_PROBES = 3

    enum class Decision {
        NOT_IDLE,
        DEFER_ACTIVE_MATCHMAKING,
        DEFER_UNCERTAIN_SCREEN,
        RESTART,
    }

    fun evaluate(
        idleForMs: Long,
        idleLimitMs: Long,
        observedScreen: String?,
        uncertainProbeAttempts: Int,
        revalidatedScreen: String? = null,
    ): Decision {
        if (idleForMs <= idleLimitMs) return Decision.NOT_IDLE
        val finalScreen = revalidatedScreen ?: observedScreen
        if (finalScreen.equals("MATCHMAKING", ignoreCase = true)) {
            return Decision.DEFER_ACTIVE_MATCHMAKING
        }
        if (isUncertainScreen(finalScreen)) {
            return Decision.DEFER_UNCERTAIN_SCREEN
        }
        if (!finalScreen.equals("HOME", ignoreCase = true)) {
            return Decision.DEFER_UNCERTAIN_SCREEN
        }
        if (revalidatedScreen == null || !observedScreen.equals("HOME", ignoreCase = true)) {
            return Decision.DEFER_UNCERTAIN_SCREEN
        }
        return Decision.RESTART
    }

    private fun isUncertainScreen(screen: String?): Boolean {
        return screen == null || screen.equals("UNKNOWN", ignoreCase = true) ||
            screen.equals("RECONNECT", ignoreCase = true) ||
            screen.equals("RECONNECT_FAILURE", ignoreCase = true) ||
            screen.equals("RECONNECT_SPINNER", ignoreCase = true) ||
            screen.equals("DIALOG", ignoreCase = true)
    }
}
