package club.xiaojiawei.hsscript.status

/**
 * Allows a read-only startup/menu observation before Power.log is ready.
 * This is deliberately not an action permit: gameplay, rank, queue, and
 * matchmaking callers must continue to require CurrentGameScreenReadinessPolicy.
 */
object StartupMenuObservationPolicy {
    // Startup progression requires a fresh frame after the bounded overlay
    // dispatch; keep observation retries bounded but don't delay that proof
    // for two minutes.
    const val CAPTURE_COOLDOWN_MS = 1_200L
    const val MAX_PASSIVE_REOBSERVATIONS = 30
    const val PASSIVE_REOBSERVATION_TIMEOUT_MS = 60_000L
    private val allowedModes = setOf("NONE", "STARTUP", "LOGIN", "HUB", "GAME_MODE")
    private val observableMenuScreens = setOf("HOME", "HOME_TASK_OVERLAY", "TOURNAMENT", "GAME_MODE", "LOGIN", "COLLECTION")
    const val MIN_MENU_CONFIDENCE = 85

    enum class Decision {
        AUTHORITATIVE_SESSION_READY,
        OBSERVE_STARTUP_MENU,
        WAIT_FOR_POWER_LOG,
        WAIT_EXPECTED_MENU,
        WAIT_FOR_TERMINAL_AUTHORITY,
        BOUNDED_SAFE_PAUSE,
    }

    fun decide(
        currentSessionReady: Boolean,
        startupObservationAuthorized: Boolean,
        observedMenu: Boolean,
        activeMatch: Boolean,
        terminal: Boolean,
        completedObservations: Int,
        maxObservations: Int,
    ): Decision = when {
        currentSessionReady -> Decision.AUTHORITATIVE_SESSION_READY
        activeMatch || terminal -> Decision.WAIT_FOR_TERMINAL_AUTHORITY
        observedMenu -> Decision.WAIT_EXPECTED_MENU
        !startupObservationAuthorized -> Decision.WAIT_FOR_POWER_LOG
        completedObservations < maxObservations.coerceAtLeast(0) -> Decision.OBSERVE_STARTUP_MENU
        else -> Decision.BOUNDED_SAFE_PAUSE
    }

    fun isCaptureDue(nowMs: Long, nextAllowedAtMs: Long): Boolean = nowMs >= nextAllowedAtMs

    fun nextCaptureAt(nowMs: Long): Long = nowMs + CAPTURE_COOLDOWN_MS

    /** OCR/capture latency is not observation time: slow reads must not consume the retry window. */
    fun extendDeadlineForObservation(deadlineMs: Long, startedAtMs: Long, completedAtMs: Long): Long =
        deadlineMs + (completedAtMs - startedAtMs).coerceAtLeast(0L)

    fun isObservedMenu(screen: String?, confidence: Int): Boolean =
        screen in observableMenuScreens && confidence >= MIN_MENU_CONFIDENCE

    fun isAuthorized(
        gameWindowVerified: Boolean,
        currentPid: Long?,
        windowPid: Long?,
        mode: String?,
        working: Boolean,
        paused: Boolean,
        activeMatch: Boolean,
        terminal: Boolean,
    ): Boolean = gameWindowVerified &&
        currentPid != null && currentPid > 0L && currentPid == windowPid &&
        mode in allowedModes && working && !paused && !activeMatch && !terminal
}
