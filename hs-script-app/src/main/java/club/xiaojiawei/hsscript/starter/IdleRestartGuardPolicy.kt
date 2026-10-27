package club.xiaojiawei.hsscript.starter

/**
 * Decides whether the legacy idle restart may restart the client.
 *
 * Power.log is allowed to be empty while Hearthstone is visibly searching for
 * an opponent. Keep that visual evidence ahead of the log's activity clock.
 * Unknown screens never authorize an immediate restart. Once the bounded
 * probe budget expires, the caller may restart only when no active game or
 * matchmaking screen is recognized.
 */
internal object IdleRestartGuardPolicy {

    const val MAX_UNCERTAIN_PROBES = 3
    const val SERVER_UNAVAILABLE_STABILITY_MS = 60_000L
    const val SERVER_UNAVAILABLE_RESTART_COOLDOWN_MS = 300_000L
    const val SERVER_UNAVAILABLE_MAX_RESTARTS = 3
    const val SERVER_UNAVAILABLE_RESTART_ACTION = "RESTART_HEARTHSTONE_NO_INPUT_NO_AUTO_PAUSE"
    const val UNKNOWN_SCREEN_STABILITY_MS = 60_000L
    const val UNKNOWN_SCREEN_MAX_PERCEPTUAL_DISTANCE = 2

    data class ScreenIdentity(val screen: String, val pid: Long, val hwnd: String)
    data class UnknownScreenIdentity(
        val screen: String,
        val pid: Long,
        val hwnd: String,
        val perceptualFingerprint: Long,
    )

    enum class UnknownScreenDecision {
        RESET_STABILITY,
        WAIT_FOR_STABILITY,
        RESTART_CLIENT,
        DEFER_ACTIVE_SESSION,
        DEFER_ACTIVE_MATCHMAKING,
        DEFER_UNVERIFIED_MATCHMAKING,
    }

    enum class ServerUnavailableDecision {
        WAIT_FOR_STABILITY,
        RESTART_CLIENT,
        ALERT_OPERATOR,
        DEFER_ACTIVE_SESSION,
        DEFER_ACTIVE_MATCHMAKING,
    }

    enum class Decision {
        NOT_IDLE,
        DEFER_ACTIVE_MATCHMAKING,
        DEFER_UNCERTAIN_SCREEN,
        RESTART_UNCERTAIN_SCREEN,
        ALERT_UNCERTAIN_SCREEN,
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
            return uncertainDecision(finalScreen, uncertainProbeAttempts)
        }
        if (!finalScreen.equals("HOME", ignoreCase = true)) {
            return uncertainDecision(finalScreen, uncertainProbeAttempts)
        }
        if (revalidatedScreen == null || !observedScreen.equals("HOME", ignoreCase = true)) {
            return Decision.DEFER_UNCERTAIN_SCREEN
        }
        return Decision.RESTART
    }

    private fun uncertainDecision(screen: String?, probes: Int): Decision = when {
        probes < MAX_UNCERTAIN_PROBES -> Decision.DEFER_UNCERTAIN_SCREEN
        screen.equals("RECONNECT", ignoreCase = true) ||
            screen.equals("RECONNECT_FAILURE", ignoreCase = true) ||
            screen.equals("RECONNECT_SPINNER", ignoreCase = true) -> Decision.RESTART_UNCERTAIN_SCREEN
        else -> Decision.ALERT_UNCERTAIN_SCREEN
    }

    fun mayRestartUncertainScreen(activeGame: Boolean, terminal: Boolean): Boolean =
        !activeGame && !terminal

    fun evaluateServerUnavailable(
        first: ScreenIdentity?,
        current: ScreenIdentity?,
        stableForMs: Long,
        currentSessionReady: Boolean,
        activeGame: Boolean = false,
        terminal: Boolean = false,
        noActiveMatchmakingEvidence: Boolean = true,
        nowMs: Long = 0L,
        lastRestartAtMs: Long = 0L,
        restartCount: Int = 0,
    ): ServerUnavailableDecision {
        if (current?.screen.equals("MATCHMAKING", ignoreCase = true) || !noActiveMatchmakingEvidence) {
            return ServerUnavailableDecision.DEFER_ACTIVE_MATCHMAKING
        }
        if (currentSessionReady || activeGame || terminal) return ServerUnavailableDecision.DEFER_ACTIVE_SESSION
        if (restartCount >= SERVER_UNAVAILABLE_MAX_RESTARTS) return ServerUnavailableDecision.ALERT_OPERATOR
        if (lastRestartAtMs > 0L && nowMs - lastRestartAtMs < SERVER_UNAVAILABLE_RESTART_COOLDOWN_MS) {
            return ServerUnavailableDecision.WAIT_FOR_STABILITY
        }
        val sameVerifiedPage = first != null && current != null &&
            first.screen.equals("SERVER_UNAVAILABLE", ignoreCase = true) &&
            current.screen.equals("SERVER_UNAVAILABLE", ignoreCase = true) &&
            first.pid == current.pid && first.hwnd == current.hwnd
        if (!sameVerifiedPage || stableForMs < SERVER_UNAVAILABLE_STABILITY_MS) {
            return ServerUnavailableDecision.WAIT_FOR_STABILITY
        }
        return ServerUnavailableDecision.RESTART_CLIENT
    }

    fun evaluateUnknownScreen(
        first: UnknownScreenIdentity?,
        current: UnknownScreenIdentity?,
        stableForMs: Long,
        currentSessionReady: Boolean,
        activeGame: Boolean,
        terminal: Boolean,
        noActiveMatchmakingEvidence: Boolean,
        nowMs: Long = 0L,
        lastRestartAtMs: Long = 0L,
    ): UnknownScreenDecision {
        if (current?.screen.equals("MATCHMAKING", ignoreCase = true)) {
            return UnknownScreenDecision.DEFER_ACTIVE_MATCHMAKING
        }
        if (currentSessionReady || activeGame || terminal) {
            return UnknownScreenDecision.DEFER_ACTIVE_SESSION
        }
        if (!noActiveMatchmakingEvidence) {
            return UnknownScreenDecision.DEFER_UNVERIFIED_MATCHMAKING
        }
        if (lastRestartAtMs > 0L && nowMs - lastRestartAtMs < SERVER_UNAVAILABLE_RESTART_COOLDOWN_MS) {
            return UnknownScreenDecision.WAIT_FOR_STABILITY
        }
        if (first == null || current == null ||
            !first.screen.equals("UNKNOWN", ignoreCase = true) ||
            !current.screen.equals("UNKNOWN", ignoreCase = true) ||
            first.pid != current.pid || first.hwnd != current.hwnd ||
            java.lang.Long.bitCount(first.perceptualFingerprint xor current.perceptualFingerprint) >
            UNKNOWN_SCREEN_MAX_PERCEPTUAL_DISTANCE
        ) {
            return UnknownScreenDecision.RESET_STABILITY
        }
        if (stableForMs < UNKNOWN_SCREEN_STABILITY_MS) return UnknownScreenDecision.WAIT_FOR_STABILITY
        return UnknownScreenDecision.RESTART_CLIENT
    }

    private fun isUncertainScreen(screen: String?): Boolean {
        return screen == null || screen.equals("UNKNOWN", ignoreCase = true) ||
            screen.equals("RECONNECT", ignoreCase = true) ||
            screen.equals("RECONNECT_FAILURE", ignoreCase = true) ||
            screen.equals("RECONNECT_SPINNER", ignoreCase = true) ||
            screen.equals("DIALOG", ignoreCase = true)
    }
}
